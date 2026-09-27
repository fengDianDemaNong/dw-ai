package com.dwai.platform.meta;

import com.dwai.platform.auth.LlmCrypto;
import com.dwai.platform.meta.dto.ApiModels;
import com.dwai.platform.meta.entity.TenantComputeEntity;
import com.dwai.platform.meta.entity.TenantEntity;
import com.dwai.platform.meta.mapper.TenantComputeMapper;
import com.dwai.platform.meta.support.AiCaps;
import com.dwai.platform.meta.support.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作台「计算资源」页：本组织自己的调度集群与数仓引擎。
 *
 * <h2>这是租户的基础设施，不是平台服务</h2>
 *
 * <p>{@code service_registry} 登记的是<b>平台自己的产品进程</b>（建模 / 元数据…），由平台
 * 用户维护；这里登记的是<b>这个租户自己的</b> DolphinScheduler 与引擎，由租户管理员维护，
 * 只对本报组织生效。两件事互不相干，别混。
 *
 * <p><b>登记 ≠ 接管</b>：本版只做「存下来 + 能测通」（用户裁定）。dw-model 的调度链路
 * 走的是它自己的进程级 {@code DwaiProperties.DolphinScheduler}，<b>不读这张表</b> ——
 * 把它接过去是另一件事，别顺手做。
 *
 * <h2>Token 的处置</h2>
 *
 * <p>加密落库（复用 {@link LlmCrypto}，与 LLM Key 同一把密钥 —— 类名带 {@code Llm} 是历史
 * 原因，它是应用级的对称加密），任何回传路径只给 {@code hasToken} 布尔。请求与响应是两个
 * 不同的 record（{@link ApiModels.ComputePutReq} / {@link ApiModels.ComputeDto}）：共用
 * 一个的话，某天有人顺手把响应接上请求，掩码串就会被当成新 Token 存回去，而症状是
 * 「保存后测试连接一直失败」，页面上看不出任何异常。
 *
 * <h2>引擎</h2>
 *
 * <p>只有启停，<b>没有连接信息</b>（用户裁定 3），所以状态是纯占位、不落库 —— 没有可验证的
 * 连接就不该说「已接通」，那是假话。
 */
@Service
public class ComputeService {

  /**
   * 引擎的展示顺序。
   *
   * <p>合法性与 {@link AiCaps#ENGINES} <b>同一个集合</b>（写入时按它校验）；这里只是给表格
   * 定一个稳定顺序 —— {@code Set.of} 的迭代顺序不作保证，而四行每次都该待在自己的位置上。
   */
  private static final List<String> ENGINE_KINDS = List.of("hive", "spark", "clickhouse", "doris");

  private static final String UNCONFIGURED = "unconfigured";
  private static final String OK = "ok";
  private static final String ERROR = "error";

  /** 探活的超时。比 {@code MenuCandidateService} 短：这是管理员点按钮后**等着**的一次请求。 */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(8);

  private static final ObjectMapper JSON = new ObjectMapper();

  private final TenantComputeMapper computes;
  private final AccessService access;
  private final LlmCrypto crypto;
  private final RestClient http;

  public ComputeService(TenantComputeMapper computes, AccessService access, LlmCrypto crypto) {
    this.computes = computes;
    this.access = access;
    this.crypto = crypto;
    // 不设 baseUrl：探活地址是租户级的、运行期才知道，每次请求用绝对 URI。
    // 超时必须显式设 —— RestClient 的默认值来自底层的 JDK HttpClient，不设等于「等到底」，
    // 而这一页是管理员点了按钮在等结果的。
    this.http = RestClient.builder()
        .requestFactory(ClientHttpRequestFactories.get(
            ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(CONNECT_TIMEOUT)
                .withReadTimeout(READ_TIMEOUT)))
        .build();
  }

  /** 读计算资源。没有行 = 全默认（未启用、无 Token、四个引擎全关）。 */
  public ApiModels.ComputeDto get(String tenantId) {
    requireTenant(tenantId);
    access.requireTenantAdmin();
    return toDto(computes.selectById(tenantId));
  }

