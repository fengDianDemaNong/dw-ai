package com.dwai.platform;

import com.dwai.platform.meta.ProductCodes;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 把技术方案 §2 的三条<b>禁止项</b>变成源码级的守卫。
 *
 * <p>{@code docs/tech/07-0.2.0.md} §2 写得很硬：
 * <blockquote>
 * <b>禁止</b>：模块共库（含同一 MySQL/PostgreSQL 库名）、前端直打兄弟 {@code /api}、
 * 把组织库主键当跨服务 code。
 * </blockquote>
 *
 * <p>这三条是<b>跨进程</b>的约定，任何单个服务的功能测试都碰不到它们 ——
 * 违反之后服务各自都「正常」，只有整套跑在一起才会出问题（两个服务互相覆盖同一张表、
 * 模块绕过组织直接读别人的业务接口、主键与 code 在不同库里指向不同租户）。
 * 因此只能靠扫源码来守。
 *
 * <p>这几条断言都<b>允许兄弟模块不在场</b>（{@code assumeTrue} 跳过）：
 * 每个服务都要能独立拿出去部署，单独 checkout 时这些测试应当跳过而不是失败。
 */
class CrossServiceDesignGuardTest {

    /** 从 {@code dw-org/api} 出发的仓库根。 */
    private static final Path REPO = Path.of("..", "..");

    private static final Path ORG_API = REPO.resolve(Path.of("dw-org", "api"));
    private static final Path ORG_UI = REPO.resolve(Path.of("dw-org", "ui"));
    private static final Path MODEL_API = REPO.resolve(Path.of("dw-model", "api"));
    private static final Path LINEAGE = REPO.resolve(Path.of("dw-lineage", "api"));

    // ------------------------------------------------------------------
    // 禁止 1：共库
    // ------------------------------------------------------------------

    /**
     * 三个服务必须各自一套库名。
     *
     * <p>共库的后果不是「多写几行 SQL」：三个服务各有自己的 Flyway 迁移脚本，
     * 指向同一个库时谁都可能先建表、谁都可能改表，失败形态是「先启动的那个赢」，
     * 而且迁移历史表只有一份、互相认为对方是漂移。
     */
    @Test
    void servicesDoNotShareDatabase() {
        Path orgYml = ORG_API.resolve(Path.of("src/main/resources/application.yml"));
        Path modelYml = MODEL_API.resolve(Path.of("src/main/resources/application.yml"));
        Path lineageYml = LINEAGE.resolve(Path.of("src/main/resources/application.yml"));
        assumeTrue(Files.exists(modelYml) && Files.exists(lineageYml),
                "兄弟模块不在场，跳过（本服务可独立 checkout）");

        String org = read(orgYml);
        String model = read(modelYml);
        String lineage = read(lineageYml);

        // 各服务自己的库名（默认值）
        assertTrue(org.contains("db-file: dw_org"), "组织库名变了，请同步更新本守卫");
        assertTrue(model.contains("db-file: dw_mode"), "仓建设库名变了，请同步更新本守卫");
        assertTrue(lineage.contains("DB_NAME:dw_lineage"), "数据地图库名变了，请同步更新本守卫");

        Set<String> names = new LinkedHashSet<>(List.of("dw_org", "dw_mode", "dw_lineage"));
        assertEquals(3, names.size(), "库名有重复: " + names);

        // 反向检查：任何一家的配置里都不许出现兄弟的库名
        assertNotReferencing(org, "dw-org", List.of("dw_mode", "dw_lineage"));
        assertNotReferencing(model, "dw-model", List.of("dw_org", "dw_lineage"));
        assertNotReferencing(lineage, "dw-lineage", List.of("dw_org", "dw_mode"));
    }

    // ------------------------------------------------------------------
    // 禁止 2：直打兄弟业务 /api
    // ------------------------------------------------------------------

