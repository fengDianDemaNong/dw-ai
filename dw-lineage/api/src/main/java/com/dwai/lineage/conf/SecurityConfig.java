package com.dwai.lineage.conf;

import com.dwai.lineage.auth.LocalTokenEpoch;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * 三种运行模式下的身份认证。
 *
 * <h2>每个模式的门禁</h2>
 *
 * <pre>
 *   standalone → /api/** 全部放行
 *   standard   → /api/** 必须有有效的<b>本模块签发</b>的 JWT
 *   multi      → /api/** 必须有有效的<b>组织签发</b>的 JWT
 * </pre>
 *
 * <p>standalone 至今无认证是<b>设计如此</b>：它是「每个服务都能单独拿出来用」那条承诺的
 * 实现 —— 一个本地 SQL 血缘工具，不做任何配置就能起来用，不该先逼人建账号。
 * （要跑它得显式设 {@code LINEAGE_RUN_MODE=standalone}；本模块的默认模式是 standard。）
 *
 * <h2>standard 为什么现在真的有认证了</h2>
 *
 * <p>此前这里 {@code isStandard()} 也走 {@code permitAll}，于是 standard 与 standalone
 * 行为<b>完全等价</b>：一个被标成「普通模式 / 有账号体系」的部署，实际上是裸奔的。
 * 这轮按技术方案 §3.3（{@code Authorization = 本模块 JWT}）与 §4（本地用户 + 本库角色）
 * 把 standard 的账号体系补上了：{@code /api/auth/*} 登录、users 表、BCrypt、refresh 落库。
 * 现在 standard 下的 {@code /api/**} 与 multi 一样要求令牌。
 *
 * <h2>两条路复用什么、不复用什么</h2>
 *
 * <p>共用同一个 {@link #jwtDecoder}（HS256 共享密钥，所以 standard 自签与组织签发
 * 可以共用一个解码器），但<b>世代校验只对 standard 生效</b> ——
 * 详见该方法上的说明。这是两种模式唯一的行为分歧，也是这个类里最容易搞错的一处。
 *
 * <h2>这解决了什么、没解决什么</h2>
 *
 * <p>解决：<b>匿名访问</b>。standard / multi 下没有有效 JWT 连 {@code /api/**} 都进不来。
 *
 * <p>没解决：<b>租户授权</b>。组织签发的 JWT 里没有租户声明（只有 sub / username /
 * name / platform_admin / boot），本地签发的也刻意不带（见 LocalJwtIssuer），
 * 所以令牌回答的是「你是谁」，不是「你能碰哪个租户」。<b>multi</b> 下，一个 alpha
 * 租户的合法用户仍然可以带 beta 租户的 {@code X-Tenant-Code} 读到 beta 的数据 ——
 * 要关掉它必须再向组织问一次 {@code authz/check}（见 ADR-0011 的后续项）。
 *
 * <p>standard 不受这条影响：{@link com.dwai.lineage.tenant.TenantInterceptor}
 * 对 standard 直接忽略租户头，请求恒定落在默认租户上。也就是说 standard 的单租户
 * 语义是拦截器给的，<b>不是</b>这里给的 —— 两者别混。
 */
@Configuration
public class SecurityConfig {