  /**
   * 存调度配置与引擎启停。
   *
   * <p>{@code schedulerToken} 为空 = <b>保持原值</b>（照 {@code TenantAdminService.putLlm} 的
   * 模式）：页面上那格永远显示不出明文，用户不改它就不该被清掉。
   *
   * <p>改了地址或换了 Token 都会把 {@code status} 打回 {@code unconfigured} —— 上一次「已测通」
   * 是<b>对旧地址、旧 Token</b>的结论，留着它就会出现「绿标对着一个从没测过的地址」。
   */
  @Transactional
  public ApiModels.ComputeDto put(String tenantId, ApiModels.ComputePutReq body) {
    requireTenant(tenantId);
    access.requireTenantAdmin();
    TenantComputeEntity e = computes.selectById(tenantId);
    boolean insert = e == null;
    if (insert) {
      e = new TenantComputeEntity();
      e.setTenantId(tenantId);
      e.setSchedulerEnabled(false);
      e.setSchedulerBaseUrl("");
      e.setSchedulerStatus(UNCONFIGURED);
      e.setSchedulerNote("");
    }
    if (body != null) {
      if (body.schedulerEnabled() != null) e.setSchedulerEnabled(body.schedulerEnabled());
      if (body.schedulerBaseUrl() != null) {
        String next = body.schedulerBaseUrl().trim();
        if (!next.equals(nz(e.getSchedulerBaseUrl()))) {
          e.setSchedulerBaseUrl(next);
          resetTest(e);
        }
      }
      if (!blank(body.schedulerToken())) {
        e.setSchedulerTokenEnc(crypto.encrypt(body.schedulerToken()));
        resetTest(e);
      }
      if (body.engines() != null) e.setEngines(Jsons.toJson(cleanEngines(body.engines())));
    }
    e.setUpdatedAt(OffsetDateTime.now());
    if (insert) computes.insert(e);
    else computes.updateById(e);
    return toDto(e);
  }

  /**
   * 用<b>存着的</b>配置探一次活，把结论写回。
   *
   * <p>不接受请求体里的临时地址/token：那样会出现「测通了但没保存」的分叉状态，而用户看到的
   * 结论与库里存的东西对不上。要测新地址，先保存。
   *
   * <p><b>失败不抛 5xx</b>：这是管理员的一次点击，连不上是<b>预期结果之一</b>（地址填错、
   * Token 过期、网段不通都要显示成结论）。抛 5xx 会被前端当成平台坏了，还会把真实的
   * 错误文本丢在日志里而不是页面上。
   */
  @Transactional
  public ApiModels.ComputeDto test(String tenantId) {
    requireTenant(tenantId);
    access.requireTenantAdmin();
    TenantComputeEntity e = computes.selectById(tenantId);
    if (e == null || nz(e.getSchedulerBaseUrl()).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先填写并保存调度地址");
    }
    String baseUrl = nz(e.getSchedulerBaseUrl());
    String token = crypto.decrypt(e.getSchedulerTokenEnc());
    String failure = probe(baseUrl, token);
    e.setSchedulerStatus(failure == null ? OK : ERROR);
    e.setSchedulerNote(failure == null ? "连接正常：" + probeUrl(baseUrl) : failure);
    e.setSchedulerTestedAt(OffsetDateTime.now());
    e.setUpdatedAt(OffsetDateTime.now());
    computes.updateById(e);
    return toDto(e);
  }

  /**
   * 发一次请求，返回 {@code null} = 通了，否则返回给用户看的失败原因。
   *
   * <p>请求地址与 {@code MenuCandidateService} 同一个理由回显给用户：<b>地址填错是最常见的
   * 一种失败</b>，而「连接失败」这四个字对排查毫无帮助 —— 拼出来的完整 URL 是唯一能一眼
   * 对上「我填的地址是不是少了 /dolphinscheduler」的东西。
   *
   * <p>这是管理员受信动作（门槛 {@code requireTenantAdmin}），地址由他自填 —— 与平台
   * 「服务注册」登记地址同级，不额外加白名单：半吊子的白名单只会挡掉合法的内网域名。
   */
  private String probe(String baseUrl, String token) {
    String url = probeUrl(baseUrl);
    try {
      String body = http.get()
          .uri(URI.create(url))
          .header("token", token == null ? "" : token)
          .retrieve()
          .body(String.class);
      return judge(body);
    } catch (HttpClientErrorException | HttpServerErrorException ex) {
      // 连得上但被拒（401/403 最常见 = Token 不对；404 = 地址少了 /dolphinscheduler）
      return "服务返回 HTTP " + ex.getStatusCode().value() + "：" + url;
    } catch (ResourceAccessException ex) {
      return "连不上 " + url + "（" + nz(ex.getMessage()) + "）";
    } catch (Exception ex) {
      return "连接失败：" + ex.getClass().getSimpleName() + " " + nz(ex.getMessage());
    }
  }