    /**
     * 服务间调用只能落在 {@code /internal/v1/**}。
     *
     * <p>「模块直打兄弟业务 {@code /api}」看起来能少写代码，代价是把对方的业务响应
     * 变成了事实契约：对方改一个字段就要同步升级两个服务，而且绕过了组织的授权判定
     * （{@code /api} 是给浏览器用的，按用户身份鉴权；服务间应当走模块令牌那条线）。
     *
     * <p>实现上以包为界：只有 {@code internal} 包允许知道兄弟服务的地址，
     * 所以只扫这些文件里的路径字面量，不误伤同包内的普通业务字符串。
     */
    @Test
    void modulesDoNotCallSiblingBusinessApis() {
        List<Path> internalDirs = List.of(
                Path.of("src/main/java/com/dwai/platform/internal"),
                MODEL_API.resolve(Path.of("src/main/java/com/dwai/platform/internal")),
                LINEAGE.resolve(Path.of("src/main/java/com/dwai/lineage/internal")));

        int scanned = 0;
        for (Path dir : internalDirs) {
            if (!Files.isDirectory(dir)) continue;
            for (Path file : javaFiles(dir)) {
                scanned++;
                for (String literal : literals(read(file))) {
                    assertFalse(literal.startsWith("/api/"),
                            file + " 里出现了直打兄弟业务接口的路径: " + literal
                                    + " —— 服务间调用必须走 /internal/v1/**");
                }
            }
        }
        assertTrue(scanned > 0, "一个 internal 包都没扫到，守卫失效了（路径写错了？）");
    }

    // ------------------------------------------------------------------
    // 禁止 3：拿组织主键当跨服务 code
    // ------------------------------------------------------------------

    /**
     * 跨进程传租户/项目时只认 code（{@code tenantCode} / {@code projectCode}）。
     *
     * <p>组织库的主键形如 {@code t-xinghe}，是<b>那个库自己的</b>自增/约定值；
     * 模块库里的主键是另一套。把组织主键当跨服务标识传，等于假设「两边的 1 是同一个租户」——
     * 一旦哪天不是，症状是数据写到别的租户名下。
     *
     * <p>注意职责边界：<b>接收方</b>（组织的 {@code /internal/v1/authz/check}）在过渡期
     * 仍接受 {@code tenantId} 做兼容，那里的 legacy 参数是刻意的。本守卫只管<b>发起方</b> ——
     * 模块不许把主键当成契约的一部分发出去。
     */
    @Test
    void callersSendCodesNotPrimaryKeys() {
        Path modelClient = MODEL_API.resolve(
                Path.of("src/main/java/com/dwai/platform/internal/OrgClient.java"));
        Path lineageClient = LINEAGE.resolve(
                Path.of("src/main/java/com/dwai/lineage/internal/OrgClient.java"));
        assumeTrue(Files.exists(modelClient) && Files.exists(lineageClient),
                "兄弟模块不在场，跳过");

        String model = read(modelClient);
        assertTrue(model.contains("\"tenantCode\""), "仓建设调组织应当按 tenantCode 走");
        assertTrue(model.contains("\"projectCode\""), "仓建设调组织应当按 projectCode 走");
        assertFalse(model.contains("queryParam(\"tenantId\""),
                "仓建设把组织主键当跨服务参数发出去了 —— 该用 tenantCode");
        assertFalse(model.contains("queryParam(\"projectId\""),
                "仓建设把组织主键当跨服务参数发出去了 —— 该用 projectCode");

        assertFalse(read(lineageClient).contains("queryParam(\"tenantId\""),
                "数据地图把组织主键当跨服务参数发出去了 —— 该用 tenantCode");
    }

    // ------------------------------------------------------------------
    // 约定 4：前端嵌壳的 token 续期通道，两端必须都真的接上
    // ------------------------------------------------------------------

