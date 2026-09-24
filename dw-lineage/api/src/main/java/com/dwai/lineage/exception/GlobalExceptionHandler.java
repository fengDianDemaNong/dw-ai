package com.dwai.lineage.exception;

import jakarta.servlet.http.HttpServletRequest;
import com.dwai.lineage.dto.ErrorResponse;
import com.dwai.lineage.service.metadata.dbx.DbxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.UUID;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** SQL 解析类异常属于用户输入问题，消息可以直接回显。 */
    @ExceptionHandler(SqlParseException.class)
    public ResponseEntity<ErrorResponse> handleSqlParseException(SqlParseException e, HttpServletRequest request) {
        String traceId = newTraceId();
        logger.warn("[{}] SQL 解析失败: {}", traceId, e.getMessage(), e);
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(e.getMessage(), traceId, request.getRequestURI()));
    }

    /**
     * 配置问题：原样透传消息。
     *
     * <p>这类异常的 message 本身就是给运维看的操作指引（例如「请设置 METADATA_SECRET_KEY」），
     * 落到兜底会被替换成「服务内部错误，请联系管理员」，等于把唯一有用的信息丢掉。
     *
     * <p>用 503 而不是 500：请求本身没错，是服务缺配置，改完配置重试即可。
     */
    @ExceptionHandler(MetadataConfigException.class)
    public ResponseEntity<ErrorResponse> handleMetadataConfig(MetadataConfigException e,
                                                              HttpServletRequest request) {
        String traceId = newTraceId();
        logger.warn("[{}] 元数据服务配置问题: {}", traceId, e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse(e.getMessage(), traceId, request.getRequestURI()));
    }

    /**
     * 上游元数据服务（dbx）返回了错误。
     *
     * <p>用 502 而不是 500：请求本身没问题，是我们依赖的外部服务出错了。
     * 消息原样透传 —— 它是 dbx 错误体里的 detail，
     * 实践中基本都是「连接 id 写错了」「密码不对」这类用户能自己改的问题，
     * 落到兜底变成「服务内部错误，请联系管理员」等于把唯一有用的线索丢掉。
     *
     * <p>{@code DbxException} 的消息构造时已保证不含凭据。
     */
    @ExceptionHandler(DbxException.class)
    public ResponseEntity<ErrorResponse> handleDbx(DbxException e, HttpServletRequest request) {
        String traceId = newTraceId();
        logger.warn("[{}] dbx 调用失败: {}", traceId, e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse(e.getMessage(), traceId, request.getRequestURI()));
    }

    /**
     * 目标仍被数据引用，拒绝删除。
     *
     * <p>用 409 而不是 400：请求本身合法，是当前状态不允许。消息里带着行数与替代方案
     * （改用停用），原样透传给用户。
     */
    @ExceptionHandler(ResourceInUseException.class)
    public ResponseEntity<ErrorResponse> handleResourceInUse(ResourceInUseException e,
                                                             HttpServletRequest request) {
        String traceId = newTraceId();
        logger.warn("[{}] 目标仍被引用，拒绝删除: {}", traceId, e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(e.getMessage(), traceId, request.getRequestURI()));
    }

    /**
     * Spring 把仓储层抛的 {@link IllegalArgumentException} 包装过一层。
     *
     * <p>{@code @Repository} 类会被持久化异常翻译切面拦截，方法内抛出的
     * {@code IllegalArgumentException} 会被包成 {@code InvalidDataAccessApiUsageException}，
     * 于是绕开了下面那个 400 处理器，落到兜底变成 500 ——
     * 「血缘版本不存在: 17」这种明确的用户输入问题，会显示成
     * 「服务内部错误，请联系管理员」。这里把它拆回来。
     *
     * <p>只在 cause 确实是 IllegalArgumentException 时降级为 400；
     * 其余数据访问异常（连不上库、约束冲突等）仍然是真故障，交给兜底。
     */
    @ExceptionHandler(InvalidDataAccessApiUsageException.class)
    public ResponseEntity<ErrorResponse> handleInvalidDataAccessUsage(
            InvalidDataAccessApiUsageException e, HttpServletRequest request) {
        if (e.getCause() instanceof IllegalArgumentException cause) {
            return handleIllegalArgument(cause, request);
        }
        return handleGenericException(e, request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e, HttpServletRequest request) {
        String traceId = newTraceId();
        logger.warn("[{}] 请求参数非法: {}", traceId, e.getMessage(), e);
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(e.getMessage(), traceId, request.getRequestURI()));
    }

    /**
     * 必填的查询参数没传。
     *
     * <p>不接的话会落到兜底变成 500 +「服务内部错误，请联系管理员」——
     * 而实际上只是少传了一个参数，那句话让人完全不知道该改什么。
     * Spring 对 {@code @Valid @RequestBody} 的校验失败有单独处理，
     * 但 {@code @RequestParam} 这一路一直漏在外面，所有带必填查询参数的接口都受影响。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException e,
                                                            HttpServletRequest request) {
        String traceId = newTraceId();
        logger.warn("[{}] 缺少必填参数: {}", traceId, e.getParameterName());
        return ResponseEntity.badRequest().body(new ErrorResponse(
                "缺少必填参数 " + e.getParameterName(), traceId, request.getRequestURI()));
    }

    /**
     * 参数类型对不上，比如 {@code ?sourceId=abc} 而签名是 long。
     *
     * <p>同上，不接就是 500。这里<b>不回吐期望的类型名</b>（{@code java.lang.Long} 之类）——
     * 对用户没有意义，说清楚是哪个参数不对就够了。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                            HttpServletRequest request) {
        String traceId = newTraceId();
        logger.warn("[{}] 参数类型不对: {}={}", traceId, e.getName(), e.getValue());
        return ResponseEntity.badRequest().body(new ErrorResponse(
                "参数 " + e.getName() + " 的取值不合法: " + e.getValue(), traceId,
                request.getRequestURI()));
    }

    /**
     * 唯一约束冲突是用户输入问题，不是服务故障。
     *
     * <p>不走兜底：兜底会返回 500 + 「服务内部错误，请联系管理员」，而用户实际只是
     * 把元数据服务的名称取重了，看到那句话完全不知道该改什么。
     * 原始异常里含表名与索引名，不回吐给前端。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateKey(DuplicateKeyException e,
                                                            HttpServletRequest request) {
        String traceId = newTraceId();
        logger.warn("[{}] 唯一约束冲突: {}", traceId, e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("名称已存在，请换一个", traceId, request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e,
                                                          HttpServletRequest request) {
        String traceId = newTraceId();
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        logger.warn("[{}] 参数校验失败: {}", traceId, detail);
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(detail, traceId, request.getRequestURI()));
    }

    /**
     * 静态资源不存在就是 404，不该走兜底变成 500。
     *
     * <p>否则前端少一个文件会在日志里刷 ERROR 堆栈，且状态码误导排查方向。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException e,
                                                               HttpServletRequest request) {
        logger.debug("静态资源不存在: {}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("资源不存在", newTraceId(), request.getRequestURI()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleStatus(ResponseStatusException e, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        String msg = e.getReason() == null || e.getReason().isBlank() ? e.getMessage() : e.getReason();
        return ResponseEntity.status(status)
                .body(new ErrorResponse(msg, newTraceId(), request.getRequestURI()));
    }

    /**
     * 兜底。不把原始异常信息回吐给前端 —— 它可能包含内网地址、库表名、堆栈等敏感内容，
     * 只返回 traceId，细节留在服务端日志。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception e, HttpServletRequest request) {
        String traceId = newTraceId();
        logger.error("[{}] 服务内部错误: {}", traceId, e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("服务内部错误，请联系管理员并提供 traceId", traceId, request.getRequestURI()));
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