  /** 探活的实际请求地址；末尾斜杠要归一，否则会拼出 {@code //projects}。 */
  private static String probeUrl(String baseUrl) {
    String base = nz(baseUrl);
    while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
    return base + "/projects?pageNo=1&pageSize=1";
  }

  /**
   * HTTP 2xx 之后的第二道判据。
   *
   * <p>只看状态码不够：DolphinScheduler 鉴权失败时回的是 <b>HTTP 200 + 业务码非 0</b>
   * （{@code {"code":10001,"msg":"user login failure"}}），只看 2xx 会把「Token 不对」
   * 报成「连接正常」—— 而这恰恰是这个按钮最该抓出来的一种错。
   */
  private static String judge(String body) {
    if (body == null || body.isBlank()) return null; // 2xx 且空体：当它通了
    try {
      JsonNode n = JSON.readTree(body);
      if (n != null && n.has("code")) {
        int code = n.path("code").asInt(-1);
        if (code != 0) {
          String msg = n.path("msg").asText("");
          return "调度器返回业务码 " + code + (msg.isEmpty() ? "" : "：" + msg);
        }
      }
      return null;
    } catch (Exception ex) {
      // 2xx 但响应体不是 JSON（被网关 / 登录页截胡）：这**不是**一个可用的 DS 地址，
      // 当成通过会让人以为配好了。报文只取前 80 字，免得把一整页 HTML 灌进 note 列。
      String head = body.trim().substring(0, Math.min(80, body.trim().length()));
      return "服务有响应但不是调度器的应答（可能被网关或登录页截胡）：" + head;
    }
  }

  private static void resetTest(TenantComputeEntity e) {
    e.setSchedulerStatus(UNCONFIGURED);
    e.setSchedulerTestedAt(null);
    e.setSchedulerNote("");
  }

  /** 收下引擎启停，丢掉不认识的 kind（写入时归一，读侧就不必再兜）。 */
  private static List<ApiModels.ComputeEngineDto> cleanEngines(List<ApiModels.ComputeEngineDto> raw) {
    Map<String, Boolean> on = new HashMap<>();
    Set<String> seen = new HashSet<>();
    for (ApiModels.ComputeEngineDto item : raw) {
      if (item == null) continue;
      String kind = nz(item.kind());
      if (!AiCaps.ENGINES.contains(kind) || !seen.add(kind)) continue;
      on.put(kind, item.enabled());
    }
    List<ApiModels.ComputeEngineDto> out = new ArrayList<>(ENGINE_KINDS.size());
    for (String kind : ENGINE_KINDS) {
      out.add(new ApiModels.ComputeEngineDto(kind, on.getOrDefault(kind, false)));
    }
    return out;
  }

  /** 库里那串 JSON → 四行引擎（缺行 = 未启用；这是「不种种子行」的代价，也只在这一处）。 */
  private static List<ApiModels.ComputeEngineDto> enginesOf(String raw) {
    Map<String, Boolean> on = new HashMap<>();
    for (Map<String, Object> item : Jsons.maps(raw)) {
      String kind = nz(item.get("kind") == null ? null : String.valueOf(item.get("kind")));
      if (!ENGINE_KINDS.contains(kind)) continue;
      on.put(kind, !Boolean.FALSE.equals(item.get("enabled")));
    }
    List<ApiModels.ComputeEngineDto> out = new ArrayList<>(ENGINE_KINDS.size());
    for (String kind : ENGINE_KINDS) {
      out.add(new ApiModels.ComputeEngineDto(kind, on.getOrDefault(kind, false)));
    }
    return out;
  }

  private static ApiModels.ComputeDto toDto(TenantComputeEntity e) {
    if (e == null) {
      return new ApiModels.ComputeDto(false, "", false, UNCONFIGURED, "", "", enginesOf(null));
    }
    String status = nz(e.getSchedulerStatus());
    return new ApiModels.ComputeDto(
        Boolean.TRUE.equals(e.getSchedulerEnabled()),
        nz(e.getSchedulerBaseUrl()),
        e.getSchedulerTokenEnc() != null && e.getSchedulerTokenEnc().length > 0,
        status.isEmpty() ? UNCONFIGURED : status,
        e.getSchedulerTestedAt() == null ? "" : e.getSchedulerTestedAt().toString(),
        nz(e.getSchedulerNote()),
        enginesOf(e.getEngines()));
  }

  private void requireTenant(String tenantId) {
    TenantEntity t = access.requireTenant();
    if (!t.getId().equals(tenantId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "组织不匹配");
    }
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static String nz(String s) {
    return s == null ? "" : s.trim();
  }
}
