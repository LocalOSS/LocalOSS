package io.github.letsopen.localoss.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.letsopen.localoss.error.S3ErrorCode;
import io.github.letsopen.localoss.error.S3Exception;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * S3 请求解析过滤器。
 *
 * <p>在所有 Controller 之前运行：
 * <ol>
 *   <li>生成 x-amz-request-id / x-amz-id-2 / Date / Server 响应头</li>
 *   <li>从原始 URI 解析 bucket / key（只解码 %XX，+ 不转空格）</li>
 *   <li>解析 query（保留重复参数），支持 subresource 判定</li>
 *   <li>解析失败直接抛 S3Exception（由 GlobalExceptionHandler 转 Error XML）</li>
 * </ol>
 */
@Slf4j
@Component
public class S3RequestFilter extends OncePerRequestFilter {

    /** 健康检查路径（已确认 R7，避免与 bucket 名 localoss 冲突） */
    public static final String HEALTH_PATH = "/localoss-health";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals(HEALTH_PATH) || path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = newRequestId();
        response.setHeader("x-amz-request-id", requestId);
        response.setHeader("x-amz-id-2", requestId);
        response.setDateHeader("Date", System.currentTimeMillis());
        response.setHeader("Server", "LocalOSS");

        S3RequestContext ctx;
        try {
            ctx = parse(request, requestId);
        } catch (S3Exception e) {
            // 过滤器层异常不经过 ControllerAdvice：直接写 S3 Error XML
            writeErrorXml(response, e.getErrorCode(), e.getDetailMessage(),
                    request.getRequestURI(), requestId);
            return;
        }
        S3RequestContext.set(request, ctx);
        chain.doFilter(request, response);
    }

    /** 过滤器层 S3 错误输出（标准 Error XML） */
    private void writeErrorXml(HttpServletResponse response, S3ErrorCode code, String message,
                               String resource, String requestId) throws IOException {
        response.setStatus(code.getHttpStatus());
        response.setContentType("application/xml");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        StringBuilder sb = new StringBuilder(512);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<Error>\n");
        sb.append("  <Code>").append(code.getCode()).append("</Code>\n");
        if (message != null) {
            sb.append("  <Message>").append(xmlEscape(message)).append("</Message>\n");
        }
        sb.append("  <Resource>").append(xmlEscape(resource)).append("</Resource>\n");
        sb.append("  <RequestId>").append(requestId).append("</RequestId>\n");
        sb.append("  <HostId>").append(requestId).append("</HostId>\n");
        sb.append("</Error>\n");
        response.getOutputStream().write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String xmlEscape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
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

    private String newRequestId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    /**
     * 解析原始 URI。
     *
     * <p>path-style：/{bucket}[/{key}]。仅解码 %XX；不对 + 做空格转换（S3 key 中 + 是字面量）。
     */
    S3RequestContext parse(HttpServletRequest request, String requestId) {
        String rawPath = request.getRequestURI();
        // 去掉 context path（应用默认部署在根，防御性处理）
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && rawPath.startsWith(contextPath)) {
            rawPath = rawPath.substring(contextPath.length());
        }
        if (rawPath.isEmpty()) {
            rawPath = "/";
        }

        String rawQuery = request.getQueryString() == null ? "" : request.getQueryString();
        Map<String, List<String>> query = parseQuery(rawQuery);

        if ("/".equals(rawPath)) {
            return new S3RequestContext(requestId, rawPath, null, null, rawQuery, query, true);
        }

        if (!rawPath.startsWith("/")) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "URI must start with /");
        }

        String rest = rawPath.substring(1);
        int slash = rest.indexOf('/');
        String rawBucket = slash < 0 ? rest : rest.substring(0, slash);
        String rawKey = slash < 0 ? null : rest.substring(slash + 1);

        String bucket = percentDecode(rawBucket);
        String key = rawKey == null ? null : percentDecode(rawKey);

        // 禁止尾随 / 于 bucket 段
        if (bucket.isEmpty()) {
            throw new S3Exception(S3ErrorCode.INVALID_BUCKET_NAME, "Empty bucket name in URI");
        }

        return new S3RequestContext(requestId, rawPath, bucket, key, rawQuery, query, false);
    }

    /**
     * RFC3986 percent-decode：只解码 %XX；+ 保持字面量。
     */
    static String percentDecode(String s) {
        if (s == null || s.indexOf('%') < 0) {
            return s;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) {
                    throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Malformed percent encoding: " + s);
                }
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Malformed percent encoding: " + s);
                }
                out.write((hi << 4) | lo);
                i += 2;
            } else {
                // ASCII char only for path; other chars (UTF-8 pre-decoded by container) pass through
                byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(b, 0, b.length);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * 解析 query 串为参数表（保留重复参数；值做 percent-decode，+ 转空格——query 中 + 依 RFC 表示空格）。
     */
    static Map<String, List<String>> parseQuery(String rawQuery) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return map;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            String name;
            String value;
            int eq = pair.indexOf('=');
            if (eq < 0) {
                name = queryDecode(pair);
                value = "";
            } else {
                name = queryDecode(pair.substring(0, eq));
                value = queryDecode(pair.substring(eq + 1));
            }
            map.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return map;
    }

    private static String queryDecode(String s) {
        if (s.indexOf('%') < 0 && s.indexOf('+') < 0) {
            return s;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '+') {
                out.write(' ');
            } else if (c == '%') {
                if (i + 2 >= s.length()) {
                    throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Malformed query encoding");
                }
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Malformed query encoding");
                }
                out.write((hi << 4) | lo);
                i += 2;
            } else {
                byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(b, 0, b.length);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
