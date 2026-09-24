package com.dwai.lineage.auth;

import com.dwai.lineage.conf.LineageProperties;
import com.dwai.lineage.persistence.LocalUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * standard 模式空库时补一个初始管理员。
 *
 * <p>照 dw-model 的 {@code WarehouseLocalSeedRunner.addUser} 而来：账号名与初始密码
 * 都是 {@code admin / 123456}，让「独立部署」这条路上的第一次登录是确定的。
 *
 * <h2>触发条件刻意收得很窄</h2>
 *
 * <p>只在<b>standard 模式</b>且<b>一个账号都没有</b>时种。三个模式各自的行为：
 *
 * <pre>
 *   standalone → 不种。没有登录，种了也没人用。
 *   standard   → 空库时种 admin。
 *   multi      → 不种。身份由组织签发，users 表保持为空（种了反而会被误读成账号体系在工作）。
 * </pre>
 *
 * <p>「一个都没有」这个条件保证了：管理员改过密码后重启不会被重置回 {@code 123456}；
 * 管理员手工删掉 admin 建了自己的账号之后，也不会凭空又冒出一个 admin。
 *
 * <h2>「有账号但没有可用管理员」的兜底</h2>
 *
 * <p>这不正常，但可能发生：有人直接改了库（把 is_admin 清掉、或停用了唯一的管理员）。
 * 此时所有账号管理接口都会 403，而当事人看到的只是「需要管理员权限」，
 * 完全不知道为什么、也不知道怎么修。
 *
 * <p>这里<b>不自动提权</b>——那等于给一个「改库就能触发提权」的隐式通道，还会掩盖问题 ——
 * 只打一条明确的 ERROR 日志说明现状与修法。让人去看日志，比让系统悄悄猜要好。
 *
 * <p>正常路径不会走到这条分支：{@code LocalUserService} 的
 * {@code guardLastEnabledAdmin} 挡住了「通过 API 把可用管理员清零」的所有可能。
 *
 * <p><b>⚠️ 默认密码 123456 是刻意留的口子，只对本地部署成立。</b>
 * standard 定位是「自己装一套、自己用」，首登必须能进去；而对外暴露的部署在
 * README 与部署文档里都要求先改密。不做「强制首登改密」是因为它需要一条
 * 「首次登录」的持久标记，而该标记会变成新的状态机 —— 收益不抵复杂度。
 */
@Component
@Order(1)
public class LocalAdminSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalAdminSeedRunner.class);

    /** 与 dw-model 的 WarehouseLocalSeedRunner 保持一致。 */
    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_DISPLAY_NAME = "管理员";
    private static final String ADMIN_PASSWORD = "123456";

    private final LineageProperties props;
    private final LocalUserRepository users;
    private final PasswordEncoder passwords;

    public LocalAdminSeedRunner(
            LineageProperties props,
            LocalUserRepository users,
            PasswordEncoder passwords) {
        this.props = props;
        this.users = users;
        this.passwords = passwords;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isStandard()) {
            return;
        }
        long existing = users.countUsers();
        if (existing == 0) {
            users.insertUser(ADMIN_USERNAME, ADMIN_DISPLAY_NAME,
                    passwords.encode(ADMIN_PASSWORD), true);
            log.warn("standard 模式库中尚无任何账号，已创建初始管理员 {} / {}。"
                    + "对外可访问的部署请登录后立即修改密码。", ADMIN_USERNAME, ADMIN_PASSWORD);
            return;
        }
        if (users.countEnabledAdmins() == 0) {
            log.error("standard 模式库中有 {} 个账号，但**没有一个可用的管理员** —— "
                            + "账号管理接口会全部返回 403。"
                            + "请直接把某个账号的 is_admin 置为 1 且 status 置为 1；"
                            + "或清空 users 表让本任务重新种一个 admin。"
                            + "（本任务刻意不自动提权：那会掩盖问题，且是一条隐式的提权通道。）",
                    existing);
        }
    }
}
