package com.dwai.lineage.controller;

import com.dwai.lineage.conf.LineageProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api", "/api/v1"})
public class RuntimeController {
    private final LineageProperties props;

    public RuntimeController(LineageProperties props) {
        this.props = props;
    }

    @GetMapping("/runtime")
    public Map<String, Object> runtime() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("product", "metadata");
        out.put("runMode", props.runMode());
        out.put("standalone", props.isStandalone());
        out.put("standard", props.isStandard());
        out.put("multi", props.isMulti());
        // 组织平台的**前端**地址。本页被直接打开（地址栏手打、没有 `#boot=` 自报宿主）时，
        // 前端靠它把「回门户登录」这类跳转指对地方 —— 以前这个地址来自构建期的
        // `VITE_ORG_ORIGIN`，改一次要重新构建前端。空 = 没配，前端用内置默认。
        //
        // 注意这里给的是 org-ui-url 而不是 org-base-url：后者是**后端**基址（5171 vs 18080
        // 在开发态就不一样），拿它拼 `/org/login` 会跳到接口服务上的 404。
        out.put("orgUiUrl", props.getOrgUiUrl() == null ? "" : props.getOrgUiUrl());
        return out;
    }

    /**
     * 服务自描述：本服务有哪些角色、哪些菜单，各自要什么权限。
     *
     * <p>给组织工作台（壳）用 —— 「进入项目前就知道哪些服务有哪些权限」。壳拿它去画入口、
     * 决定点进某个产品时该带什么角色，所以它<b>不能要求身份</b>：壳是在拿到用户身份
     * <b>之前</b>来问这份清单的（见 {@code SecurityConfig} 的 {@code PUBLIC_PATHS}）。
     * 与 dw-org / dw-model 的同名端点同一口径。
     *
     * <p>权限词与 {@code ROLE_PERMS}（前端共享包 `packages/engine/src/iam.ts`）以及本服务
     * 项目级菜单（`ui/src/config/nav.ts` 的 {@code projectNavGroups}）<b>逐项对齐</b> ——
     * 三处对同一个页面必须说同一个词，否则从工作台进来的和从数据地图侧栏进去的是两套口径。
     * {@code perm} 为空串 = 谁都能进。
     */
    @GetMapping("/manifest")
    public Map<String, Object> manifest() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("product", "metadata");
        out.put("version", "0.1.5");
        out.put("runMode", props.runMode());
        out.put("roles", List.of(
                Map.of("code", "admin", "label", "目录管理员"),
                Map.of("code", "modeler", "label", "血缘分析"),
                Map.of("code", "viewer", "label", "只读访客")));
        out.put("menus", List.of(
                Map.of("id", "metadata.home", "path", "/lineage", "perm", ""),
                Map.of("id", "metadata.search", "path", "/lineage/search", "perm", "catalog:read"),
                Map.of("id", "metadata.lineage", "path", "/lineage/tables", "perm", "lineage:read"),
                Map.of("id", "metadata.catalogs", "path", "/lineage/catalogs", "perm", "catalog:admin"),
                Map.of("id", "metadata.tempRules", "path", "/lineage/temp-rules", "perm", "catalog:admin"),
                Map.of("id", "metadata.analyze", "path", "/lineage/analyze", "perm", "lineage:read"),
                Map.of("id", "metadata.meta", "path", "/lineage/meta", "perm", "catalog:read"),
                Map.of("id", "metadata.settings", "path", "/lineage/settings/map", "perm", "catalog:admin")));
        return out;
    }
}