    /**
     * 无需身份的路径。
     *
     * <p>{@code /api/runtime} 是运行模式探测：不知道模式就不知道该不该带 JWT，
     * 要求它认证是循环依赖 —— 前端也正是用裸 fetch 调它来发现模式的。
     *
     * <p>{@code /api/auth/config|login|refresh|logout} 同理属于「登录流程本身」：
     * 登录不能要求先登录。这几个路径在 standalone 下会回 403 说明「本模式没有登录」，
     * 在 multi 下同样 403「登录由组织提供」—— 那两种模式下的 403 由 controller 给出，
     * 不是靠放行再拒绝，所以这里统一 permitAll 是安全的。
     *
     * <p>⚠️ {@code /api/auth/me|profile|password} <b>故意不在</b>这个列表里：
     * 它们要读当前身份，必须已登录。少写一条就是「匿名能改密码」。
     */
    private static final String[] PUBLIC_PATHS = {
            "/api/runtime",
            "/api/v1/runtime",
            // 服务自描述（角色与菜单清单），给组织工作台用。壳是在**拿到用户身份之前**
            // 来问它的 —— 要求认证就成循环依赖。与 dw-org / dw-model 的同名端点同口径。
            // 它只描述「本服务有哪些入口」，不含任何租户数据，放行不等于泄露。
            "/api/manifest",
            "/api/v1/manifest",
            "/api/auth/config",
            "/api/v1/auth/config",
            "/api/auth/login",
            "/api/v1/auth/login",
            "/api/auth/refresh",
            "/api/v1/auth/refresh",
            "/api/auth/logout",
            "/api/v1/auth/logout",
            "/actuator/health",
            "/actuator/info",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/internal/v1/**"
    };

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, LineageProperties props) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> {
                    reg.requestMatchers(PUBLIC_PATHS).permitAll()
                            // 预检请求不带 Authorization，放行交给 MVC 的 CORS 处理
                            .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                    if (props.isStandalone()) {
                        reg.anyRequest().permitAll();
                    } else {
                        // standard 与 multi 在这一层的差别为零 —— 差别在令牌由谁签发，
                        // 那由 decoder 的世代校验与 controller 的守卫决定，不在路径匹配里。
                        reg.requestMatchers("/api/**").authenticated()
                                .anyRequest().permitAll();
                    }
                })
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));
        return http.build();
    }

    /**
     * BCrypt。standard 模式的账号密码用它。
     *
     * <p>放在这个类里而不是另开一个 {@code PasswordConfig}：它唯一的消费者就是本地认证，
     * 而本地认证的行为开关（模式判定）也在这里，两处分开放反而容易看漏关联。
     *
     * <p>为什么是 BCrypt 而不是 {@code DelegatingPasswordEncoder}：这里只有本地账号一种
     * 凭据来源，用不上「同一库里混着 bcrypt / argon2 / pbkdf2」的多态前缀能力，
     * 那层间接只会让「存的到底是什么格式」变得不直观。
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * HS256 共享密钥解码器，**只在 standard 下校验启动世代**。
     *
     * <p>为什么两种模式能共用：{@code jwtSecret} 是三个服务共用的一组环境变量
     * （见 {@link LineageProperties.Security} 的说明），所以「本模块自签」与
     * 「组织签发」的令牌是同一个算法、同一把密钥 —— 一个解码器就够。
     *
     * <p>为什么世代校验必须区分模式：
     *
     * <ul>
     *   <li><b>standard</b>：令牌是本进程签发的，{@code boot} 就是本进程这次启动的世代。
     *       重启后新旧世代不一致 → 旧 access 整体失效。这是想要的：重启意味着
     *       「可能换过密钥、可能修过 bug」，让所有人重新登录是安全的默认。</li>
     *   <li><b>multi</b>：令牌是<b>组织</b>签发的，里面的 {@code boot} 属于组织那次启动，
     *       与本进程无关。拿本进程的世代去比，只会把<b>所有</b>合法令牌判为过期 ——
     *       等于数据地图在 multi 下彻底登不进去。所以这里必须跳过。</li>
     * </ul>
     *
     * <p>standalone 下这个解码器不会被用到（{@code /api/**} 全放行，没人解析令牌），
     * 但按 standard 的规则构造是无害的。
     */
    @Bean
    JwtDecoder jwtDecoder(LineageProperties props, LocalTokenEpoch epoch) {
        LineageProperties.Security sec = props.getSecurity();
        if (sec.isOidc() && sec.getCasdoor().configured()) {
            // Casdoor 默认 RS256；启动时不拉 metadata，避免没装 Casdoor 就起不来
            return NimbusJwtDecoder.withJwkSetUri(sec.getCasdoor().resolvedJwkSetUri())
                    .jwsAlgorithm(SignatureAlgorithm.RS256)
                    .build();
        }
        SecretKeySpec key = new SecretKeySpec(
                sec.getJwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        JwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256).build();
        if (!props.isStandard()) {
            return decoder;
        }
        return token -> {
            Jwt jwt = decoder.decode(token);
            String boot = jwt.getClaimAsString(LocalTokenEpoch.CLAIM);
            if (boot == null || !boot.equals(epoch.id())) {
                // 文案与 dw-model 的 SecurityConfig 一致，前端按同一套字符串处理
                throw new BadJwtException("session expired");
            }
            return jwt;
        };
    }
}
