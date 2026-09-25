package com.dwai.platform;

import com.dwai.platform.meta.PermWords;
import com.dwai.platform.meta.support.Perms;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 权限词的<b>形状</b>规则，以及「三份词表不许漂移」的守卫。
 *
 * <h2>为什么这个类不启 Spring</h2>
 *
 * 它测的全是静态规则与仓库里的文本产物，没有一条需要数据库或 HTTP。启一个上下文
 * 只为了读几个常量，代价是每次跑都要等一次 Flyway。
 *
 * <h2>为什么值得为「拼写」单独写一层测试</h2>
 *
 * 权限词的形状错误（{@code catalog:edit}、{@code lineage:readonly}）在这里是<b>唯一</b>
 * 地方能被抓住：写进 {@code nav_items.perm} 或 {@code product_role_perms} 之后，
 * 判定一律是「否」（{@code Perms.has} 不认），而界面上表现为入口一直在原位置灰、
 * 或者角色看起来配了权限却什么都做不了 —— 没有一行报错，也没有任何地方会显示
 * 「你写的这个词不存在」。守卫必须落在写入时，而写入时的规则就在这个类里。
 *
 * <h2>三份词表</h2>
 *
 * <ol>
 *   <li>{@code dw-common} 的 {@code Perms.java} —— <b>权威定义</b>（本方案明确不改）。</li>
 *   <li>{@code packages/engine/src/iam.ts} 的 {@code Perm} 联合类型 —— 前端权威。</li>
 *   <li>各产品 {@code config/navData.ts} 的 {@code PERM_OPTIONS} —— 产品<b>自报</b>的
 *       词表，组织侧的两个下拉与写入校验都吃它（见 {@code MenuCandidateService}）。</li>
 * </ol>
 *
 * <p>第 1、2 份改不动（本方案的范围之外），所以本类守的是「第 3 份与第 1 份、
 * 以及第 3 份与迁移里的种子数据」这两条边：
 *
 * <ul>
 *   <li>{@link #hardcodedPermsMatchTheShapeRules()} —— 反射读 {@code Perms} 的全部词，
 *       逐个断言 {@link PermWords} 认它。org 侧那份形状规则是抄的第三份，抄歪了
 *       就会拒绝一个合法词（管理员配不了菜单），或放过一个非法词（写进库永远判否）。</li>
 *   <li>{@link #seedPermsComeFromProductWordLists()} —— 迁移里给内置角色灌的每一个词，
 *       都必须是该产品自报词表里的词。种子与产品词表是两份手写的文本，
 *       <b>漂移的表现是内置角色一开始就带着一个产品不认的词</b>（升级即坏，且只在
 *       用那个角色时才现形）。</li>
 * </ul>
 */
class PermWordsTest {

    /** 仓库根。本类在 dw-org/api 下跑，所以是上两级。 */
    private static final Path REPO = Path.of("..", "..");

    /** 产品码 → 它的前端词表文件。只有这两个产品有真实前端；其余产品的角色还没定。 */
    private static final Map<String, Path> PRODUCT_NAVDATA = Map.of(
            "warehouse", REPO.resolve(Path.of("dw-model", "ui", "src", "config", "navData.ts")),
            "metadata", REPO.resolve(Path.of("dw-lineage", "ui", "src", "config", "navData.ts")));

    private static final Path SEED_SQL = REPO.resolve(Path.of(
            "dw-org", "api", "src", "main", "resources", "db", "migration", "mysql",
            "V20__product_roles.sql"));

    /** `{ value: 'spec:read', label: '查看规范' }` —— 只认一种写法，写歪了下面的计数断言会红。 */
    private static final Pattern PERM_OPTION = Pattern.compile(
            "\\{\\s*value:\\s*'([^']*)'\\s*,\\s*label:\\s*'([^']*)'\\s*\\}");

    /** 种子里的 `('prole-warehouse-admin', 'spec:read')`。 */
    private static final Pattern SEED_PERM = Pattern.compile("\\('(prole-[\\w-]+)',\\s*'([^']+)'\\)");

    /** 种子里的 `('prole-warehouse-admin', 'warehouse', 'admin', ...)`。 */
    private static final Pattern SEED_ROLE = Pattern.compile("\\('(prole-[\\w-]+)',\\s*'(\\w+)',\\s*'\\w+'");

    // ------------------------------------------------------------------
    // 形状规则
    // ------------------------------------------------------------------

    /**
     * <b>这条是本类存在的核心理由。</b>
     *
     * <p>用反射读 {@code Perms} 的私有矩阵，把它认的每一个词喂给 org 侧的
     * {@link PermWords#isWellFormed}。两份不一致时：合法的词被 org 拒（管理员配不了
     * 菜单，报「不是合法的权限词」而那个词明明在用），或非法的词被 org 放过
     * （写进库后 {@code Perms.has} 判否，入口永远置灰）。
     *
     * <p>反射是刻意的：如果 {@code Perms} 以后加了一个词，本用例自动覆盖到它，
     * 不需要有人记得同步一份清单过来。
     */
    @Test
    void hardcodedPermsMatchTheShapeRules() throws Exception {
        Map<String, Map<String, Set<String>>> roles = hardcodedRoles();
        assertFalse(roles.isEmpty(), "没能从 Perms 里读到任何角色矩阵 —— 反射的目标字段改名了？");

        int seen = 0;
        for (Map.Entry<String, Map<String, Set<String>>> product : roles.entrySet()) {
            for (Map.Entry<String, Set<String>> role : product.getValue().entrySet()) {
                for (String perm : role.getValue()) {
                    seen++;
                    assertTrue(PermWords.isWellFormed(perm),
                            "「" + product.getKey() + " / " + role.getKey() + "」里的权限词「" + perm
                                    + "」没能通过 org 侧的形状校验 —— 两份词表漂了，"
                                    + "管理员将会看到「不是合法的权限词」，而这个词其实在用");
                    String action = perm.substring(perm.indexOf(':') + 1);
                    assertTrue(PermWords.ACTIONS.contains(action),
                            "动作「" + action + "」不在 org 侧的动作白名单里：" + PermWords.ACTIONS);
                }
            }
        }
        assertTrue(seen >= 10, "期望至少读到 10 个 (产品,角色,权限) 组合，实际 " + seen
                + " —— 反射没读到东西时这条守卫是假绿");
    }

    /** 空串合法：它表示「这个菜单不判权，进得来就看得见」，不是错误。 */
    @Test
    void emptyPermIsAllowedAndMeansNoCheck() {
        assertTrue(PermWords.isWellFormed(""), "空串必须合法 —— 它是「不判权」的表达方式");
        assertTrue(PermWords.isWellFormed("   "), "空白串按空处理");
        assertEquals("", PermWords.requireWellFormed("", "权限词"));
        assertEquals("", PermWords.requireWellFormed("  ", "权限词"));
    }

    /** 形状错的在写入时拒掉。这些词若落库，表现是入口永远置灰、且没有任何地方报错。 */
    @Test
    void badShapesAreRejected() {
        for (String bad : new String[]{
                "catalog",          // 没有动作段
                "catalog:",         // 动作空
                ":read",            // 域空
                "Catalog:read",     // 域大写
                "catalog:READ",     // 动作大写
                "catalog:read:x",   // 三段
                "catalog:edit",     // 动作不在五档（「编辑」不是权限词，是按钮文案）
                "catalog:view",     // 同上（「查看」）
                "1catalog:read",    // 域以数字开头
                "catalog-read",     // 分隔符不对
                null}) {
            assertFalse(PermWords.isWellFormed(bad), "「" + bad + "」不该被当成合法权限词");
            assertThrows(ResponseStatusException.class,
                    () -> PermWords.requireWellFormed(bad, "权限词"),
                    "「" + bad + "」应当让写入方拿到 400，而不是静默落库");
        }
    }

    /** 合法的词形形色色都过：多字母域、带数字的域、五个动作各自。 */
    @Test
    void goodShapesAreAccepted() {
        for (String good : new String[]{
                "spec:read", "spec:write", "model:publish", "iam:member", "catalog:admin",
                "lineage2:read"}) {
            assertTrue(PermWords.isWellFormed(good), "「" + good + "」应当是合法的");
        }
    }

    // ------------------------------------------------------------------
    // 跨产物漂移
    // ------------------------------------------------------------------

    /**
     * 每个产品的词表本身要自洽：形状合法、标签非空、不重复。
     *
     * <p><b>「菜单上的词必须在词表里」这条不在这里</b>：那个检查要的是<b>解析后</b>的候选
     * （组级 {@code perm} 会下沉到组内每一项，见产品的 {@code inheritGroupPerm}），
     * 而 {@code navData.ts} 里同时住着不进候选的分组 —— 本进程里 dw-model 的
     * {@code LINEAGE_GROUP} 就带着 {@code catalog:read} 这类词，按文本一网打尽必然是假红。
     * 那个不变量的正确位置是 {@code gen-menu.mjs}（它拿得到真数据），两边脚本里都有。
     */
    @Test
    void productWordListsAreSelfConsistent() throws IOException {
        for (Map.Entry<String, Path> entry : PRODUCT_NAVDATA.entrySet()) {
            String product = entry.getKey();
            Path file = entry.getValue();
            assumeTrue(Files.exists(file), "跳过：找不到 " + file);

            Map<String, String> words = permOptions(Files.readString(file, StandardCharsets.UTF_8));
            assertFalse(words.isEmpty(),
                    product + " 的 PERM_OPTIONS 一个词都没解析出来 —— 解析规则或文件结构变了？"
                            + "（守卫失效比守卫变红更糟，所以这里直接失败）");

            for (Map.Entry<String, String> word : words.entrySet()) {
                assertTrue(PermWords.isWellFormed(word.getKey()),
                        product + " 自报的权限词「" + word.getKey() + "」形状不合法 —— "
                                + "组织侧整份校验，一个非法词会让这个产品的菜单候选都拉不到");
                assertFalse(word.getValue().isBlank(),
                        product + " 的词「" + word.getKey() + "」没有 label，组织侧的下拉会显示裸权限词");
            }
        }
    }

    /**
     * 迁移里给内置角色灌的每个词，都必须是该产品自报词表里的词。
     *
     * <p>种子（SQL）与词表（TS）是两份手写的文本，中间没有任何东西保证一致。
     * 漂移的后果<b>在升级那一刻就发生了</b>：内置的「目录管理员」带着一个产品不认的词，
     * 而它只在有人真的用这个角色时才现形。
     */
    @Test
    void seedPermsComeFromProductWordLists() throws IOException {
        assumeTrue(Files.exists(SEED_SQL), "跳过：找不到 " + SEED_SQL);
        String sql = Files.readString(SEED_SQL, StandardCharsets.UTF_8);

        Map<String, String> roleProduct = new LinkedHashMap<>();
        Matcher role = SEED_ROLE.matcher(sql);
        while (role.find()) roleProduct.put(role.group(1), role.group(2));
        assertFalse(roleProduct.isEmpty(), "没能从种子里解析出任何角色 —— 解析规则或 SQL 结构变了？");

        Map<String, Set<String>> seeded = new LinkedHashMap<>();
        Matcher perm = SEED_PERM.matcher(sql);
        while (perm.find()) {
            String product = roleProduct.get(perm.group(1));
            assertTrue(product != null, "种子里的权限行指向一个没见过的角色 " + perm.group(1));
            seeded.computeIfAbsent(product, k -> new LinkedHashSet<>()).add(perm.group(2));
        }
        assertFalse(seeded.isEmpty(), "没能从种子里解析出任何权限词 —— 守卫失效了");

        for (Map.Entry<String, Set<String>> entry : seeded.entrySet()) {
            String product = entry.getKey();
            Path file = PRODUCT_NAVDATA.get(product);
            if (file == null || !Files.exists(file)) continue; // quality / serve 还没有前端
            Set<String> words = permOptions(Files.readString(file, StandardCharsets.UTF_8)).keySet();
            for (String word : entry.getValue()) {
                assertTrue(words.contains(word),
                        "迁移给「" + product + "」的内置角色灌了「" + word + "」，但该产品的 PERM_OPTIONS 里没有它 —— "
                                + "内置角色一开始就带着一个产品不认的词（判否）。词表：" + words);
            }
        }
    }

    // ------------------------------------------------------------------

    /** `{value,label}` 按出现顺序，重复的 value 直接失败（重复的下拉项无法解释）。 */
    private static Map<String, String> permOptions(String source) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = PERM_OPTION.matcher(source);
        while (m.find()) {
            String value = m.group(1);
            String prev = out.put(value, m.group(2));
            assertEquals(null, prev, "权限词「" + value + "」在词表里出现了两次");
        }
        return out;
    }

    /** 反射读 {@code Perms} 的私有矩阵。改它的结构会让 {@link #hardcodedPermsMatchTheShapeRules} 红。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Set<String>>> hardcodedRoles() throws Exception {
        Field field = Perms.class.getDeclaredField("ROLE");
        field.setAccessible(true);
        return (Map<String, Map<String, Set<String>>>) field.get(null);
    }
}
