package io.github.letsopen.localoss.error;

import io.github.letsopen.localoss.web.S3RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.nio.charset.StandardCharsets;

/**
 * 全局异常处理：所有异常统一转换为 S3 标准 Error XML。
 *
 * <p>三层策略：
 * <ol>
 *   <li>{@link S3Exception}：业务层已定的 S3 错误码，直接映射；</li>
 *   <li>框架已知异常：映射到最接近的 S3 错误码；</li>
 *   <li>未知异常：InternalError 500。</li>
 * </ol>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(S3Exception.class)
    public ResponseEntity<String> handleS3Exception(S3Exception e, HttpServletRequest request) {
        if (e.getErrorCode().getHttpStatus() >= 500) {
            log.error("S3 internal error [{}] on {} {}: {}",
                    e.getErrorCode().getCode(), request.getMethod(), request.getRequestURI(),
                    e.getDetailMessage(), e);
        } else {
            log.debug("S3 error [{}] on {} {}: {}",
                    e.getErrorCode().getCode(), request.getMethod(), request.getRequestURI(),
                    e.getDetailMessage());
        }
        String requestId = S3RequestContext.getRequestId(request);
        String xml = buildErrorXml(e.getErrorCode(), e.getDetailMessage(), request, requestId);
        return ResponseEntity.status(HttpStatus.valueOf(e.getErrorCode().getHttpStatus()))
                .contentType(MediaType.APPLICATION_XML)
                .header("x-amz-request-id", requestId)
                .body(xml);
    }

    // ==================== 框架异常映射 ====================

    /** multipart 表单损坏/超限 → MalformedPOSTRequest / EntityTooLarge */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<String> handleMultipart(MultipartException e,
                                                  HttpServletRequest request) {
        log.debug("Multipart error on {} {}: {}", request.getMethod(),
                request.getRequestURI(), e.getMessage());
        String requestId = S3RequestContext.getRequestId(request);
        S3ErrorCode code = S3ErrorCode.MALFORMED_XML;
        String msg = "The provided multipart form data is malformed or exceeds limits";
        if (e.getMessage() != null && e.getMessage().contains("SizeLimitExceeded")) {
            code = S3ErrorCode.ENTITY_TOO_LARGE;
            msg = "Your proposed upload exceeds the maximum allowed size";
        }
        return xmlResponse(code, msg, request, requestId);
    }

    /** 请求体不可读 → IncompleteBody */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<String> handleNotReadable(HttpMessageNotReadableException e,
                                                    HttpServletRequest request) {
        return xmlResponse(S3ErrorCode.INCOMPLETE_BODY, "The request body terminated unexpectedly",
                request, S3RequestContext.getRequestId(request));
    }

    /** 方法不支持 → MethodNotAllowed（S3 语义） */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<String> handleMethodNotSupported(HttpRequestMethodNotSupportedException e,
                                                           HttpServletRequest request) {
        return xmlResponse(S3ErrorCode.METHOD_NOT_ALLOWED,
                "The specified method is not allowed against this resource.",
                request, S3RequestContext.getRequestId(request));
    }

    /** 媒体类型不支持 → UnsupportedContentType（非 S3 标准码，本地定义） */
    @ExceptionHandler({HttpMediaTypeNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class})
    public ResponseEntity<String> handleMediaType(Exception e, HttpServletRequest request) {
        return xmlResponse(S3ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "The specified media type is not supported.",
                request, S3RequestContext.getRequestId(request));
    }

    /** 缺参数 → InvalidArgument */
    @ExceptionHandler({MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class})
    public ResponseEntity<String> handleMissingParam(Exception e, HttpServletRequest request) {
        return xmlResponse(S3ErrorCode.INVALID_ARGUMENT,
                "A required parameter or part is missing.",
                request, S3RequestContext.getRequestId(request));
    }

    /** 类型不匹配 → InvalidArgument */
    @ExceptionHandler({TypeMismatchException.class, ConversionNotSupportedException.class})
    public ResponseEntity<String> handleTypeMismatch(Exception e, HttpServletRequest request) {
        return xmlResponse(S3ErrorCode.INVALID_ARGUMENT, "Invalid parameter type.",
                request, S3RequestContext.getRequestId(request));
    }

    /** 404（未匹配路由）→ 按资源语义返回 NoSuchBucket/NoSuchKey 不易判断，用标准 NoSuchResource */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<String> handleNoHandler(NoHandlerFoundException e,
                                                  HttpServletRequest request) {
        return xmlResponse(S3ErrorCode.NO_SUCH_BUCKET,
                "The specified bucket does not exist",
                request, S3RequestContext.getRequestId(request));
    }

    /** 非法参数（编程式 throw IllegalArgumentException 统一转 InvalidArgument） */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException e,
                                                        HttpServletRequest request) {
        log.debug("IllegalArgument on {} {}: {}", request.getMethod(),
                request.getRequestURI(), e.getMessage());
        return xmlResponse(S3ErrorCode.INVALID_ARGUMENT, sanitize(e.getMessage()),
                request, S3RequestContext.getRequestId(request));
    }

    /** 兜底：未知异常 → InternalError 500 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleGeneric(Exception e, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), e);
        return xmlResponse(S3ErrorCode.INTERNAL_ERROR, S3ErrorCode.INTERNAL_ERROR.getMessage(),
                request, S3RequestContext.getRequestId(request));
    }

    private ResponseEntity<String> xmlResponse(S3ErrorCode code, String message,
                                               HttpServletRequest request, String requestId) {
        String xml = buildErrorXml(code, message, request, requestId);
        return ResponseEntity.status(HttpStatus.valueOf(code.getHttpStatus()))
                .contentType(MediaType.APPLICATION_XML)
                .header("x-amz-request-id", requestId)
                .body(xml);
    }

    private String buildErrorXml(S3ErrorCode code, String message,
                                 HttpServletRequest request, String requestId) {
        String resource = request.getRequestURI();
        StringBuilder sb = new StringBuilder(512);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<Error>\n");
        append(sb, "Code", code.getCode());
        append(sb, "Message", message);
        append(sb, "Resource", resource);
        append(sb, "RequestId", requestId);
        append(sb, "HostId", requestId);
        sb.append("</Error>\n");
        return sb.toString();
    }

    private void append(StringBuilder sb, String tag, String value) {
        if (value == null) {
            return;
        }
        sb.append("  <").append(tag).append('>')
                .append(escape(value))
                .append("</").append(tag).append(">\n");
    }

    private String escape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '\'' -> out.append("&apos;");
                case '"' -> out.append("&quot;");
                case '\r' -> out.append("&#13;");
                case '\n' -> out.append("&#10;");
                default -> {
                    if (c >= 0x20 && c != 0x7F) {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private String sanitize(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n]", "_");
    }
}
