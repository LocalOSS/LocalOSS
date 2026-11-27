package io.github.letsopen.localoss.auth;

import io.github.letsopen.localoss.config.SystemProperties;
import io.github.letsopen.localoss.error.S3ErrorCode;
import io.github.letsopen.localoss.error.S3Exception;
import io.github.letsopen.localoss.web.S3RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * AWS Signature Version 4 校验（计划 5.1 / 5.2 节）。
 *
 * <p>支持：
 * <ul>
 *   <li>Authorization Header（含 payload hash 校验，UNSIGNED-PAYLOAD 除外）</li>
 *   <li>Presigned URL（X-Amz-Algorithm 等 query 参数）</li>
 * </ul>
 */
@Slf4j
@Component
public class SigV4Verifier {

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final DateTimeFormatter AMZ_DATE_COMPACT =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    private final SystemProperties props;

    public SigV4Verifier(SystemProperties props) {
        this.props = props;
    }

    /** 是否免认证：未配置 access-key 时关闭认证（开发便利，生产须配置） */
    public boolean authDisabled() {
        return props.getAccessKey() == null || props.getAccessKey().isEmpty();
    }

    /** 流式签名上下文：解码 aws-chunked body 所需 */
    public record SigningContext(String seedSignature, String amzDate, byte[] signingKey,
                                 String credentialScope, String accessKey, String credentialLevel) {
    }

    /** 校验请求签名；不合法抛 S3Exception。返回流式上下文（非流式为 null） */
    public SigningContext verify(HttpServletRequest request, S3RequestContext ctx,
                                 String actualPayloadHash) {
        if (authDisabled()) {
            return null;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("AWS4-HMAC-SHA256")) {
            return verifyHeader(request, ctx, authorization, actualPayloadHash);
        } else if ("AWS4-HMAC-SHA256".equals(ctx.queryParam("X-Amz-Algorithm"))) {
            verifyPresigned(request, ctx);
            String cred = ctx.queryParam("X-Amz-Credential");
            String presignAk = cred == null ? null : cred.split("/")[0];
            return new SigningContext(null, null, null, null, presignAk,
                    presignAk == null ? null : levelForAccessKey(presignAk));
        } else {
            // 匿名请求：读操作（GET/HEAD）延迟到对象级 ACL 判定；
            // 写/删操作由 guardWrite/guardDelete 拒绝（AccessMode），此处不抛。
            // 但必须在控制器读路径上对 private 对象显式拒绝（见 getObjectOrHead）。
            return ANONYMOUS;
        }
    }

    /** 匿名请求标记（未携带凭证） */
    public static final SigningContext ANONYMOUS =
            new SigningContext(null, null, null, null, null, null);

    // ==================== Header auth ====================

