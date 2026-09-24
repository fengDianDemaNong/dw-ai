package support;

import com.dwai.lineage.auth.LocalAdminSeedRunner;
import com.dwai.lineage.auth.LocalJwtIssuer;
import com.dwai.lineage.persistence.LocalUserRepository;
import com.dwai.lineage.persistence.LocalUserRow;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 给测试里的<b>每个</b>请求默认挂上 standard 模式的 access 令牌。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>standard 补上账号体系之后（见 {@code SecurityConfig}），{@code /api/**} 一律要求令牌。
 * 仓库里那批<b>业务</b>测试（数据目录、远程元数据、统计、同步任务）写在没有认证的年代，
 * 它们关心的是业务语义，不是「谁在调」—— 于是每一条请求都该是「已登录的管理员发的」。
 * 让每个测试类各自去拼 {@code Authorization} 头，既啰嗦又容易漏。
 *
 * <h2>为什么用 defaultRequest 而不是逐条加头</h2>
 *
 * <p>{@code MockMvcBuilder.defaultRequest} 是「模板」：它的头会并入每个请求，
 * 而请求自己设的方法、路径、其它头都保留。测试里那几十处 {@code mvc.perform(...)}
 * 一个字都不用改。这比在 40 处调用点各加一行可靠 —— 后者总会漏掉几处，
 * 而漏掉的表现是 401，看起来像「功能坏了」。
 *
 * <h2>为什么要先手动跑一次种子</h2>
 *
 * <p>{@code LocalAdminSeedRunner} 是 {@code ApplicationRunner}，它要等上下文 refresh
 * <b>结束</b>才由 Spring Boot 调用；而 {@code MockMvc} 是在 refresh <b>期间</b>创建的单例 bean，
 * 构建它的定制器也在那时回调 —— 此时 {@code admin} 还没进库，直接查会 {@code orElseThrow}。
 * （这里踩过一次：以为定制器是懒回调，结果整个上下文起不来。）
 *
 * <p>所以先显式调一次 {@code seed.run(null)}。它是幂等的（只在 standard 且 users 表为空时插入），
 * 而且这就是<b>生产的那段代码</b>，不是在测试里另抄一份账号密码 —— 生产的种子换了，
 * 这里跟着换，不会悄悄脱钩。传 {@code null} 是安全的：{@code run} 不读 {@code args}。
 *
 * <h2>用在哪、不用在哪</h2>
 *
 * <p>只给「默认 standard + 纯业务」的测试类用。以下两类<b>不要</b>用它：
 *
 * <ul>
 *   <li>断言匿名被拒的测试（{@code RunModeSmokeTest}、{@code LocalAuthApiTest}）——
 *       带上默认令牌就把要测的 401 抹掉了。</li>
 *   <li>standalone / multi 的测试 —— 前者的 {@code /api/**} 本来就放行，
 *       后者认的是组织签发的令牌，本地令牌过不了世代校验。</li>
 * </ul>
 */
@TestConfiguration(proxyBeanMethods = false)
public class AuthenticatedMockMvc {

    /**
     * 种子里那个管理员。与 {@code LocalAdminSeedRunner} 保持一致 ——
     * 这里刻意走「查库 + 用真实签发器签」而不是手搓一个 JWT：
     * 手搓的令牌不带启动世代（{@code boot}）声明，会被 standard 的解码器判成过期，
     * 那正是 {@code support.DevJwt} 只适用于 multi 的原因。
     */
    private static final String SEED_ADMIN = "admin";

    @Bean
    MockMvcBuilderCustomizer authenticateEveryRequest(
            LocalJwtIssuer issuer, LocalUserRepository users, LocalAdminSeedRunner seed) {
        return builder -> builder.defaultRequest(get("/")
                .header("Authorization", "Bearer " + tokenFor(issuer, users, seed)));
    }

    private static String tokenFor(LocalJwtIssuer issuer, LocalUserRepository users, LocalAdminSeedRunner seed) {
        seed.run(null);
        LocalUserRow admin = users.findByUsername(SEED_ADMIN)
                .orElseThrow(() -> new IllegalStateException(
                        "库里没有 " + SEED_ADMIN + " —— LocalAdminSeedRunner 只在 standard"
                                + " 且 users 表为空时种它。本测试类要么跑的不是 standard，"
                                + "要么库不干净。"));
        return issuer.issue(admin);
    }
}
