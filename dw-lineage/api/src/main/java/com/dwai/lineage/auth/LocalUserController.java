package com.dwai.lineage.auth;

import com.dwai.lineage.dto.LocalUserPasswordRequest;
import com.dwai.lineage.dto.LocalUserRequest;
import com.dwai.lineage.dto.LocalUserResponse;
import com.dwai.lineage.dto.LocalUserStatusRequest;
import com.dwai.lineage.persistence.LocalUserRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 本地账号管理面（standard 模式）。
 *
 * <p>与 {@code /api/auth/*} 的分工：那里是「我自己的会话」（登录、登出、改我自己的
 * 密码与显示名），这里是他人的账号（建号、停用、重置密码、删除）。
 * 分开是因为两者的授权模型完全不同 —— 前者只要登录，后者必须管理员。
 *
 * <h2>每个方法的第一行都是权限检查</h2>
 *
 * <p>{@link #requireAdmin()} 出现在所有方法（含只读的列表）的开头。这是有意的重复：
 * 这个类是「一旦漏判就是提权漏洞」的地方，把检查放在每个方法的显眼位置，
 * 比抽成类级注解或拦截器更容易在 review 时看出谁少了。
 * 新增端点时照抄第一行即可。
 *
 * <h2>为什么列表也要管理员</h2>
 *
 * <p>账号列表会暴露「这个部署上有哪些人、谁是管理员、谁被停用了」——
 * 对普通用户来说这是不必要的信息，也是攻击者做社会工程时想要的东西。
 */
@Tag(name = "本地账号管理", description = "standard 模式下的账号管理。仅管理员可用。")
@RestController
@RequestMapping({"/api/users", "/api/v1/users"})
public class LocalUserController {

    private final LocalUserService service;
    private final CurrentLocalUser current;

    public LocalUserController(LocalUserService service, CurrentLocalUser current) {
        this.service = service;
        this.current = current;
    }

    @Operation(
            summary = "列出全部本地账号",
            description = "仅管理员。不返回密码哈希。包含已停用的账号（enabled=false），管理页需要看到它们才能重新启用。")
    @GetMapping
    public List<LocalUserResponse> list() {
        requireAdmin();
        return service.list().stream().map(LocalUserResponse::from).toList();
    }

    @Operation(
            summary = "查询单个本地账号",
            description = "仅管理员。不返回密码哈希。")
    @GetMapping("/{id}")
    public LocalUserResponse get(
            @Parameter(description = "账号数字 id", required = true) @PathVariable long id) {
        requireAdmin();
        return LocalUserResponse.from(service.get(id));
    }

    @Operation(
            summary = "新建本地账号",
            description = """
                    仅管理员。username 是稳定标识，创建后不可改；password 至少 4 位。
                    admin=true 建成管理员。返回体不含密码。
                    若这是本部署唯一的账号，LocalAdminSeedRunner 已自动建过 admin，通常无需手动建。
                    """)
    @PostMapping
    public LocalUserResponse create(@Valid @RequestBody LocalUserRequest request) {
        requireAdmin();
        return LocalUserResponse.from(service.create(request));
    }

    @Operation(
            summary = "更新账号的显示名或管理员身份",
            description = """
                    仅管理员。请求体里的 username 与 password 会被忽略 ——
                    改密码走 PUT /api/users/{id}/password。
                    取消某人的管理员身份时，若他是最后一个可用管理员则 409。
                    不能撤销自己的管理员身份（409），请让另一个管理员操作。
                    """)
    @PutMapping("/{id}")
    public LocalUserResponse update(
            @Parameter(description = "账号数字 id", required = true) @PathVariable long id,
            @Valid @RequestBody LocalUserRequest request) {
        return LocalUserResponse.from(service.update(id, request, requireAdmin()));
    }

    @Operation(
            summary = "启用或停用账号",
            description = """
                    仅管理员。停用会立刻撤销该账号的全部刷新令牌，他当前的 access 最长再过 15 分钟失效。
                    停用后拒绝登录（登录返回 401，文案与密码错一致，不泄露账号状态）。
                    不能停用自己，也不能停用最后一个可用管理员（都是 409）。
                    """)
    @PutMapping("/{id}/status")
    public LocalUserResponse setStatus(
            @Parameter(description = "账号数字 id", required = true) @PathVariable long id,
            @RequestBody(required = false) LocalUserStatusRequest request) {
        int status = request == null ? 0 : request.statusOrDefault();
        return LocalUserResponse.from(service.setStatus(id, status, requireAdmin()));
    }

    @Operation(
            summary = "管理员重置某账号的密码",
            description = """
                    仅管理员，不需要该账号的旧密码。重置后立刻撤销他的全部刷新令牌 ——
                    这是「怀疑密码泄露」时的止血动作。
                    不提供「查看密码」：库里只有 BCrypt 哈希，谁都取不回明文。
                    """)
    @PutMapping("/{id}/password")
    public void resetPassword(
            @Parameter(description = "账号数字 id", required = true) @PathVariable long id,
            @Valid @RequestBody LocalUserPasswordRequest request) {
        requireAdmin();
        service.resetPassword(id, request.password());
    }

    @Operation(
            summary = "删除账号",
            description = """
                    仅管理员。物理删除，不可恢复；同时删掉他的刷新令牌。
                    不能删除自己，也不能删除最后一个可用管理员（都是 409）。
                    想保留审计线索的话应改用停用（PUT /{id}/status）。
                    """)
    @DeleteMapping("/{id}")
    public void delete(
            @Parameter(description = "账号数字 id", required = true) @PathVariable long id) {
        service.delete(id, requireAdmin());
    }

    // ------------------------------------------------------------------

    /**
     * 权限前置检查：必须是本地账号模式下的管理员。
     *
     * <p>standalone 与 multi 都明确拒绝，而不是「静默返回空列表」——
     * 静默返回空会让人以为「账号管理功能坏了」，而明确的 403 加文案能直接说明
     * 「这个模式下账号根本不归这里管」。
     */
    private LocalUserRow requireAdmin() {
        if (!current.accountsManageableLocally()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "账号管理仅在普通模式（standard）下提供；standalone 没有账号体系，"
                            + "multi 的账号由组织平台管理");
        }
        return current.requireAdmin();
    }
}