    private SigningContext verifyHeader(HttpServletRequest request, S3RequestContext ctx,
                                        String authorization, String actualPayloadHash) {
        // Authorization: AWS4-HMAC-SHA256 Credential=AK/date/region/s3/aws4_request,
        //                SignedHeaders=a;b;c, Signature=hex
        Map<String, String> parts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        String rest = authorization.substring("AWS4-HMAC-SHA256".length()).trim();
        for (String pair : rest.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                parts.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        String credential = parts.get("Credential");
        String signedHeaders = parts.get("SignedHeaders");
        String signature = parts.get("Signature");
        if (credential == null || signedHeaders == null || signature == null) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Malformed Authorization header");
        }

        String[] cred = credential.split("/");
        if (cred.length < 5) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Malformed credential scope");
        }
        String accessKey = cred[0];
        String date = cred[1];
        String region = cred[2];
        String service = cred[3];
        String terminator = cred[4];
        String secretForRequest = secretForAccessKey(accessKey);
        if (secretForRequest == null) {
            throw new S3Exception(S3ErrorCode.INVALID_ACCESS_KEY_ID);
        }
        if (!"s3".equals(service) || !"aws4_request".equals(terminator)) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Invalid credential scope");
        }

        String amzDate = request.getHeader("x-amz-date");
        if (amzDate == null) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Missing x-amz-date header");
        }
        // 容忍 x-amz-security-token（单租户不做 STS 校验，但保留头的存在以免 SDK 报错）
        if (request.getHeader("x-amz-security-token") == null
                && request.getParameter("X-Amz-Security-Token") != null) {
            // presigned 带 token：允许
        }
        checkClockSkew(amzDate);

        // payload hash
        String payloadHash = request.getHeader("x-amz-content-sha256");
        if (payloadHash == null) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                    "Missing x-amz-content-sha256 header");
        }
        boolean streaming = payloadHash.startsWith("STREAMING-");
        if (!streaming
                && !"UNSIGNED-PAYLOAD".equals(payloadHash)
                && actualPayloadHash != null
                && !payloadHash.equalsIgnoreCase(actualPayloadHash)) {
            throw new S3Exception(S3ErrorCode.SIGNATURE_DOES_NOT_MATCH,
                    "Payload hash does not match actual body");
        }

        String canonicalRequest = buildCanonicalRequest(request, ctx, signedHeaders, payloadHash);
        String stringToSign = buildStringToSign(amzDate, date, region, canonicalRequest);
        byte[] signingKey = signingKey(props.getSecretKey(), date, region);
        byte[] computed = hmacSha256(signingKey, stringToSign);
        String computedHex = hex(computed);
        if (!constantTimeEquals(computedHex, signature)) {
            log.warn("SigV4 mismatch: expected={} actual={}", computedHex, signature);
            throw new S3Exception(S3ErrorCode.SIGNATURE_DOES_NOT_MATCH);
        }
        String sha256Hdr = request.getHeader("x-amz-content-sha256");
        boolean isStreaming = sha256Hdr != null && sha256Hdr.startsWith("STREAMING-");
        return isStreaming ? new SigningContext(signature, amzDate,
                signingKey(secretForRequest, date, region),
                date + "/" + region + "/s3/aws4_request", accessKey, levelForAccessKey(accessKey))
                : new SigningContext(null, null, null, null, accessKey,
                        levelForAccessKey(accessKey));
    }

    // ==================== Presigned ====================

    private void verifyPresigned(HttpServletRequest request, S3RequestContext ctx) {
        String credential = ctx.queryParam("X-Amz-Credential");
        String amzDate = ctx.queryParam("X-Amz-Date");
        String expiresRaw = ctx.queryParam("X-Amz-Expires");
        String signedHeaders = ctx.queryParam("X-Amz-SignedHeaders");
        String signature = ctx.queryParam("X-Amz-Signature");
        if (credential == null || amzDate == null || expiresRaw == null
                || signedHeaders == null || signature == null) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Malformed presigned URL");
        }
        String[] cred = credential.split("/");
        if (cred.length < 5) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Malformed credential scope");
        }
        String accessKey = cred[0];
        String date = cred[1];
        String region = cred[2];
        String secretForRequest = secretForAccessKey(accessKey);
        if (secretForRequest == null) {
            throw new S3Exception(S3ErrorCode.INVALID_ACCESS_KEY_ID);
        }
        checkClockSkew(amzDate);

        long expires;
        try {
            expires = Long.parseLong(expiresRaw);
        } catch (NumberFormatException e) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Invalid X-Amz-Expires");
        }
        if (expires < 1 || expires > props.getPresignMaxExpiresSeconds()) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                    "X-Amz-Expires must be between 1 and " + props.getPresignMaxExpiresSeconds());
        }
        Instant start = parseAmzDate(amzDate);
        if (Instant.now().isAfter(start.plusSeconds(expires))) {
            throw new S3Exception(S3ErrorCode.ACCESS_DENIED, "Request has expired");
        }

        // canonical query 排除 X-Amz-Signature
        String canonicalQuery = canonicalQuery(ctx, "X-Amz-Signature");
        String canonicalRequest = request.getMethod() + "\n"
                + canonicalUri(ctx) + "\n"
                + canonicalQuery + "\n"
                + canonicalHeaders(request, signedHeaders) + "\n"
                + signedHeaders + "\n"
                + "UNSIGNED-PAYLOAD";
        String stringToSign = buildStringToSign(amzDate, date, region, canonicalRequest);
        byte[] signingKey = signingKey(secretForRequest, date, region);
        String computedHex = hex(hmacSha256(signingKey, stringToSign));
        if (!constantTimeEquals(computedHex, signature)) {
            log.warn("SigV4 PRESIGN mismatch: expected={} actual={}", computedHex, signature);
            throw new S3Exception(S3ErrorCode.SIGNATURE_DOES_NOT_MATCH);
        }
    }

    // ==================== canonical builders ====================

    private String buildCanonicalRequest(HttpServletRequest request, S3RequestContext ctx,
                                         String signedHeaders, String payloadHash) {
        return request.getMethod() + "\n"
                + canonicalUri(ctx) + "\n"
                + canonicalQuery(ctx, null) + "\n"
                + canonicalHeaders(request, signedHeaders) + "\n"
                + signedHeaders + "\n"
                + payloadHash;
    }

    /** canonical URI：S3 规则不规范化路径，对每个段做 RFC3986 编码（保留 /） */
    private String canonicalUri(S3RequestContext ctx) {
        String path = ctx.getRawPath();
        if (path.isEmpty()) {
            return "/";
        }
        StringBuilder sb = new StringBuilder();
        for (String seg : path.split("/", -1)) {
            if (!seg.isEmpty()) {
                sb.append('/');
                sb.append(uriEncode(seg, false));
            }
        }
        if (sb.length() == 0) {
            return "/";
        }
        return sb.toString();
    }

    private String canonicalQuery(S3RequestContext ctx, String exclude) {
        // 取原始 query 串解析（保留重复参数），按 name/value 排序
        List<String[]> pairs = new ArrayList<>();
        String raw = ctx.getRawQueryString();
        if (raw != null && !raw.isEmpty()) {
            for (String pair : raw.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                String name;
                String value;
                int eq = pair.indexOf('=');
                if (eq < 0) {
                    name = pair;
                    value = "";
                } else {
                    name = pair.substring(0, eq);
                    value = pair.substring(eq + 1);
                }
                // query 值需要先 decode 再 encode（canonical 规范）
                String dn = uriEncode(java.net.URLDecoder.decode(
                        name.replace("+", "%20"), StandardCharsets.UTF_8), true);
                String dv = uriEncode(java.net.URLDecoder.decode(
                        value.replace("+", "%20"), StandardCharsets.UTF_8), true);
                if (exclude != null && dn.equals(exclude)) {
                    continue;
                }
                pairs.add(new String[]{dn, dv});
            }
        }
        pairs.sort((a, b) -> {
            int c = a[0].compareTo(b[0]);
            return c != 0 ? c : a[1].compareTo(b[1]);
        });
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pairs.size(); i++) {
            if (i > 0) {
                sb.append('&');
            }
            sb.append(pairs.get(i)[0]).append('=').append(pairs.get(i)[1]);
        }
        return sb.toString();
    }

    private String canonicalHeaders(HttpServletRequest request, String signedHeaders) {
        StringBuilder sb = new StringBuilder();
        for (String h : signedHeaders.split(";")) {
            String name = h.toLowerCase(Locale.ROOT);
            List<String> values = new ArrayList<>();
            java.util.Enumeration<String> e = request.getHeaders(h);
            if (e != null) {
                while (e.hasMoreElements()) {
                    values.add(e.nextElement().trim().replaceAll("\\s+", " "));
                }
            }
            if (values.isEmpty()) {
                // presigned GET 请求可能不含某些 signed header（如 host 由容器提供）
                if (name.equals("host")) {
                    values.add(request.getServerName()
                            + (request.getServerPort() == 80 || request.getServerPort() == 443
                            ? "" : ":" + request.getServerPort()));
                } else {
                    values.add("");
                }
            }
            sb.append(name).append(':').append(String.join(",", values)).append('\n');
        }
        return sb.toString();
    }

    private String buildStringToSign(String amzDate, String date, String region,
                                     String canonicalRequest) {
        return "AWS4-HMAC-SHA256\n"
                + amzDate + "\n"
                + date + "/" + region + "/s3/aws4_request\n"
                + hex(sha256(canonicalRequest));
    }

    // ==================== credentials ====================

    /** 查找 access key 对应的 secret（主凭证 + credentials 列表）；未找到返回 null */
    private String secretForAccessKey(String accessKey) {
        if (!props.getAccessKey().isEmpty() && props.getAccessKey().equals(accessKey)) {
            return props.getSecretKey();
        }
        for (var c : props.getCredentials()) {
            if (accessKey.equals(c.getAccessKey())) {
                return c.getSecretKey();
            }
        }
        return null;
    }

    /** 查找凭证级限制（无则 null） */
    private String levelForAccessKey(String accessKey) {
        for (var c : props.getCredentials()) {
            if (accessKey.equals(c.getAccessKey())) {
                return c.getLevel();
            }
        }
        return null;
    }

    // ==================== helpers ====================

    private void checkClockSkew(String amzDate) {
        Instant requestTime = parseAmzDate(amzDate);
        Duration skew = Duration.between(requestTime, Instant.now()).abs();
        if (skew.getSeconds() > props.getClockSkewSeconds()) {
            throw new S3Exception(S3ErrorCode.REQUEST_TIME_TOO_SKEWED,
                    "Clock skew " + skew.getSeconds() + "s exceeds allowed "
                            + props.getClockSkewSeconds() + "s");
        }
    }

    /** 解析 x-amz-date（UTC 固定时区） */
    public static Instant parseAmzDate(String amzDate) {
        try {
            return AMZ_DATE.withZone(ZoneOffset.UTC).parse(amzDate, Instant::from);
        } catch (java.time.format.DateTimeParseException
                 | java.time.temporal.UnsupportedTemporalTypeException e) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Invalid x-amz-date format");
        }
    }

    /** RFC3986 uri encode；encodeSlashSlash=false 时保留 /（canonical URI 用） */
    static String uriEncode(String s, boolean isQueryComponent) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~';
            if (unreserved) {
                sb.append(c);
            } else if (c == '/' && !isQueryComponent) {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return sb.toString();
    }

    static byte[] sha256(String data) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(data.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] sha256Bytes(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 供测试与 chunk 解码使用 */
    public static byte[] publicSigningKey(String secret, String date, String region) {
        return signingKey(secret, date, region);
    }

    public static String publicHexSha256(String data) {
        return hex(sha256(data));
    }

    public static String publicHmacHex(byte[] key, String data) {
        return hex(hmacSha256(key, data));
    }

    static byte[] signingKey(String secret, String date, String region) {
        byte[] kSecret = ("AWS4" + secret).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = hmacSha256(kSecret, date);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, "s3");
        return hmacSha256(kService, "aws4_request");
    }

    static byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) {
            sb.append(Character.forDigit((v >> 4) & 0xF, 16));
            sb.append(Character.forDigit(v & 0xF, 16));
        }
        return sb.toString();
    }

    static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