    /**
     * 壳推 token、子应用收 token，两端必须对齐。
     *
     * <p>这条守卫来自一次真实事故：壳端的 {@code ProductEmbed.vue} 声明了
     * {@code MSG_TOKEN} 常量、也 import 了 {@code AUTH_TOKEN_EVENT}，
     * 但<b>既没监听也没 postMessage</b> —— 协议只写了一半，看起来却像已实现。
     * 症状是子应用手里 15 分钟的令牌过期后只能靠重建 iframe 兜住（页面状态全丢），
     * 而读代码的人会以为续期是好的。
     *
     * <p>为什么必须扫源码：协议横跨两个前端应用，两边都<b>没有单元测试</b>，
     * 任何一端漏了在各自的构建里都是「通过」。
     *
     * <p>扫的是<b>全仓所有副本</b>而非某一条硬编码路径：门户集成后 dw-org 也持有
     * 一份 {@code ProductEmbed.vue}（照抄自 dw-model）。硬编码只能守住原版，
     * 而复制粘贴出来的新副本恰恰是漂移风险最高、又最没人守的地方。
     *
     * <p>还有一条容易忽略的不变量：壳端的 sessionKey（决定何时重建 iframe）
     * <b>不能包含 token</b>。包含的话 token 一变就重建 iframe，
     * 消息通道就白做了 —— 功能「能用」，代价悄悄从「原地换」退化成「整个重载」。
     */
    @Test
    void embedTokenChannelIsWiredOnBothEnds() {
        List<Path> shells = findEmbedShells();
        List<Path> children = findEmbedChildren();
        assumeTrue(!shells.isEmpty() && !children.isEmpty(),
                "前端不在场（本服务可独立 checkout），跳过");

        // 1) 所有壳端与子端用同一个消息类型 —— 名字对不上时推送会被静默丢弃
        for (Path shell : shells) {
            String shellSrc = read(shell);
            assertTrue(shellSrc.contains("'dw-embed-token'"),
                    shell + " 里没有 dw-embed-token 消息类型，续期通道断了");

            // 2) 壳端不只是声明：常量至少要被引用一次（postMessage 里）
            assertTrue(count(shellSrc, "MSG_TOKEN") >= 2,
                    shell + " 只声明了 MSG_TOKEN 却没用它 —— "
                            + "这正是那次事故的形态：协议看起来实现了，实际没人发");

            // 3) 壳端订阅了续期事件，且卸载时摘掉（否则组件重建后监听器越积越多）
            assertTrue(shellSrc.contains("addEventListener(AUTH_TOKEN_EVENT"),
                    shell + " 没有监听 AUTH_TOKEN_EVENT，token 换了也推不出去");
            assertTrue(shellSrc.contains("removeEventListener(AUTH_TOKEN_EVENT"),
                    shell + " 没有在卸载时摘掉 AUTH_TOKEN_EVENT 监听，会越积越多");

            // 4) 关键不变量：token 不得参与 iframe 的会话身份
            assertFalse(shellSrc.contains("boot.token"),
                    shell + " 把 token 算进了 sessionKey —— token 一变就重建 iframe，"
                            + "原地续期退化成整页重载，消息通道白做");
        }

        for (Path child : children) {
            String childSrc = read(child);
            assertTrue(childSrc.contains("'dw-embed-token'"),
                    child + " 里没有 dw-embed-token 消息类型，续期通道断了");

            // 5) 子端不只是声明：收到之后要真的套用
            assertTrue(childSrc.contains("EMBED_TOKEN") && childSrc.contains("applyAccessToken"),
                    child + " 收到了 dw-embed-token 却没套用（applyAccessToken 未调用）");
        }
    }

