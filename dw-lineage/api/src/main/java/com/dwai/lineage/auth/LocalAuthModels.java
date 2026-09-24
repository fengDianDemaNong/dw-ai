package com.dwai.lineage.auth;

/**
 * standard 模式本地认证的请求 / 响应体。
 *
 * <p>字段名刻意与 dw-model 的 {@code ApiModels}（{@code LoginRes} / {@code LoginReq} /
 * {@code RefreshReq} / {@code ProfileReq} / {@code PasswordReq}）保持<b>同名同义</b> ——
 * 前端只写一套登录逻辑就能同时对接两个服务，切换服务不用改字段。
 *
 * <p>但类型不共用：数据地图不依赖 dw-model，把它那几个 record 抽进 dw-common 会让
 * 「独立部署」这个卖点多出一份耦合。这里只有 5 个极小的 record，重复的代价低于耦合的代价。
 *
 * <p>与 model 的 LoginRes 相比去掉的字段（{@code needSelectTenant} / {@code platformAdmin} /
 * {@code tenants}）不是遗漏：数据地图的 standard 模式<b>只有默认租户</b>，没有「选组织」
 * 这一步，令牌也只认人、不携带租户（租户由 {@code TenantInterceptor} 从请求头解析）。
 */
public final class LocalAuthModels {

    private LocalAuthModels() {
    }

    public record LoginReq(String username, String password) {
    }

    public record RefreshReq(String refreshToken) {
    }

    /**
     * 登录 / 刷新的响应。
     *
     * <p>{@code refreshToken} 的<b>明文只在这里出现一次</b>，库里存的是 SHA-256。
     * 前端必须把它持久化，丢了就只能重新登录。
     */
    public record LoginRes(
            String token,
            String refreshToken,
            long expiresIn,
            long userId,
            String username,
            String displayName) {
    }

    /**
     * 当前登录者。
     *
     * <p>不含租户信息 —— 租户来自请求头，不是账号属性（见本类说明）。
     *
     * <p>含 {@code admin}：前端用它决定要不要显示「账号管理」入口。
     * 这个字段只是<b>便于渲染</b>，不是授权依据 —— 真正的判权在后端
     * （{@code LocalUserController.requireAdmin()}）。前端隐藏入口是为了不去点一个
     * 必然 403 的页面，不是安全措施。
     */
    public record Me(
            long userId,
            String username,
            String displayName,
            boolean admin,
            String runMode) {
    }

    public record ProfileReq(String displayName) {
    }

    public record PasswordReq(String currentPassword, String newPassword) {
    }
}
