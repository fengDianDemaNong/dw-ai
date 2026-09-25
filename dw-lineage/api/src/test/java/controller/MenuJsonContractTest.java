package controller;

import com.dwai.lineage.conf.LineageProperties;
import com.dwai.lineage.controller.RuntimeController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 菜单候选（`ui/public/menu.json`）必须覆盖 {@code GET /api/manifest} 的 {@code menus}，
 * 且同一页面的权限词要一致。
 *
 * <h2>守的是什么</h2>
 *
 * {@code RuntimeController.manifest()} 的 javadoc 把这条契约写死了：同一批页面在
 * <b>前端菜单</b>与<b>后端自描述</b>里必须说同一个路径、同一个权限词。这两份清单
 * 一份是 TypeScript（`ui/src/config/navData.ts`，由 `ui/scripts/gen-menu.mjs` 展开成
 * `menu.json` 供组织平台拉取）、一份是 Java 字面量，<b>编译器管不着彼此</b>。
 *
 * <p>判据是<b>包含</b>（manifest ⊆ 候选）而不是相等：候选清单面向组织平台的壳，
 * 可以比自描述宽 —— 例如「元数据服务」不在数据地图自己的侧栏里（`projectNavGroups`），
 * 却是工作台壳需要的入口。反过来的方向才是错：manifest 说某页要 A 权限、
 * 壳按菜单配出来却按 B 权限置灰，症状是「有权限的人被置灰」或者反过来。
 *
 * <p>不一致的症状很隐蔽：组织平台按 `menu.json` 配出来的菜单能显示、能点进去，
 * 但壳拿 manifest 判断「这个人进这一页要什么角色」时对不上 —— 权限词错一个，
 * 表现是「有权限的人被置灰」或者反过来。两边都在，谁也不算错，就是不同步。
 *
 * <h2>为什么在 Java 侧写、怎么拿到那个文件</h2>
 *
 * 文件是<b>前端构建的产物</b>（`npm run gen:menu`，`prebuild` 与各 dev 入口都会跑它），
 * 所以这里用 {@code assumeTrue} 处理「还没构建过前端」的情形 —— 干净的 `mvn test`
 * 不该因为没跑过 npm 而变红。跑过一次前端再跑测试，它就会真的把关。
 *
 * <p>路径从<b>本类的 class 文件位置</b>反推（`target/test-classes` → 模块根 → `../ui/public`），
 * 不看当前工作目录：`mvn -f dw-lineage/api/pom.xml test` 与在模块目录里跑 mvn，
 * 两者的 cwd 不同，用相对路径会时灵时不灵。
 */
class MenuJsonContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 与 dw-org 的 `NavItemService.SCOPES` 同一份白名单。 */
    private static final Set<String> SCOPES = Set.of("workbench", "project");

    private static Path menuJsonPath() throws Exception {
        URL root = MenuJsonContractTest.class.getResource("/");
        assumeTrue(root != null, "取不到测试类路径");
        // target/test-classes → target → 模块目录
        Path moduleDir = Paths.get(root.toURI()).getParent().getParent();
        return moduleDir.resolve("../ui/public/menu.json").normalize();
    }

    @Test
    void menuJsonMatchesManifestMenus() throws Exception {
        Path file = menuJsonPath();
        assumeTrue(Files.exists(file),
                "还没有生成 " + file + " —— 先跑一次 `npm run gen:menu`（或任意 dev / build 入口）");

        JsonNode menuJson = MAPPER.readTree(Files.readString(file));
        JsonNode manifest = MAPPER.valueToTree(new RuntimeController(new LineageProperties()).manifest());

        assertEquals(menuJson.path("product").asText(), manifest.path("product").asText(),
                "产品码两边不一致 —— 组织平台按 menu.json 的产品码落库，manifest 却报另一个");

        Map<String, String> fromMenuJson = pathToPerm(menuJson.path("menus"));
        Map<String, String> fromManifest = pathToPerm(manifest.path("menus"));

        // 两边都空也是「包含」—— 那不说明任何事，只会让这道网静默失效
        assertTrue(!fromMenuJson.isEmpty(), "menu.json 里一条候选都没有");
        assertTrue(!fromManifest.isEmpty(), "/api/manifest 的 menus 是空的");

        // 判「manifest 描述得对不对」而不是「两边一模一样」：候选清单面向壳，可以比
        // 自描述宽（例如「元数据服务」不在数据地图自己的侧栏里，却是组织工作台要的入口）。
        // 反过来的方向才是错：manifest 说某页要 A 权限，壳按菜单配出来却按 B 权限置灰。
        for (Map.Entry<String, String> e : fromManifest.entrySet()) {
            assertTrue(fromMenuJson.containsKey(e.getKey()),
                    "/api/manifest 报了 " + e.getKey() + "，菜单候选里却没有 —— "
                            + "两份清单一份在 ui/src/config/navData.ts、一份在 RuntimeController.manifest()");
            assertEquals(e.getValue(), fromMenuJson.get(e.getKey()),
                    e.getKey() + " 的权限词两边不一致：manifest 说 " + e.getValue()
                            + "，菜单候选说 " + fromMenuJson.get(e.getKey()));
        }
    }

    /** `scope` 是组织平台 `nav_items.scope` 的白名单，写错了 org 侧会 400 而前端看不出原因。 */
    @Test
    void everyScopeIsLegal() throws Exception {
        Path file = menuJsonPath();
        assumeTrue(Files.exists(file), "还没有生成 " + file);

        for (JsonNode item : MAPPER.readTree(Files.readString(file)).path("menus")) {
            String scope = item.path("scope").asText();
            assertTrue(SCOPES.contains(scope),
                    "候选 " + item.path("path").asText() + " 的 scope「" + scope
                            + "」不在 " + SCOPES + " 里 —— org 侧写入时会被拒");
        }
    }

    /** 每条候选都要有能在侧栏画出来的最小信息，缺一项配出来的就是个空白入口。 */
    @Test
    void everyCandidateCarriesLabelAndIcon() throws Exception {
        Path file = menuJsonPath();
        assumeTrue(Files.exists(file), "还没有生成 " + file);

        for (JsonNode item : MAPPER.readTree(Files.readString(file)).path("menus")) {
            List<String> required = List.of("id", "path", "label", "icon");
            for (String field : required) {
                assertTrue(!item.path(field).asText().isBlank(),
                        "候选 " + item.path("path").asText() + " 缺 " + field);
            }
        }
    }

    private static Map<String, String> pathToPerm(JsonNode menus) {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode m : menus) out.put(m.path("path").asText(), m.path("perm").asText());
        return out;
    }
}