    /**
     * 续期通道的第二条腿：子应用令牌失效时能<b>主动求</b>，壳端能<b>应</b>。
     *
     * <p>只有「壳推、子收」还不够。两种情况会漏：壳在「iframe 已创建、子应用还没
     * ready」的窗口里续过一次（那次推送没人接），以及子应用加载时手里的 `#boot=` 令牌
     * 已经过期。两种都会让子应用停在 401 上——修之前的表现就是数据地图整片空白。
     *
     * <p>于是子端要能发 `dw-embed-token-request`、壳端要能应，且子端的 401 处理要
     * 真的走这条路（而不是把 401 直接抛给用户）。这条握手的内容是令牌，
     * 所以<b>两端都不许用 `'*'` 广播</b>。
     */
    @Test
    void embedTokenRequestChannelIsWiredOnBothEnds() {
        List<Path> shells = findEmbedShells();
        List<Path> children = findEmbedChildren();
        assumeTrue(!shells.isEmpty() && !children.isEmpty(),
                "前端不在场（本服务可独立 checkout），跳过");

        // 1) 所有壳端与子端用同一个消息类型
        for (Path shell : shells) {
            String shellSrc = read(shell);
            assertTrue(shellSrc.contains("'dw-embed-token-request'"),
                    shell + " 里没有 dw-embed-token-request 消息类型，子应用求不到新令牌");

            // 2) 壳端真的在分发里响应它（声明 + 分支判断）
            assertTrue(count(shellSrc, "MSG_TOKEN_REQUEST") >= 2,
                    shell + " 声明了 MSG_TOKEN_REQUEST 却没在消息分发里响应 —— "
                            + "子应用发出去的求令牌请求会被静默丢弃");

            // 3) 握手内容含令牌，不许广播
            assertFalse(shellSrc.contains(", '*'"),
                    shell + " 用 '*' 广播 —— 响应里带 token，等于交给任何监听的窗口");
        }

        for (Path child : children) {
            String childSrc = read(child);
            assertTrue(childSrc.contains("'dw-embed-token-request'"),
                    child + " 里没有 dw-embed-token-request 消息类型，子应用求不到新令牌");

            // 4) 子端不只是声明：常量至少被引用一次（postMessage 里）
            assertTrue(count(childSrc, "EMBED_TOKEN_REQUEST") >= 2,
                    child + " 只声明了 EMBED_TOKEN_REQUEST 却没用它 —— 求令牌的请求发不出去");

            assertFalse(childSrc.contains(", '*'"),
                    child + " 用 '*' 广播 —— 请求虽然不带 token，但定向发送是纪律");

            // 5) 子端的 401 真的走这条路，且只在 multi 下（其他模式后端不认证）。
            //    401 处理落在子应用自己的 request.ts，与 embed.ts 同源同理。
            Path childReq = child.getParent().getParent().resolve(Path.of("utils", "request.ts"));
            if (!Files.exists(childReq)) continue;
            String childReqSrc = read(childReq);
            assertTrue(childReqSrc.contains("requestEmbedToken"),
                    childReq + " 的 401 处理没有去求新令牌 —— 数据地图会在令牌过期后整片空白");
            assertTrue(childReqSrc.contains("status === 401") && childReqSrc.contains("isMulti()"),
                    childReq + " 的 401 兜底缺少 multi 判断 —— "
                            + "standalone / standard 下会把用户往组织登录页踢");
        }
    }

    // ------------------------------------------------------------------
    // 约定 5：产品码与许可模块名是两套词汇，包含关系必须成立
    // ------------------------------------------------------------------

