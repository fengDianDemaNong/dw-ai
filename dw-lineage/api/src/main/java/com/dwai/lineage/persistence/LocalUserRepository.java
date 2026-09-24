package com.dwai.lineage.persistence;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * standard 模式的本地账号与刷新令牌存取。
 *
 * <p>与业务表不同，这两张表<b>刻意不带 {@code tenant_id}</b>（登录发生在知道租户之前），
 * 因此被 {@code TenantIsolationArchTest} 按表豁免 —— 见该测试类里
 * {@code TENANT_FREE_TABLES} 的说明。
 *
 * <h2>哪些方法负责「不把自己锁在门外」</h2>
 *
 * <p>{@link #countEnabledAdmins()} 是账号管理面自保的基础：停用、降级、删除管理员之前，
 * 服务层必须先用它确认「至少还剩一个可用的管理员」。没有这道检查，一次误操作
 * 就能把部署变成没有人能再登进去的状态 —— 而那种状态<b>只能进库改</b>，
 * 是本地部署最难受的故障。
 *
 * <p>本接口只提供计数与增删改查，<b>不做这项判断</b>：判断属于业务规则，
 * 放在 {@code LocalUserService} 里，仓储保持哑的。
 */
public interface LocalUserRepository {

    Optional<LocalUserRow> findByUsername(String username);

    Optional<LocalUserRow> findById(long id);

    /** 全部账号，按 id 升序。管理面用。 */
    List<LocalUserRow> listUsers();

    /** 账号总数。LocalAdminSeedRunner 用「0 个账号」判断是否要种初始管理员。 */
    long countUsers();

    /**
     * <b>可用的</b>管理员数量（{@code is_admin = 1 且 status = 1}）。
     *
     * <p>注意是「可用」而不是「所有」：被停用的管理员**登不进来**，
     * 所以它救不了你。判断「能不能停用/降级/删除这个管理员」时必须用这个数 ——
     * 用「所有管理员数量」会放过一种致命组合：库里有 2 个管理员、其中 1 个已被停用，
     * 于是你以为还有备份，把仅剩的那个也停了，结果谁也进不来。
     */
    long countEnabledAdmins();

    /** 插入账号，返回自增主键。 */
    long insertUser(String username, String displayName, String passwordHash, boolean admin);

    /** 改显示名，返回改动后的行（返回 null 说明账号已不存在）。 */
    LocalUserRow rename(long id, String displayName);

    /** 改密码哈希。调用方负责编码，本方法不碰明文。 */
    boolean changePassword(long id, String passwordHash);

    /** 启用（1）/ 停用（0）。返回是否命中一行。 */
    boolean updateStatus(long id, int status);

    /** 提升 / 取消管理员。返回是否命中一行。 */
    boolean updateAdmin(long id, boolean admin);

    /** 物理删除账号。返回是否命中一行。 */
    boolean deleteUser(long id);

    // ---------- 刷新令牌 ----------

    void insertRefreshToken(String id, long userId, String tokenHash, LocalDateTime expiresAt);

    Optional<RefreshTokenRow> findRefreshTokenByHash(String tokenHash);

    /** 顺延刷新令牌的过期时间（滑动续期）。 */
    boolean extendRefreshTokenExpiry(String id, LocalDateTime expiresAt);

    boolean deleteRefreshTokenByHash(String tokenHash);

    void deleteRefreshTokensByUser(long userId);

    /** 清掉已过期的刷新令牌。签发时顺手做，不用另起定时任务。 */
    int deleteExpiredRefreshTokens(LocalDateTime now);
}
