package com.dwai.platform.internal;

import com.dwai.platform.DwaiProperties;
import com.dwai.platform.meta.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
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
 * 组织平台 → 本模块的<b>项目镜像 + 许可</b>按需拉取。
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
 * <p>落点在 {@code auth/TenantFilter}，{@code ensure(..., required)} 的 {@code required}
 * 就是分岔口，两条路的失败语义<b>刻意不同</b>：
 *
 * <ul>
 *   <li><b>required = true</b>（本地压根解析不出来）：同步拉。失败要外显 ——
 *       组织不可达就 {@link Unavailable} → 调用方 503。</li>
 *   <li><b>required = false</b>（本地有，只是缓存过期）：<b>后台</b>补一次，请求不等它、
 *       也不因为它的失败而失败。这一条不能省、也不能改成同步：它是「组织改了项目名 /
 *       撤了许可，模块多久跟上」的唯一通道（本模块的许可行就是靠这条路径校准的）。
 *       曾经把它省掉（只在解析不出来时拉），结果是镜像一旦落地就<b>永不刷新</b>。</li>
 * </ul>
 *
 * <p>同步那条路径上，三件事都要做对，缺一个就会在冷启动时把 Tomcat 线程占满或者让页面白屏：
 *
 * <ul>
 *   <li><b>正/负缓存</b>：命中就不再打组织。正缓存 {@value #POSITIVE_TTL_SECONDS} 秒
 *       —— 它同时是「组织改了许可，模块多久跟上」的上界，所以别省；负缓存
 *       {@value #NEGATIVE_TTL_SECONDS} 秒 = 「在组织建了项目到这边看得见」的最大延迟。</li>
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
 *   <li>组织说<b>没有这个项目</b>（404）→ 落短负缓存，让调用方照常走既有的
 *       「编码未同步 / 租户不存在」路径。用户该做的是去组织里看看项目建了没有。</li>
 *   <li>组织<b>不可达</b>（连不上 / 超时 / 5xx）→ 抛 {@link Unavailable}（调用方回 503），
 *       并且<b>不落任何缓存</b>。反过来如果把网络故障也记成「项目不存在」，组织抖一下
 *       就会让一个正常项目在接下来半分钟里被钉死。</li>
 *   <li><b>根本没配组织地址</b>（{@code DWAI_ORG_BASE_URL} 为空）→ 什么都不做（不落缓存、
 *       不抛异常），留一行 warn。这<b>不是</b>「组织挂了」：一个配置缺失不该放大成
 *       整个模块 503（{@code RunModeSmokeTest} 的 multi 组就没配这个值，
 *       且本地已有的租户/项目本来就不该因为组织地址没配而被拒绝）。</li>
 *   <li>拉到了 → 复用既有的 {@code ProjectService.upsertInternal}（它就是当年为
 *       「组织推过来」写的：建租户、校许可、幂等短路一整套都在里面）。</li>
 * </ul>
 *
 * <p>本类不负责判断「本地缺不缺」（那是调用方的 {@code needsPull}），也不负责把
 * 「解析不到」变成 403。它只做三件事：尽量把镜像补齐；本地有旧镜像时在后台跟组织对账；
 * 把「组织挂了」和「组织说没有」分开告诉调用方。
 *
 * <h2>与 dw-lineage 的那份是同一形态</h2>
 *
 * <p>两个模块各有一份（不共享代码：三个服务刻意不互相依赖）。改这里时那边通常也要改 ——
 * 差异只有两处：那边没有本地许可（拉回来只需落项目），以及租户名的处理。
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

  private final DwaiProperties props;
  private final ProjectService projects;

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

  public OrgProjectPuller(DwaiProperties props, ProjectService projects) {
    this.props = props;
    this.projects = projects;
  }

  /** 组织不可达。调用方据此回 503 —— 「问不到」而不是「答案是没有」。 */
  public static class Unavailable extends RuntimeException {
    public Unavailable(String message) {
      super(message);
    }
  }

  /**
   * 确保本地有 (tenantCode, projectCode) 这个镜像，并用组织的许可校准本地行；本地缺东西时同步拉。
   *
   * @throws Unavailable 组织不可达（调用方回 503，而不是当成「项目不存在」）
   */
  public void ensure(String tenantCode, String projectCode) {
    ensure(tenantCode, projectCode, true);
  }

  /**
   * 确保本地有 (tenantCode, projectCode) 这个镜像，并用组织的许可校准本地行。
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
      log.warn("multi 模式下未配置组织平台地址（DWAI_ORG_BASE_URL），"
          + "无法按需拉取项目镜像：本地没有的租户/项目会一直报「编码未同步」");
    }
    return false;
  }

  /**
   * 本地已有镜像，缓存到期：丢一次后台对账，请求线程不等它。
   *
   * <p>复用 {@link #inFlight} 做单飞：同一个 key 已经有拉取在飞时直接返回 —— 后台刷新的
   * 目的是「跟上组织的最新值」（项目名、许可），不是「每次请求都刷一遍」。
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
  @SuppressWarnings("unchecked")
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
      Map<String, Object> body = client(org)
          .get()
          .uri(uri -> uri.path("/internal/v1/projects/by-code/{code}")
              .queryParam("tenantCode", tenantCode)
              .build(projectCode))
          .headers(this::token)
          .retrieve()
          // 404 是「组织明确说没有」，与网络故障必须分开（见类注释）
          .onStatus(s -> s.value() == 404, (req, res) -> {
            throw new Absent();
          })
          .body(Map.class);

      if (body == null) {
        throw new Unavailable("组织未返回项目");
      }
      // 复用既有的落库路径（建租户 + 校许可 + 幂等短路都在里面）。
      // projects 是注入的 Spring bean（CGLIB 代理），@Transactional 才生效 ——
      // 直接 new 一个 ProjectService 调它，事务注解会被静默跳过。
      try {
        projects.upsertInternal(
            projectCode,
            string(body.get("name")),
            tenantCode,
            string(body.get("id")),
            string(body.get("tenantName")),
            strings(body.get("modules")),
            strings(body.get("aiCaps")));
      } catch (RuntimeException e) {
        // 落库失败是**本模块**的毛病（约束、字段为空、SQL 写错），组织那边是通的。
        // 消息里必须能看出来，否则调用方那句 503「组织平台不可用」会把人送到组织地址上
        // 去查一个根本不存在的问题 —— 这条路径真踩过（projects.owner 为 NULL）。
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

  private void token(org.springframework.http.HttpHeaders h) {
    String tok = props.getSecurity().getModuleToken();
    if (tok != null && !tok.isBlank()) h.set("X-Module-Token", tok);
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

  /**
   * 取出一个字符串数组字段。
   *
   * <p>{@code null} 必须原样保留：它表示「组织没有这项信息，别动本地」，
   * 与「组织说一项都没开通」（空数组）在 {@code syncLicense} 里走的是不同分支。
   * 把 null 变成空数组，等于让一次网络异常把租户的许可抹空。
   */
  private static List<String> strings(Object v) {
    if (!(v instanceof List<?> list)) return null;
    return list.stream().map(String::valueOf).toList();
  }

  private static String string(Object v) {
    return v == null ? null : String.valueOf(v);
  }

  private static String key(String tenantCode, String projectCode) {
    return tenantCode + '\u0000' + projectCode;
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