    /**
     * 菜单按「租户许可的模块名」过滤，产品码必须落在同一套词汇里。
     *
     * <p>这是<b>静默失败</b>的典型：{@code nav_items.product} 与
     * {@code tenant_licenses.modules} 目前逐字相同（{@code warehouse} / {@code metadata}
     * / {@code quality} / {@code serve}），所以服务端直接做字符串比较就够了。
     * 一旦有人往产品码里加了个许可表里没有的名字（比如 {@code lineage}），
     * 表现是「接口 200、菜单配好了、侧栏就是不出现」—— 三处配置看起来都对，
     * 没有一行报错，只能靠人肉比对两张表。
     *
     * <p>所以这里把两件事钉住：服务端 {@code ProductCodes} 的产品码是许可词汇表的子集；
     * 前端两份清单（许可的 {@code iam.ts}、产品的 {@code products.ts}）与服务端逐字一致。
     * 前端的清单只是显示用（过滤在服务端），但它同样是人工维护的，同样会漂。
     */
    @Test
    void productCodeVocabulariesAgree() {
        assertTrue(ProductCodes.LICENSE_MODULES.containsAll(ProductCodes.KNOWN),
                "产品码里出现了许可表中没有的名字，菜单会被静默过滤掉: "
                        + minus(ProductCodes.KNOWN, ProductCodes.LICENSE_MODULES));

        Path iam = ORG_UI.resolve(Path.of("src", "config", "iam.ts"));
        Path products = ORG_UI.resolve(Path.of("src", "config", "products.ts"));
        assumeTrue(Files.exists(iam) && Files.exists(products), "前端不在场，跳过");

        Set<String> licensed = arrayValues(read(iam), "MODULE_OPTIONS", iam);
        assertEquals(ProductCodes.LICENSE_MODULES, licensed,
                "iam.ts 的 MODULE_OPTIONS 与服务端 ProductCodes.LICENSE_MODULES 漂了");

        Set<String> productsUi = arrayValues(read(products), "PRODUCT_OPTIONS", products);
        assertEquals(ProductCodes.KNOWN, productsUi,
                "products.ts 的 PRODUCT_OPTIONS 与服务端 ProductCodes.KNOWN 漂了");
    }

    /** {@code KNOWN - other}，只用来把失败信息说清楚。 */
    private static Set<String> minus(Set<String> known, Set<String> other) {
        Set<String> out = new LinkedHashSet<>(known);
        out.removeAll(other);
        return out;
    }

    /**
     * 从 TS 源码里取出 {@code const <name> = [ ... ];} 数组中所有 {@code value: 'x'} 的 x。
     *
     * <p>只截到第一个 {@code ];} 为止，免得把同文件里别的选项数组（{@code AI_CAP_OPTIONS}）
     * 一起收进来。解析不到就直接失败 —— 这条守卫宁可吵，不能因为正则在改版后失配
     * 而变成永远通过。
     */
    private static Set<String> arrayValues(String src, String constName, Path where) {
        int start = src.indexOf("const " + constName);
        assertTrue(start >= 0, "没能在这份源码里找到 " + constName + ": " + where);
        int end = src.indexOf("];", start);
        assertTrue(end > start, "没能找到 " + constName + " 数组的结尾（写法变了？）: " + where);

        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("value:\\s*'([^']+)'").matcher(src.substring(start, end));
        while (m.find()) out.add(m.group(1));
        assertFalse(out.isEmpty(), constName + " 里一个 value 都没解析出来: " + where);
        return out;
    }

    // ------------------------------------------------------------------

    /** 全仓所有「壳」副本：{@code <module>/ui/src/components/ProductEmbed.vue}。 */
    private static List<Path> findEmbedShells() {
        return findInUi(Path.of("components", "ProductEmbed.vue"));
    }

    /** 全仓所有「子应用接收端」副本：{@code <module>/ui/src/config/embed.ts}。 */
    private static List<Path> findEmbedChildren() {
        return findInUi(Path.of("config", "embed.ts"));
    }

    /**
     * 扫 {@code <module>/ui/src/<suffix>}，跨模块、跨副本。
     *
     * <p>深度 5 正好够到 {@code <module>/ui/src/components/X.vue}
     * （仓库根为 0）；不必更深，嵌壳相关的文件都在前端源码树里。
     */
    private static List<Path> findInUi(Path suffix) {
        Path target = Path.of("ui", "src").resolve(suffix);
        try (Stream<Path> s = Files.walk(REPO, 5)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.endsWith(target))
                    .filter(p -> !p.toString().contains("node_modules"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            n++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return n;
    }

    private static void assertNotReferencing(String config, String who, List<String> others) {
        for (String other : others) {
            assertFalse(config.contains(other),
                    who + " 的配置里引用了兄弟库名 " + other + " —— 违反「不共库」");
        }
    }

    private static List<Path> javaFiles(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 取出源码里的字符串字面量。 */
    private static List<String> literals(String source) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(source);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
