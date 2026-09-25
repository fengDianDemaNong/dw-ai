package com.dwai.lineage.internal;

import com.dwai.lineage.conf.LineageProperties;
import com.dwai.lineage.service.TenantAdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 组织平台 → 本模块的<b>项目镜像</b>按需拉取。
 *
 * <h2>为什么从推送改成拉取</h2>
 *
 * <p>以前是组织在「建项目 / 改项目 / 改许可」时主动 PUT 到各模块（推送）。那要求组织
 * 知道<b>每个模块的后端地址</b>，于是要一张服务地址表、要心跳保活、要处理「模块没起来
 * 时推送失败怎么办」。而组织真正需要的只有模块的<b>页面地址</b>（门户集成要嵌它）。
 * 改成拉取后：组织只留一份配置，模块自己按需来取，模块重启/多实例/换地址都不用通知组织。
 *
 * <h2>两条触发路径（缺东西 vs 对账）</h2>
 *
 * <p>落点在 {@code TenantInterceptor}，{@code ensure(..., required)} 的 {@code required}
 * 就是分岔口，两条路的失败语义<b>刻意不同</b>：
 *
 * <ul>
 *   <li><b>required = true</b>（本地压根解析不出来）：同步拉。失败要外显 ——
 *       组织不可达就 {@link Unavailable} → 调用方 503。用户此时确实什么都看不到，
 *       把「问不到」和「答案是空」混起来只会让人去查错方向。</li>
 *   <li><b>required = false</b>（本地有，只是缓存过期）：<b>后台</b>补一次，请求不等它、
 *       也不因为它的失败而失败。这一条不能省、也不能改成同步：它是「组织改了项目名 /
 *       撤了许可，模块多久跟上」的唯一通道。曾经把它省掉（只在解析不出来时拉），
 *       结果是镜像一旦落地就<b>永不刷新</b> —— 正缓存也跟着变成半个死代码。
 *       反过来把它放回请求路径上同步做，就等于让每个请求都可能为一次对账等 4 秒，
 *       还可能在组织抖动时把本来能正常服务的请求打成 503。</li>
 * </ul>
 *
 * <p>所以这里三件事都要做对，缺一个就会在冷启动时把 Tomcat 线程占满或者让页面白屏：
 *
 * <ul>
 *   <li><b>正/负缓存</b>：命中就不再打组织。正缓存 {@value #POSITIVE_TTL_SECONDS} 秒
 *       （= 许可/改名传播的最大延迟），负缓存 {@value #NEGATIVE_TTL_SECONDS} 秒
 *       （= 「在组织建了项目到这边看得见」的最大延迟）。</li>
 *   <li><b>并发去重</b>：同一个 (租户, 项目) 只有一个请求真的去打组织，其余等它的结果。
 *       没有这一层，冷启动 20 个并发请求就是 20 次 HTTP，且 20 个线程全在等。</li>
 *   <li><b>短超时</b>：连接 1 秒、读取 3 秒。{@code OrgClient} 的 2s/5s 是给
 *       {@code authz/check} 那个门禁调用刻意设的，别混用 —— 这里的调用发生在
 *       「新用户第一次进页面」的路径上，5 秒就是 5 秒白屏。</li>
 * </ul>
 *
 * <h2>失败语义</h2>
 *
 * <p>几种结果分开处理，因为它们指向完全不同的排查方向：
 *
 * <ul>
 *   <li>组织说<b>没有这个项目</b>（404）→ 落短负缓存，让调用方照常走「编码未同步」的
 *       既有路径（400）。用户该做的是去组织里看看项目建了没有。</li>
 *   <li>组织<b>不可达</b>（连不上 / 超时 / 5xx）→ 同步路径抛 {@link Unavailable}
 *       （调用方回 503），并且<b>不落任何缓存</b>。反过来如果把网络故障也记成「项目不存在」，
 *       组织抖一下就会让一个正常项目在接下来半分钟里被钉死。</li>
 *   <li>拉到了 → 落库（复用既有的 {@code upsertFromOrg}），落正缓存。</li>
 *   <li><b>根本没配组织地址</b>（{@code ORG_BASE_URL} 为空）→ 什么都不做（不落缓存、
 *       不抛异常），让调用方照旧给出「编码未同步」，并在日志里留一行 warn。
 *       这<b>不是</b>「组织挂了」：那是一个配置缺失，不该放大成整个模块 503。</li>
 * </ul>
 *
 * <p>本类<b>不</b>负责把「解析不到」变成 400 —— 那是调用方原有的逻辑，本类也不负责
 * 判断「本地缺不缺」（那是调用方的 {@code needsPull}）。这里只做三件事：尽量把镜像补齐；
 * 有旧镜像时在后台跟组织对账；把「组织挂了」和「组织说没有」分开告诉调用方。
 */
@Component
public class OrgProjectPuller {

    private static final Logger log = LoggerFactory.getLogger(OrgProjectPuller.class);

    /** 正缓存时长：也是「组织里改了许可，模块多久跟上」的上界。 */
    static final int POSITIVE_TTL_SECONDS = 60;
    /** 负缓存时长：与当年心跳的 30 秒同量级，「刚建的项目多久能看见」。 */
    static final int NEGATIVE_TTL_SECONDS = 15;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    /** 后来者最多等同一个在飞的拉取多久。比 1s+3s 的客户端超时留一点余量。 */
    private static final long WAIT_MS = 5000;

    /** 后台对账的排队上限。满了就丢弃这次对账（见 {@link #refreshInBackground}）。 */
    private static final int MAX_REFRESH_QUEUE = 256;

    private final LineageProperties props;
    private final TenantAdminService tenants;

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Entry>> inFlight = new ConcurrentHashMap<>();

    /** 「没配组织地址」只提醒一次（见 {@link #orgConfigured()}），避免每请求一行 warn。 */
    private volatile boolean warnedNoOrg;

    /**
     * 后台对账用的单线程。队满就丢弃这次刷新（见 {@link #refreshInBackground}）——
     * 丢一次对账没有后果，把请求线程拖进来才有。
     */
    private final ExecutorService refresher = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_REFRESH_QUEUE),
            r -> {
                Thread t = new Thread(r, "org-project-refresh");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    public OrgProjectPuller(LineageProperties props, TenantAdminService tenants) {
        this.props = props;
        this.tenants = tenants;
    }

    /** 组织不可达。调用方据此回 503 —— 「问不到」而不是「答案是没有」。 */
    public static class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }
    }

    /**
     * 确保本地有 (tenantCode, projectCode) 这个镜像，本地缺东西时同步拉。
     *
     * @throws Unavailable 组织不可达（调用方回 503，而不是当成「项目不存在」）
     */
    public void ensure(String tenantCode, String projectCode) {
        ensure(tenantCode, projectCode, true);
    }

    /**
     * 确保本地有 (tenantCode, projectCode) 这个镜像。
     *
     * <p>只在 multi 下做事：standard/standalone 没有组织平面，租户是本地固定的。
     *
     * @param required {@code true} = 本地解析不出来，同步拉、失败外显（503）；
     *                 {@code false} = 本地有，只是缓存过期，后台对账、失败只记日志。
     *                 取舍见类注释「两条触发路径」。
     * @throws Unavailable 仅 {@code required=true} 时可能抛：组织不可达
     */
    public void ensure(String tenantCode, String projectCode, boolean required) {
        if (!props.isMulti()) return;
        String tenant = blankToNull(tenantCode);
        String project = blankToNull(projectCode);
        if (tenant == null || project == null) return;
        if (!orgConfigured()) return;

        String key = key(tenant, project);
        long now = System.currentTimeMillis();
        Entry cached = cache.get(key);
        if (cached != null && cached.expiresAt() > now) return;

        if (!required) {
            // 本地有旧镜像，只是到期了：后台对一次账，请求绝不因此变慢或失败。
            // 组织已经明确说过「没有这个项目」的 key 不再反复打扰 —— 纯数字的本地 id
            // 口径（本地主键，不是组织编码）问一次就会落到这个分支，于是只白问一次。
            if (cached != null && !cached.orgKnowsIt()) return;
            refreshInBackground(tenant, project, key);
            return;
        }

        CompletableFuture<Entry> mine = new CompletableFuture<>();
        CompletableFuture<Entry> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            awaitRunning(running);
            return;
        }
        try {
            Entry fresh = pull(tenant, project);
            // null = 这次没真的去拉（没配组织地址），别落缓存：
            // 缓存的是「问过了」这个事实，而这一次根本没问。
            if (fresh != null) cache.put(key, fresh);
            mine.complete(fresh);
        } catch (RuntimeException e) {
            // 失败不落缓存：只有组织「明确说没有」才写负缓存（见 pull 内部）
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    /**
     * 没配组织地址 = 压根没接组织平面，而不是「组织挂着」。此时两条路径都不该做事：
     * 同步路径让调用方照旧给出「编码未同步」，后台对账则直接不起 ——
     * 后者尤其重要：那是一次配置缺失，不该在<b>每个请求</b>上都留一行 warn、排一个空任务。
     *
     * <p>只警告一次：这是启动期就该发现的问题，刷屏只会淹掉别的日志。
     */
    private boolean orgConfigured() {
        if (blankToNull(props.getOrgBaseUrl()) != null) return true;
        if (!warnedNoOrg) {
            warnedNoOrg = true;
            log.warn("multi 模式下未配置组织平台地址（ORG_BASE_URL），"
                + "无法按需拉取项目镜像：本地没有的租户/项目会一直报「编码未同步」");
        }
        return false;
    }

    /**
     * 本地已有镜像，缓存到期：丢一次后台对账，请求线程不等它。
     *
     * <p>复用 {@link #inFlight} 做单飞：同一个 key 已经有拉取在飞时直接返回 —— 后台刷新的
     * 目的是「跟上组织的最新值」，不是「每次请求都刷一遍」，多刷一次没有任何收益，
     * 而组织抖动时那会变成一串重试。
     *
     * <p>失败（组织不可达）时落一个 {@value #NEGATIVE_TTL_SECONDS} 秒的退避，而不是不落 ——
     * 不落的话，每个带这个 key 的请求都会再起一次后台刷新，等于按请求频率打组织。
     */
    private void refreshInBackground(String tenant, String project, String key) {
        CompletableFuture<Entry> mine = new CompletableFuture<>();
        if (inFlight.putIfAbsent(key, mine) != null) return;
        try {
            refresher.execute(() -> {
                try {
                    Entry fresh = pull(tenant, project);
                    if (fresh != null) cache.put(key, fresh);
                    mine.complete(fresh);
                } catch (RuntimeException e) {
                    log.warn("后台刷新项目镜像失败（请求不受影响）, tenantCode={}, projectCode={}: {}",
                            tenant, project, e.getMessage());
                    // 退避：orgKnowsIt 保持 true，退避结束后还会再试；本地旧镜像继续用
                    cache.put(key, new Entry(System.currentTimeMillis()
                            + Duration.ofSeconds(NEGATIVE_TTL_SECONDS).toMillis(), true));
                    mine.completeExceptionally(e);
                } finally {
                    inFlight.remove(key, mine);
                }
            });
        } catch (RejectedExecutionException e) {
            // 队列满了：丢掉这次对账，但必须把占位摘掉，否则这个 key 会一直被别人当 leader 等
            inFlight.remove(key, mine);
        }
    }

    /** 等别人正在跑的那次拉取。它失败时同样要把失败传出去，不能静默当作成功。 */
    private void awaitRunning(CompletableFuture<Entry> running) {
        try {
            running.get(WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Unavailable u) throw u;
            throw new Unavailable("组织项目拉取失败: " + cause.getMessage());
        } catch (TimeoutException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new Unavailable("等待组织项目拉取超时");
        }
    }

    /**
     * @return 本次拉取的结论（在/不在），用于写缓存；<b>{@code null} = 没去拉</b>（不写缓存）
     */
    private Entry pull(String tenantCode, String projectCode) {
        String org = blankToNull(props.getOrgBaseUrl());
        if (org == null) {
            // 兜底：正常路径已经被 orgConfigured() 挡在前面了（那样才不会每个请求都留一行日志）。
            // 留这一手是为了「运行期把地址清掉」这种不该发生但真会有人干的事：回 null = 不落缓存。
            log.debug("未配置组织平台地址，跳过拉取, tenantCode={}, projectCode={}", tenantCode, projectCode);
            return null;
        }
        long now = System.currentTimeMillis();
        try {
            Map<?, ?> body = client(org)
                    .get()
                    .uri(uri -> uri.path("/internal/v1/projects/by-code/{code}")
                            .queryParam("tenantCode", tenantCode)
                            .build(projectCode))
                    .headers(h -> {
                        String tok = props.getModuleToken();
                        if (tok != null && !tok.isBlank()) h.set("X-Module-Token", tok);
                    })
                    .retrieve()
                    // 404 是「组织明确说没有」，与网络故障必须分开（见类注释）
                    .onStatus(s -> s.value() == 404, (req, res) -> {
                        throw new Absent();
                    })
                    .body(Map.class);

            String name = body == null ? null : string(body.get("name"));
            // 复用既有的落库路径：它就是当年为「组织推过来」写的，语义与「我来拉」逐字一致。
            // 走接口（Spring 代理）而不是实现类，否则 @Transactional 不生效。
            try {
                tenants.upsertFromOrg(projectCode, tenantCode, name, projectCode);
            } catch (RuntimeException e) {
                // 落库失败是**本模块**的毛病，组织那边是通的。消息里必须能看出来，
                // 否则调用方那句 503「组织平台不可用」会把人送到组织地址上去查。
                // （dw-model 的同名类在这条路径上真踩过：projects.owner 为 NULL。）
                throw new Unavailable("项目镜像落库失败（组织是通的）: " + e.getMessage());
            }
            log.info("从组织拉取项目镜像, tenantCode={}, projectCode={}", tenantCode, projectCode);
            return new Entry(now + Duration.ofSeconds(POSITIVE_TTL_SECONDS).toMillis(), true);
        } catch (Absent e) {
            log.info("组织侧没有这个项目, tenantCode={}, projectCode={}", tenantCode, projectCode);
            return new Entry(now + Duration.ofSeconds(NEGATIVE_TTL_SECONDS).toMillis(), false);
        } catch (Unavailable e) {
            throw e;
        } catch (Exception e) {
            throw new Unavailable("组织项目拉取不可用: " + e.getMessage());
        }
    }

    private RestClient client(String base) {
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(
                        ClientHttpRequestFactorySettings.DEFAULTS
                                .withConnectTimeout(CONNECT_TIMEOUT)
                                .withReadTimeout(READ_TIMEOUT)))
                .baseUrl(base.replaceAll("/$", ""))
                .build();
    }

    private static String key(String tenantCode, String projectCode) {
        return tenantCode + '\u0000' + projectCode;
    }

    private static String string(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    /**
     * 「这个 (租户, 项目) 刚问过组织，到期之前不必再问」。
     *
     * <p>不区分「在」与「不在」：拉到了就是本地有了，没拉到就让调用方照常走它的
     * 「编码未同步」路径 —— 两种情况下这里的动作都一样（别再问了）。差别在缓存时长。
     *
     * @param orgKnowsIt 组织认得这个 key。只有为 {@code true} 时才值得后台反复对账：
     *                   组织说「没有这个项目」的 key（含纯数字的本地 id 口径）再问也是白问。
     */
    private record Entry(long expiresAt, boolean orgKnowsIt) {
    }

    /** 组织明确回复「没有这个项目」。仅用于内部控制流，不外传。 */
    private static class Absent extends RuntimeException {
    }
}
