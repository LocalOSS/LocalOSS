package io.github.letsopen.localoss.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从原始 URI 解析出的 S3 请求上下文。
 *
 * <p>不走 Spring 的 {@code @PathVariable}（会二次解码 %2F、+ 等），
 * 而是由 {@link S3RequestFilter} 基于 requestURI 原始值解析。
 */
@Getter
@ToString
public class S3RequestContext {

    /** 请求唯一 ID（x-amz-request-id） */
    private final String requestId;

    /** 原始（未解码）path，例如 /my-bucket/photos/a%20b.jpg */
    private final String rawPath;

    /** bucket 名（已解码，可能为 null 表示列桶或根操作） */
    private final String bucket;

    /** object key（已解码，仅解码 %XX；+ 保持字面量），可能为 null */
    private final String key;

    /** query 原始串（保持原始编码，供 SigV4 canonical） */
    private final String rawQueryString;

    /** 已解码的 query 参数（重复 key 保留为列表，LinkedHashMap 保持顺序） */
    private final Map<String, List<String>> queryParameters;

    /** 是否 path-style 下访问根路径（ListBuckets） */
    private final boolean rootRequest;

    /** 匿名请求（由控制器在认证判定后设置） */
    @Setter
    private Boolean anonymous;

    public S3RequestContext(String requestId, String rawPath, String bucket, String key,
                            String rawQueryString, Map<String, List<String>> queryParameters,
                            boolean rootRequest) {
        this.requestId = requestId;
        this.rawPath = rawPath;
        this.bucket = bucket;
        this.key = key;
        this.rawQueryString = rawQueryString;
        this.queryParameters = queryParameters;
        this.rootRequest = rootRequest;
    }

    /** 获取第一个匹配的 query 参数值，无则返回 null */
    public String queryParam(String name) {
        List<String> values = queryParameters.get(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    /** 获取匹配 query 参数的全部值，无则返回空列表 */
    public List<String> queryParams(String name) {
        List<String> values = queryParameters.get(name);
        return values == null ? Collections.emptyList() : values;
    }

    /** 是否存在指定 subresource（如 ?delete、?uploads） */
    public boolean hasSubResource(String name) {
        return queryParameters.containsKey(name);
    }

    public boolean hasKey() {
        return key != null;
    }

    public boolean hasBucket() {
        return bucket != null;
    }

    private static final String ATTR = "S3_REQUEST_CONTEXT";

    public static void set(HttpServletRequest request, S3RequestContext ctx) {
        request.setAttribute(ATTR, ctx);
    }

    public static S3RequestContext get(HttpServletRequest request) {
        return (S3RequestContext) request.getAttribute(ATTR);
    }

    public static String getRequestId(HttpServletRequest request) {
        Object v = request.getAttribute(ATTR);
        if (v instanceof S3RequestContext ctx) {
            return ctx.getRequestId();
        }
        return "unknown-" + Integer.toHexString(System.identityHashCode(request));
    }

    /** 不可变化 */
    public Map<String, List<String>> immutableQuery() {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        queryParameters.forEach((k, v) -> copy.put(k, new ArrayList<>(v)));
        return Collections.unmodifiableMap(copy);
    }
}
