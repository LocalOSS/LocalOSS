package io.github.letsopen.localoss.web;

import io.github.letsopen.localoss.config.AccessMode;
import io.github.letsopen.localoss.config.SystemProperties;
import io.github.letsopen.localoss.error.S3ErrorCode;
import io.github.letsopen.localoss.error.S3Exception;
import io.github.letsopen.localoss.model.ObjectMetadata;
import io.github.letsopen.localoss.storage.KeyValidator;
import io.github.letsopen.localoss.storage.StorageEngine;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import io.github.letsopen.localoss.auth.SigV4Verifier;
import io.github.letsopen.localoss.config.AccessModeResolver;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * S3 入口（Phase 1：Bucket 基础 + Object CRUD）。
 *
 * <p>不使用 {@code @PathVariable}；全部从 {@link S3RequestContext} 读取，
 * 避免容器二次解码破坏 key。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class S3Controller {

    private static final String SIGNING_CTX_ATTR = "LOCALOSS_SIGNING_CTX";

    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter RFC1123_GMT =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private final SystemProperties props;
    private final StorageEngine storage;
    private final KeyValidator keyValidator;
    private final io.github.letsopen.localoss.storage.MultipartStore multipartStore;
    private final io.github.letsopen.localoss.auth.SigV4Verifier sigV4;
    private final io.github.letsopen.localoss.auth.AwsChunkedDecoder chunkedDecoder;
    private final io.github.letsopen.localoss.storage.AclEvaluator aclEvaluator;
    private final io.github.letsopen.localoss.storage.LifecycleExecutor lifecycleExecutor;

    // ==================== Access mode guard ====================

    private AccessMode effectiveMode(HttpServletRequest request, S3RequestContext ctx) {
        if (request == null) {
            return props.getAccessMode();
        }
        var signingCtx = (io.github.letsopen.localoss.auth.SigV4Verifier.SigningContext)
                request.getAttribute(SIGNING_CTX_ATTR);
        String level = signingCtx == null ? null : signingCtx.credentialLevel();
        return AccessModeResolver.resolve(props.getAccessMode(), level);
    }

    private void guardRead(HttpServletRequest request, S3RequestContext ctx) {
        AccessMode m = effectiveMode(request, ctx);
        if (m == AccessMode.DISABLED) {
            throw new S3Exception(S3ErrorCode.SERVICE_UNAVAILABLE, "Service in disabled mode");
        }
        if (!m.allowRead()) {
            throw new S3Exception(S3ErrorCode.ACCESS_DENIED, "Read operations disabled");
        }
    }

    private void guardWrite(HttpServletRequest request, S3RequestContext ctx) {
        if (isAnonymousRequest(ctx) && !props.isAllowAnonymousRead()) {
            throw new S3Exception(S3ErrorCode.ACCESS_DENIED,
                    "Anonymous access is forbidden for this operation");
        }
        AccessMode m = effectiveMode(request, ctx);
        if (m == AccessMode.DISABLED) {
            throw new S3Exception(S3ErrorCode.SERVICE_UNAVAILABLE, "Service in disabled mode");
        }
        if (!m.allowWrite()) {
            throw new S3Exception(S3ErrorCode.ACCESS_DENIED, "Write operations disabled");
        }
    }

    private void guardDelete(HttpServletRequest request, S3RequestContext ctx) {
        if (isAnonymousRequest(ctx) && !props.isAllowAnonymousRead()) {
            throw new S3Exception(S3ErrorCode.ACCESS_DENIED,
                    "Anonymous access is forbidden for this operation");
        }
        AccessMode m = effectiveMode(request, ctx);
        if (m == AccessMode.DISABLED) {
            throw new S3Exception(S3ErrorCode.SERVICE_UNAVAILABLE, "Service in disabled mode");
        }
        if (!m.allowDelete()) {
            throw new S3Exception(S3ErrorCode.ACCESS_DENIED, "Delete operations disabled");
        }
    }

    private void guardHead(HttpServletRequest request, S3RequestContext ctx) {
        AccessMode m = effectiveMode(request, ctx);
        if (m == AccessMode.DISABLED) {
            throw new S3Exception(S3ErrorCode.SERVICE_UNAVAILABLE, "Service in disabled mode");
        }
        if (!m.allowHead()) {
            throw new S3Exception(S3ErrorCode.ACCESS_DENIED, "Service in disabled mode");
        }
    }

    // ==================== Bucket ====================

    @org.springframework.web.bind.annotation.RequestMapping(
            value = "/**", method = org.springframework.web.bind.annotation.RequestMethod.PUT)
    public void handlePut(HttpServletRequest request, HttpServletResponse response) throws IOException {
        S3RequestContext ctx = S3RequestContext.get(request);
        io.github.letsopen.localoss.auth.SigV4Verifier.SigningContext signingCtx =
                sigV4.verify(request, ctx, null);
        request.setAttribute(SIGNING_CTX_ATTR, signingCtx);
        ctx.setAnonymous(signingCtx == io.github.letsopen.localoss.auth.SigV4Verifier.ANONYMOUS);
        if (ctx.isRootRequest()) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                    "PUT / is not a valid S3 operation; PUT /{bucket} to create a bucket");
        }
        if (ctx.hasSubResource("delete")) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                    "DeleteObjects requires POST method");
        }
        if (ctx.hasSubResource("uploadId") && ctx.hasSubResource("partNumber")) {
            guardWrite(request, ctx);
            uploadPart(ctx, request, response);
            return;
        }
        if (ctx.hasSubResource("uploads") || ctx.hasSubResource("uploadId")) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                    "This subresource requires a different HTTP method");
        }
        String cannedAcl = aclEvaluator.parseCannedHeader(request.getHeader("x-amz-acl"));
        if (request.getHeader("x-amz-copy-source") != null) {
            guardWrite(request, ctx);
            copyObject(ctx, request, response, cannedAcl);
            return;
        }
        guardWrite(request, ctx);
        if (!ctx.hasBucket()) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Missing bucket");
        }
        aclEvaluator.rejectGrantHeaders(headerMap(request));
        if (ctx.hasKey() && ctx.hasSubResource("acl")) {
            putObjectAcl(ctx, request, response);
            return;
        }
        if (!ctx.hasKey() && ctx.hasSubResource("acl")) {
            putBucketAcl(ctx, request, response);
            return;
        }
        if (ctx.hasKey() && ctx.hasSubResource("tagging")) {
            putObjectTagging(ctx, request, response);
            return;
        }
        if (!ctx.hasKey() && ctx.hasSubResource("tagging")) {
            putBucketTagging(ctx, request, response);
            return;
        }
        if (!ctx.hasKey() && ctx.hasSubResource("cors")) {
            guardWrite(request, ctx);
            putBucketConfigXml(ctx, ctx.getBucket(), "cors", request, response);
            return;
        }
        if (!ctx.hasKey() && ctx.hasSubResource("policy")) {
            guardWrite(request, ctx);
            putBucketPolicy(ctx, request, response);
            return;
        }
        if (!ctx.hasKey() && ctx.hasSubResource("publicAccessBlock")) {
            guardWrite(request, ctx);
            putBucketConfigXml(ctx, ctx.getBucket(), "publicAccessBlock", request, response);
            return;
        }
        if (!ctx.hasKey() && ctx.hasSubResource("ownershipControls")) {
            guardWrite(request, ctx);
            putBucketConfigXml(ctx, ctx.getBucket(), "ownershipControls", request, response);
            return;
        }
        if (!ctx.hasKey() && ctx.hasSubResource("lifecycle")) {
            guardWrite(request, ctx);
            putLifecycle(ctx, request, response);
            return;
        }
        if (!ctx.hasKey() && ctx.hasSubResource("acl")) {
            putBucketAcl(ctx, request, response);
            return;
        }
        if (!ctx.hasKey()) {
            // CreateBucket
            keyValidator.validateBucketName(ctx.getBucket());
            if (storage.bucketExists(ctx.getBucket())) {
                throw new S3Exception(S3ErrorCode.BUCKET_ALREADY_OWNED_BY_YOU,
                        "Bucket already exists and is owned by you");
            }
            storage.createBucket(ctx.getBucket());
            response.setHeader("Location", "/" + ctx.getBucket());
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }
        // PutObject
        putObject(ctx, request, response, cannedAcl);
    }

    /**
     * aws-chunked 请求体解码包装：STREAMING-* 时逐 chunk 验签并剥离框架，返回纯数据流。
     */
    private InputStream wrapChunkedBody(HttpServletRequest request) throws IOException {
        String sha256Hdr = request.getHeader("x-amz-content-sha256");
        if (sha256Hdr == null || !sha256Hdr.startsWith("STREAMING-")) {
            return request.getInputStream();
        }
        var signingCtx = (io.github.letsopen.localoss.auth.SigV4Verifier.SigningContext)
                request.getAttribute(SIGNING_CTX_ATTR);
        if (signingCtx == null) {
            // 认证关闭（开发模式）：只解码，不验签
            return new io.github.letsopen.localoss.auth.AwsChunkedDecoder.DecodedStream(
                    new io.github.letsopen.localoss.auth.AwsChunkedDecoder.ChunkParser(
                            request.getInputStream(),
                            io.github.letsopen.localoss.auth.AwsChunkedDecoder
                                    .isSignedChunkHeader(sha256Hdr),
                            io.github.letsopen.localoss.auth.AwsChunkedDecoder
                                    .hasTrailerHeader(sha256Hdr),
                            null, null, null, null, props),
                    io.github.letsopen.localoss.auth.AwsChunkedDecoder.hasTrailerHeader(sha256Hdr));
        }
        return chunkedDecoder.decode(request.getInputStream(), sha256Hdr,
                signingCtx.seedSignature(), signingCtx.amzDate(), signingCtx.signingKey(),
                signingCtx.credentialScope());
    }

    /** 批量删除（Phase 2，POST /{bucket}?delete） */
    private void deleteObjects(S3RequestContext ctx, HttpServletRequest request,
                               HttpServletResponse response) throws IOException {
        if (!ctx.hasBucket() || ctx.hasKey()) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "?delete applies to bucket only");
        }
        byte[] body = request.getInputStream().readAllBytes();
        var parsed = io.github.letsopen.localoss.xml.S3XmlReader.parse(body);
        java.util.List<String> keys = parsed.list("Delete/Object/Key");
        if (keys.isEmpty()) {
            throw new S3Exception(S3ErrorCode.MALFORMED_XML, "No Object/Key in Delete XML");
        }
        if (keys.size() > 1000) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                    "DeleteObjects limited to 1000 keys per request");
        }
        Map<String, Boolean> results = storage.deleteObjects(ctx.getBucket(), keys);
        String quietRaw = parsed.get("Delete/Quiet");
        boolean quiet = quietRaw != null && "true".equalsIgnoreCase(quietRaw.trim());

        StringBuilder xml = new StringBuilder(1024);
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.header("DeleteResult"));
        for (var e : results.entrySet()) {
            if (e.getValue()) {
                if (!quiet) {
                    xml.append("  <Deleted>\n");
                    xml.append(io.github.letsopen.localoss.xml.S3XmlWriter
                            .elementIndented("    ", "Key", e.getKey()));
                    xml.append("  </Deleted>\n");
                }
            } else {
                xml.append("  <Error>\n");
                xml.append(io.github.letsopen.localoss.xml.S3XmlWriter
                        .elementIndented("    ", "Key", e.getKey()));
                xml.append(io.github.letsopen.localoss.xml.S3XmlWriter
                        .elementIndented("    ", "Code", "InternalError"));
                xml.append(io.github.letsopen.localoss.xml.S3XmlWriter
                        .elementIndented("    ", "Message", "Delete failed"));
                xml.append("  </Error>\n");
            }
        }
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.footer("DeleteResult"));
        writeXml(response, HttpServletResponse.SC_OK, xml.toString());
    }

    /** CopyObject（Phase 2） */
    private void copyObject(S3RequestContext ctx, HttpServletRequest request,
                            HttpServletResponse response, String cannedAcl) throws IOException {
        String copySource = request.getHeader("x-amz-copy-source");
        String sourceBucket;
        String sourceKey;
        // parse /bucket/key or bucket/key (URL-encoded)
        String decoded = S3RequestFilter.percentDecode(copySource);
        String path = decoded.startsWith("/") ? decoded.substring(1) : decoded;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int slash = path.indexOf('/');
        if (slash <= 0) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Invalid x-amz-copy-source");
        }
        sourceBucket = path.substring(0, slash);
        sourceKey = path.substring(slash + 1);
        if (sourceKey.isEmpty()) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Invalid copy source key");
        }
        String directive = request.getHeader("x-amz-metadata-directive");

        ObjectMetadata meta = storage.copyObject(sourceBucket, sourceKey,
                ctx.getBucket(), ctx.getKey(), null, null, directive, cannedAcl);

        StringBuilder xml = new StringBuilder(512);
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.header("CopyObjectResult"));
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("  ",
                "LastModified",
                ISO_MILLIS.format(Instant.parse(meta.getLastModified()))));
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("  ",
                "ETag", meta.getEtag()));
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.footer("CopyObjectResult"));
        response.setHeader("ETag", meta.getEtag());
        writeXml(response, HttpServletResponse.SC_OK, xml.toString());
    }

    private void putObject(S3RequestContext ctx, HttpServletRequest request,
                           HttpServletResponse response, String cannedAcl) throws IOException {
        String bucket = ctx.getBucket();
        String key = ctx.getKey();

        Map<String, String> contentHeaders = new HashMap<>();
        for (String h : new String[]{"Content-Type", "Content-Encoding", "Content-Language",
                "Content-Disposition", "Cache-Control", "Expires"}) {
            String v = request.getHeader(h);
            if (v != null) {
                contentHeaders.put(h, v);
            }
        }
        // 用户元数据 x-amz-meta-*（R5：总量 ≤ 2KB）
        Map<String, String> userMeta = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        java.util.Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String n = names.nextElement();
            if (n != null && n.toLowerCase(Locale.ROOT).startsWith("x-amz-meta-")) {
                userMeta.put(n.substring("x-amz-meta-".length()).toLowerCase(Locale.ROOT),
                        request.getHeader(n));
            }
        }

        // 校验 checksum 头（若有）
        Map<String, String> provided = new HashMap<>();
        String md5B64 = request.getHeader("Content-MD5");
        if (md5B64 != null && !md5B64.isEmpty()) {
            provided.put("MD5", md5B64);
        }
        for (String h : new String[]{"x-amz-checksum-md5", "x-amz-checksum-sha256",
                "x-amz-checksum-crc32", "x-amz-checksum-sha1"}) {
            String v = request.getHeader(h);
            if (v != null && !v.isEmpty()) {
                String algo = h.substring("x-amz-checksum-".length()).toUpperCase(Locale.ROOT);
                if (algo.equals("SHA1")) {
                    algo = "SHA256"; // 简化：Phase 1 内部仅存 SHA256/CRC32/MD5
                }
                provided.put(algo, v);
            }
        }

        // x-amz-tagging（URL 编码的 key=value 对）
        String taggingHeader = request.getHeader("x-amz-tagging");
        Map<String, String> initialTags = parseTaggingHeader(taggingHeader);

        try (InputStream body = wrapChunkedBody(request)) {
            ObjectMetadata meta = storage.putObject(bucket, key, body, contentHeaders,
                    userMeta.isEmpty() ? null : userMeta, provided.isEmpty() ? null : provided,
                    cannedAcl);
            if (!initialTags.isEmpty()) {
                validateTags(initialTags);
                meta.setTags(new TreeMap<>(initialTags));
                storage.updateMeta(bucket, key, meta);
            }
            response.setHeader("ETag", meta.getEtag());
            if (meta.getChecksums() != null) {
                meta.getChecksums().forEach((algo, val) ->
                        response.setHeader("x-amz-checksum-" + algo.toLowerCase(Locale.ROOT), val));
                response.setHeader("x-amz-checksum-type", meta.getChecksumType());
            }
            response.setStatus(HttpServletResponse.SC_OK);
        }
    }

    // ==================== POST (DeleteObjects, Multipart later) ====================

    @org.springframework.web.bind.annotation.RequestMapping(
            value = "/**", method = org.springframework.web.bind.annotation.RequestMethod.POST)
    public void handlePost(HttpServletRequest request, HttpServletResponse response) throws IOException, jakarta.servlet.ServletException {
        S3RequestContext ctx = S3RequestContext.get(request);
        if (!ctx.hasSubResource("delete")) {
            // POST Object（表单上传）的签名在表单字段中，不由 SigV4Verifier 校验；
            // ?delete（DeleteObjects）仍走标准认证。
            request.setAttribute(SIGNING_CTX_ATTR, null);
        } else {
            io.github.letsopen.localoss.auth.SigV4Verifier.SigningContext signingCtx =
                    sigV4.verify(request, ctx, null);
            request.setAttribute(SIGNING_CTX_ATTR, signingCtx);
        }
        if (ctx.hasSubResource("delete")) {
            guardWrite(request, ctx);
            if (!ctx.hasBucket() || ctx.hasKey()) {
                throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                        "?delete applies to bucket only");
            }
            deleteObjects(ctx, request, response);
            return;
        }
        if (ctx.hasSubResource("uploads") && !ctx.hasKey()) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                    "?uploads listing applies to GET; use POST /{bucket}/{key}?uploads to initiate");
        }
        if (ctx.hasSubResource("uploads") && ctx.hasKey()) {
            guardWrite(request, ctx);
            createMultipartUpload(ctx, request, response);
            return;
        }
        if (ctx.hasSubResource("uploadId") && ctx.hasKey()) {
            guardWrite(request, ctx);
            completeMultipartUpload(ctx, request, response);
            return;
        }
        // POST Object（浏览器表单上传， SigV4 policy 签名）
        guardWrite(request, ctx);
        postObjectForm(ctx, request, response);
    }

    /**
     * POST Object：multipart/form-data 表单上传（S3 浏览器直传协议）。
     *
     * <p>表单字段：key（可含 ${filename}）、policy（base64 JSON）、x-amz-algorithm、
     * x-amz-credential、x-amz-date、x-amz-signature、file、success_action_status 等。
     */
    private void postObjectForm(S3RequestContext ctx, HttpServletRequest request,
                                HttpServletResponse response) throws IOException, jakarta.servlet.ServletException {
        var parts = new LinkedHashMap<String, byte[]>();
        String keyPattern = null;
        String successStatus = "204";
        String contentType = "application/octet-stream";
        Map<String, String> userMeta = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        var formParts = request.getParts();
        byte[] fileBytes = null;
        String policy = null;
        String signature = null;
        String credential = null;
        String amzDate = null;
        String algorithm = null;
        String submittedFileName = null;
        for (var part : formParts) {
            String name = part.getName();
            if ("file".equals(name)) {
                fileBytes = part.getInputStream().readAllBytes();
                submittedFileName = part.getSubmittedFileName();
                String submittedCt = part.getContentType();
                if (submittedCt != null && !submittedCt.isEmpty()) {
                    contentType = submittedCt;
                }
            } else {
                byte[] value = part.getInputStream().readAllBytes();
                String valueStr = new String(value, StandardCharsets.UTF_8);
                switch (name) {
                    case "key" -> keyPattern = valueStr;
                    case "policy" -> policy = valueStr;
                    case "x-amz-signature" -> signature = valueStr;
                    case "x-amz-credential" -> credential = valueStr;
                    case "x-amz-date" -> amzDate = valueStr;
                    case "x-amz-algorithm" -> algorithm = valueStr;
                    case "success_action_status" -> successStatus = valueStr;
                    default -> {
                        if (name.startsWith("x-amz-meta-")) {
                            userMeta.put(name.substring("x-amz-meta-".length()).toLowerCase(Locale.ROOT),
                                    valueStr);
                        }
                        parts.put(name, value);
                    }
                }
            }
        }

        if (fileBytes == null) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Missing file part");
        }
        if (!props.getAccessKey().isEmpty()) {
            // 签名校验
            if (policy == null || signature == null || credential == null
                    || amzDate == null || algorithm == null) {
                throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                        "Missing POST policy/signature fields");
            }
            if (!"AWS4-HMAC-SHA256".equals(algorithm)) {
                throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Unsupported algorithm");
            }
            verifyPostPolicy(policy, signature, credential, amzDate);
        }
        if (policy != null) {
            applyPostPolicyConstraints(policy, ctx.getBucket(), fileBytes.length);
        }

        if (keyPattern == null || keyPattern.isEmpty()) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Missing key field");
        }
        String filename = submittedFileName != null && !submittedFileName.isEmpty()
                ? submittedFileName
                : extractFilename(request.getHeader("Content-Disposition"), "upload");
        String key = keyPattern.replace("${filename}", filename);
        if (!ctx.hasKey()) {
            // POST /{bucket}：key 字段必须完整
            key = key.startsWith("/") ? key.substring(1) : key;
        } else {
            // POST /{bucket}/{prefix}：key 字段相对前缀
            key = ctx.getKey() + (key.startsWith("/") ? key.substring(1) : key);
        }

        Map<String, String> headers = Map.of("Content-Type", contentType);
        ObjectMetadata meta = storage.putObject(ctx.getBucket(), key,
                new ByteArrayInputStream(fileBytes), headers,
                userMeta.isEmpty() ? null : userMeta, null,
                aclEvaluator.parseCannedHeader(request.getHeader("x-amz-acl")));

        int status = "200".equals(successStatus) ? 200
                : "201".equals(successStatus) ? 201 : 204;
        if (status == 201) {
            StringBuilder xml = new StringBuilder(512);
            xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.header("PostResponse"));
            xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("  ",
                    "Location", "/" + ctx.getBucket() + "/" + key));
            xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("  ",
                    "Bucket", ctx.getBucket()));
            xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("  ",
                    "Key", key));
            xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("  ",
                    "ETag", meta.getEtag()));
            xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.footer("PostResponse"));
            writeXml(response, 201, xml.toString());
        } else {
            response.setHeader("ETag", meta.getEtag());
            response.setHeader("Location", "/" + ctx.getBucket() + "/" + key);
            response.setStatus(status);
        }
    }

    private void verifyPostPolicy(String policyB64, String signature, String credential,
                                  String amzDate) {
        String[] cred = credential.split("/");
        if (cred.length < 5) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Malformed credential scope");
        }
        if (!props.getAccessKey().equals(cred[0])) {
            throw new S3Exception(S3ErrorCode.INVALID_ACCESS_KEY_ID);
        }
        String date = cred[1];
        String region = cred[2];
        if (!"s3".equals(cred[3]) || !"aws4_request".equals(cred[4])) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Invalid credential scope");
        }
        SigV4Verifier.parseAmzDate(amzDate); // 格式校验
        byte[] key = SigV4Verifier.publicSigningKey(props.getSecretKey(), date, region);
        String computed = SigV4Verifier.publicHmacHex(key, policyB64);
        if (!SigV4VerifierConstantTimeEquals(computed, signature)) {
            throw new S3Exception(S3ErrorCode.SIGNATURE_DOES_NOT_MATCH);
        }
    }

    private boolean SigV4VerifierConstantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** 校验 policy conditions（bucket / starts-with $key / content-length-range） */
    private void applyPostPolicyConstraints(String policyB64, String bucket, long contentLength) {
        try {
            byte[] json = java.util.Base64.getDecoder().decode(policyB64);
            var mapper = props.objectMapperForConfig();
            var root = mapper.readTree(json);
            var conditions = root.get("conditions");
            if (conditions == null || !conditions.isArray()) {
                return;
            }
            for (var cond : conditions) {
                if (cond.isArray() && cond.size() >= 2) {
                    // 数组形式：["eq|starts-with|content-length-range", "$key"/..., value...]
                    String op = cond.get(0).asText();
                    if ("content-length-range".equals(op) && cond.size() >= 3) {
                        long min = cond.get(1).asLong();
                        long max = cond.get(2).asLong();
                        if (contentLength < min || contentLength > max) {
                            throw new S3Exception(S3ErrorCode.ENTITY_TOO_LARGE,
                                    "Content length out of policy range");
                        }
                    }
                    continue;
                }
                if (cond.isObject()) {
                    var bucketNode = cond.get("bucket");
                    if (bucketNode != null && !bucket.equals(bucketNode.asText())) {
                        throw new S3Exception(S3ErrorCode.ACCESS_DENIED,
                                "Policy bucket mismatch");
                    }
                    var lenNode = cond.get("content-length-range");
                    if (lenNode != null && lenNode.isArray() && lenNode.size() == 2) {
                        long min = lenNode.get(0).asLong();
                        long max = lenNode.get(1).asLong();
                        if (contentLength < min || contentLength > max) {
                            throw new S3Exception(S3ErrorCode.ENTITY_TOO_LARGE,
                                    "Content length out of policy range");
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Malformed POST policy");
        }
    }

    private String extractFilename(String contentDisposition, String fallback) {
        if (contentDisposition == null) {
            return fallback;
        }
        int idx = contentDisposition.indexOf("filename=");
        if (idx < 0) {
            return fallback;
        }
        String fn = contentDisposition.substring(idx + "filename=".length());
        return fn.replace("\"", "").split(";")[0].trim();
    }

    // ==================== GET / HEAD ====================

    @org.springframework.web.bind.annotation.RequestMapping(
            value = "/**", method = {
                    org.springframework.web.bind.annotation.RequestMethod.GET,
                    org.springframework.web.bind.annotation.RequestMethod.HEAD})
    public void handleGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        S3RequestContext ctx = S3RequestContext.get(request);
        io.github.letsopen.localoss.auth.SigV4Verifier.SigningContext signingCtx =
                sigV4.verify(request, ctx, null);
        request.setAttribute(SIGNING_CTX_ATTR, signingCtx);
        boolean isHead = "HEAD".equalsIgnoreCase(request.getMethod());

        boolean anonymous = !sigV4.authDisabled()
                && request.getHeader("Authorization") == null
                && !"AWS4-HMAC-SHA256".equals(ctx.queryParam("X-Amz-Algorithm"));
        ctx.setAnonymous(anonymous);
        if (ctx.isRootRequest()) {
            guardRead(request, ctx);
            if (anonymous && !props.isAllowAnonymousRead()) {
                throw new S3Exception(S3ErrorCode.ACCESS_DENIED,
                        "Anonymous access is forbidden for this operation");
            }
            listBuckets(response);
            return;
        }
        String bucket = ctx.getBucket();
        if (!ctx.hasKey()) {
            if (ctx.hasSubResource("location")) {
                guardRead(request, ctx);
                getBucketLocation(bucket, response);
                return;
            }
            if (ctx.hasSubResource("cors")) {
                guardRead(request, ctx);
                getBucketConfigXml(ctx, bucket, "cors", "CORSConfiguration", response);
                return;
            }
            if (ctx.hasSubResource("policy")) {
                guardRead(request, ctx);
                getBucketPolicy(ctx, bucket, response);
                return;
            }
            if (ctx.hasSubResource("publicAccessBlock")) {
                guardRead(request, ctx);
                getBucketConfigXml(ctx, bucket, "publicAccessBlock",
                        "PublicAccessBlockConfiguration", response);
                return;
            }
            if (ctx.hasSubResource("ownershipControls")) {
                guardRead(request, ctx);
                getBucketConfigXml(ctx, bucket, "ownershipControls",
                        "OwnershipControls", response);
                return;
            }
            if (ctx.hasSubResource("requestPayment")) {
                guardRead(request, ctx);
                getRequestPayment(bucket, response);
                return;
            }
            if (ctx.hasSubResource("tagging")) {
                guardRead(request, ctx);
                getBucketTagging(ctx, bucket, response);
                return;
            }
            if (ctx.hasSubResource("lifecycle")) {
                guardRead(request, ctx);
                getLifecycle(ctx, bucket, response);
                return;
            }
            if (ctx.hasSubResource("acl")) {
                guardRead(request, ctx);
                getBucketAcl(ctx, bucket, response);
                return;
            }
        }
        // Bucket-level subresources not yet implemented
        for (String sub : new String[]{"versioning", "versions",
                "website", "encryption", "object-lock",
                "logging", "notification", "replication", "inventory",
                "analytics", "metrics", "accelerate"}) {
            if (ctx.hasSubResource(sub)) {
                if (List.of("tagging", "acl", "policy", "cors", "lifecycle", "website",
                        "encryption", "object-lock", "publicAccessBlock", "ownershipControls",
                        "requestPayment", "logging", "notification", "replication", "inventory",
                        "analytics", "metrics", "accelerate").contains(sub)) {
                    guardRead(request, ctx);
                }
                throw new S3Exception(S3ErrorCode.NOT_IMPLEMENTED,
                        "Bucket subresource ?" + sub + " comes in a later phase");
            }
        }

        if (ctx.hasSubResource("uploadId") && ctx.hasKey()) {
            guardRead(request, ctx);
            listParts(ctx, bucket, response);
            return;
        }
        if (!ctx.hasKey()) {
            if (ctx.hasSubResource("uploads")) {
                guardRead(request, ctx);
                listMultipartUploads(ctx, bucket, response);
                return;
            }
            if (isHead) {
                guardHead(request, ctx);
                headBucket(bucket, response);
            } else {
                guardRead(request, ctx);
                listObjectsPhase1(ctx, bucket, response);
            }
            return;
        }
        getObjectOrHead(ctx, bucket, ctx.getKey(), request, response, isHead);
    }

    private void listBuckets(HttpServletResponse response) throws IOException {
        StringBuilder xml = new StringBuilder(1024);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<ListAllMyBucketsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">\n");
        xml.append("  <Owner><ID>local</ID><DisplayName>local</DisplayName></Owner>\n");
        xml.append("  <Buckets>\n");
        for (String b : storage.listBuckets()) {
            Path dir = keyValidator.bucketDir(b);
            String created = ISO_MILLIS.format(Instant.now());
            try {
                created = ISO_MILLIS.format(java.time.Instant.ofEpochMilli(
                        java.nio.file.Files.readAttributes(dir,
                                java.nio.file.attribute.BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS).creationTime().toInstant().toEpochMilli()));
            } catch (IOException ignored) {
                // fallback above
            }
            xml.append("    <Bucket><Name>").append(xmlEscape(b)).append("</Name>")
                    .append("<CreationDate>").append(created).append("</CreationDate></Bucket>\n");
        }
        xml.append("  </Buckets>\n");
        xml.append("</ListAllMyBucketsResult>\n");
        writeXml(response, HttpServletResponse.SC_OK, xml.toString());
    }

    private void headBucket(String bucket, HttpServletResponse response) {
        if (!storage.bucketExists(bucket)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        response.setHeader("x-amz-bucket-region", props.getRegion());
        response.setStatus(HttpServletResponse.SC_OK);
    }

    /** ListObjects v1 / v2（Phase 2 完整实现） */
    private void listObjectsPhase1(S3RequestContext ctx, String bucket, HttpServletResponse response)
            throws IOException {
        // 匿名 List：仅当桶为 public-read 或全局允许时放行
        if (!sigV4.authDisabled() && isAnonymousRequest(ctx)
                && !props.isAllowAnonymousRead()
                && !"public-read".equals(safeBucketAcl(bucket))) {
            throw new S3Exception(S3ErrorCode.ACCESS_DENIED,
                    "Anonymous access is forbidden for this operation");
        }
        String prefix = ctx.queryParam("prefix") == null ? "" : ctx.queryParam("prefix");
        String encodingType = ctx.queryParam("encoding-type");
        boolean isV2 = "2".equals(ctx.queryParam("list-type"));
        int maxKeys = parseMaxKeys(ctx.queryParam("max-keys"));
        String delimiter = ctx.queryParam("delimiter");

        List<String> allKeys = storage.listAllKeys(bucket, prefix);

        // 排序后应用 marker / start-after / continuation-token
        String marker;
        if (isV2) {
            String contToken = ctx.queryParam("continuation-token");
            marker = contToken != null ? decodeContinuation(contToken)
                    : orDefault(ctx.queryParam("start-after"), "");
        } else {
            marker = orDefault(ctx.queryParam("marker"), "");
        }
        List<String> visible = new java.util.ArrayList<>();
        for (String k : allKeys) {
            if (k.compareTo(marker) > 0) {
                visible.add(k);
            }
        }

        // delimiter 分组（CommonPrefixes）
        java.util.LinkedHashSet<String> commonPrefixes = new java.util.LinkedHashSet<>();
        java.util.List<String> contents = new java.util.ArrayList<>();
        for (String k : visible) {
            if (contents.size() + commonPrefixes.size() >= maxKeys) {
                break;
            }
            if (delimiter != null && !delimiter.isEmpty()) {
                String rest = k.substring(prefix.length());
                int d = rest.indexOf(delimiter);
                if (d >= 0) {
                    String cp = prefix + rest.substring(0, d + delimiter.length());
                    commonPrefixes.add(cp);
                    continue;
                }
            }
            contents.add(k);
        }
        boolean truncated = contents.size() + commonPrefixes.size() < visible.size()
                || (contents.size() + commonPrefixes.size() == maxKeys
                        && contents.size() + commonPrefixes.size() < allKeys.stream()
                                .filter(k -> k.compareTo(marker) > 0).count());

        StringBuilder xml = new StringBuilder(4096);
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.header("ListBucketResult"));
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("  ",
                "Name", bucket));
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("  ",
                "Prefix", maybeEncode(prefix, encodingType)));
        if (delimiter != null) {
            xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("  ",
                    "Delimiter", maybeEncode(delimiter, encodingType)));
        }
        if (isV2) {
            xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("  ",
                    "KeyCount", String.valueOf(contents.size() + commonPrefixes.size())));
        }
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("  ",
                "MaxKeys", String.valueOf(maxKeys)));
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("  ",
                "IsTruncated", String.valueOf(truncated)));
        if (!isV2) {
            xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("  ",
                    "Marker", maybeEncode(marker, encodingType)));
            if (truncated && !contents.isEmpty()) {
                xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("  ",
                        "NextMarker", maybeEncode(contents.get(contents.size() - 1), encodingType)));
            }
        } else {
            if (truncated) {
                String last = contents.isEmpty()
                        ? commonPrefixes.isEmpty() ? marker
                                : commonPrefixes.stream().reduce((a, b) -> b).orElse(marker)
                        : contents.get(contents.size() - 1);
                String token = encodeContinuation(last);
                xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("  ",
                        "NextContinuationToken", token));
            }
        }
        if (encodingType != null) {
            xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("  ",
                    "EncodingType", encodingType));
        }

        Path bucketDir = keyValidator.bucketDir(bucket);
        for (String k : contents) {
            Path p = bucketDir.resolve(k);
            ObjectMetadata meta = storage.getMeta(bucket, k, p);
            xml.append("  <Contents>\n");
            xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("    ",
                    "Key", maybeEncode(k, encodingType)));
            xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("    ",
                    "LastModified", ISO_MILLIS.format(Instant.parse(meta.getLastModified()))));
            xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("    ",
                    "ETag", meta.getEtag()));
            xml.append("    <Size>").append(meta.getSize()).append("</Size>\n");
            xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("    ",
                    "StorageClass", meta.getStorageClass() == null ? "STANDARD" : meta.getStorageClass()));
            xml.append("    <Owner><ID>local</ID><DisplayName>local</DisplayName></Owner>\n");
            xml.append("  </Contents>\n");
        }
        for (String cp : commonPrefixes) {
            xml.append("  <CommonPrefixes>\n");
            xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("    ",
                    "Prefix", maybeEncode(cp, encodingType)));
            xml.append("  </CommonPrefixes>\n");
        }
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.footer("ListBucketResult"));
        writeXml(response, HttpServletResponse.SC_OK, xml.toString());
    }

    private static String orDefault(String s, String def) {
        return s == null ? def : s;
    }

    private static int parseMaxKeys(String s) {
        if (s == null || s.isEmpty()) {
            return 1000;
        }
        try {
            int v = Integer.parseInt(s);
            return Math.max(0, Math.min(v, 1000));
        } catch (NumberFormatException e) {
            return 1000;
        }
    }

    /** continuation-token：base64url(lastKey)（混淆但可逆，符合 S3 行为） */
    private static String encodeContinuation(String lastKey) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(lastKey.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeContinuation(String token) {
        try {
            return new String(java.util.Base64.getUrlDecoder().decode(token),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Invalid continuation-token");
        }
    }

    /** encoding-type=url：RFC3986 编码 key/prefix（S3 行为） */
    private static String maybeEncode(String value, String encodingType) {
        if (value == null || !"url".equalsIgnoreCase(encodingType)) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length() * 3);
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~';
            if (unreserved || c == '/') {
                out.append(c);
            } else {
                out.append('%');
                out.append(String.format("%02X", b & 0xFF));
            }
        }
        return out.toString();
    }

    private void getObjectOrHead(S3RequestContext ctx, String bucket, String key,
                                 HttpServletRequest request, HttpServletResponse response,
                                 boolean isHead) throws IOException {
        // ACL 匿名读检查：public-read 对象在全局禁读模式下也拒绝（access-mode 优先）；
        // 在签名要求模式下，public-read 对象允许匿名 GET/HEAD
        boolean anonymous = !sigV4.authDisabled()
                && request.getHeader("Authorization") == null
                && !"AWS4-HMAC-SHA256".equals(ctx.queryParam("X-Amz-Algorithm"));
        if (props.getAccessMode() != io.github.letsopen.localoss.config.AccessMode.DISABLED) {
            Path pre = keyValidator.toFilePath(bucket, key);
            boolean dirObj = key.endsWith("/");
            if (storage.objectExists(pre, dirObj)) {
                ObjectMetadata aclMeta = storage.getMeta(bucket, key, pre);
                boolean isPublicRead = aclEvaluator.isPublicRead(aclMeta.getAcl(),
                        props.isAllowAnonymousRead());
                if (anonymous) {
                    if (isPublicRead && props.getAccessMode().allowRead()) {
                        if (isHead) {
                            guardHead(request, ctx);
                        }
                        getObjectOrHeadInner(ctx, bucket, key, request, response, isHead);
                        return;
                    }
                    throw new S3Exception(S3ErrorCode.ACCESS_DENIED,
                            "Anonymous access is forbidden for this operation");
                }
            }
        }
        if (isHead) {
            guardHead(request, ctx);
        } else {
            guardRead(request, ctx);
        }
        getObjectOrHeadInner(ctx, bucket, key, request, response, isHead);
    }

    private void getObjectOrHeadInner(S3RequestContext ctx, String bucket, String key,
                                      HttpServletRequest request, HttpServletResponse response,
                                      boolean isHead) throws IOException {
        if (ctx.hasSubResource("tagging") && !isHead) {
            guardRead(request, ctx);
            getObjectTagging(ctx, bucket, response);
            return;
        }
        if (ctx.hasSubResource("acl") && !isHead) {
            guardRead(request, ctx);
            getObjectAcl(ctx, bucket, response);
            return;
        }
        if (ctx.hasSubResource("attributes")) {
            throw new S3Exception(S3ErrorCode.NOT_IMPLEMENTED,
                    "Object subresource ?attributes comes in a later phase");
        }
        if (!storage.bucketExists(bucket)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET);
        }
        Path target = keyValidator.toFilePath(bucket, key);
        keyValidator.checkSymlinkEscape(keyValidator.bucketDir(bucket), target);

        boolean directoryObject = key.endsWith("/");
        if (!storage.objectExists(target, directoryObject)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_KEY);
        }
        ObjectMetadata meta = storage.getMeta(bucket, key, target);

        // 响应头覆盖参数（仅签名请求允许；Phase 2 先无条件支持，Phase 3 认证完善后收紧）
        String overrideContentType = ctx.queryParam("response-content-type");
        String overrideContentDisposition = ctx.queryParam("response-content-disposition");
        String overrideCacheControl = ctx.queryParam("response-cache-control");
        String overrideContentLanguage = ctx.queryParam("response-content-language");
        String overrideContentEncoding = ctx.queryParam("response-content-encoding");
        String overrideExpires = ctx.queryParam("response-expires");

        // Range 解析（单段；Phase 1 简版）
        long offset = 0;
        long length = meta.getSize();
        String range = request.getHeader("Range");
        boolean isRange = false;
        if (range != null && range.startsWith("bytes=")) {
            String spec = range.substring("bytes=".length());
            int dash = spec.indexOf('-');
            if (dash < 0) {
                throw new S3Exception(S3ErrorCode.INVALID_RANGE);
            }
            String startStr = spec.substring(0, dash).trim();
            String endStr = spec.substring(dash + 1).trim();
            try {
                if (startStr.isEmpty() && !endStr.isEmpty()) {
                    // suffix: bytes=-N
                    long n = Long.parseLong(endStr);
                    offset = Math.max(0, meta.getSize() - n);
                    length = meta.getSize() - offset;
                } else if (!startStr.isEmpty()) {
                    offset = Long.parseLong(startStr);
                    length = endStr.isEmpty()
                            ? meta.getSize() - offset
                            : Math.min(Long.parseLong(endStr) - offset + 1, meta.getSize() - offset);
                } else {
                    throw new S3Exception(S3ErrorCode.INVALID_RANGE,
                            "Invalid Range header: " + range);
                }
            } catch (NumberFormatException e) {
                throw new S3Exception(S3ErrorCode.INVALID_RANGE,
                        "Invalid Range header: " + range);
            }
            if (offset >= meta.getSize() || length < 0) {
                throw new S3Exception(S3ErrorCode.INVALID_RANGE,
                        "Requested range not satisfiable: " + range);
            }
            isRange = true;
        }

        response.setHeader("ETag", meta.getEtag());
        response.setHeader("Last-Modified", RFC1123_GMT.format(Instant.parse(meta.getLastModified())));
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("x-amz-request-id", ctx.getRequestId());
        if (meta.getTags() != null && !meta.getTags().isEmpty()) {
            response.setHeader("x-amz-tagging-count", String.valueOf(meta.getTags().size()));
        }
        if (meta.getChecksums() != null && meta.getChecksums().containsKey("SHA256")) {
            response.setHeader("x-amz-checksum-sha256", meta.getChecksums().get("SHA256"));
        }
        if (meta.getChecksumType() != null) {
            response.setHeader("x-amz-checksum-type", meta.getChecksumType());
        }
        if (overrideContentType != null) {
            response.setHeader("Content-Type", overrideContentType);
        } else if (meta.getContentType() != null) {
            response.setContentType(meta.getContentType());
        }
        if (meta.getContentDisposition() != null || overrideContentDisposition != null) {
            response.setHeader("Content-Disposition", overrideContentDisposition != null
                    ? overrideContentDisposition : meta.getContentDisposition());
        }
        if (meta.getCacheControl() != null || overrideCacheControl != null) {
            response.setHeader("Cache-Control", overrideCacheControl != null
                    ? overrideCacheControl : meta.getCacheControl());
        }
        if (overrideContentLanguage != null) {
            response.setHeader("Content-Language", overrideContentLanguage);
        } else if (meta.getContentLanguage() != null) {
            response.setHeader("Content-Language", meta.getContentLanguage());
        }
        if (overrideContentEncoding != null) {
            response.setHeader("Content-Encoding", overrideContentEncoding);
        } else if (meta.getContentEncoding() != null) {
            response.setHeader("Content-Encoding", meta.getContentEncoding());
        }
        if (overrideExpires != null) {
            response.setHeader("Expires", overrideExpires);
        }
        // 用户元数据回显
        if (meta.getUserMetadata() != null) {
            meta.getUserMetadata().forEach((k, v) -> response.setHeader("x-amz-meta-" + k, v));
        }

        if (isHead) {
            response.setContentLengthLong(length);
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        if (isRange) {
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader("Content-Range",
                    "bytes " + offset + "-" + (offset + length - 1) + "/" + meta.getSize());
        } else {
            response.setStatus(HttpServletResponse.SC_OK);
        }
        response.setContentLengthLong(length);

        if (directoryObject) {
            return;
        }
        try (var obj = storage.openObject(target, offset, length);
             OutputStream out = response.getOutputStream()) {
            transfer(obj.stream(), out, length);
        }
    }

    private void transfer(InputStream in, OutputStream out, long len) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long remaining = len;
        while (remaining > 0) {
            int want = (int) Math.min(buf.length, remaining);
            int n = in.read(buf, 0, want);
            if (n < 0) {
                break;
            }
            out.write(buf, 0, n);
            remaining -= n;
        }
        out.flush();
    }

    // ==================== DELETE ====================

    @org.springframework.web.bind.annotation.RequestMapping(
            value = "/**", method = org.springframework.web.bind.annotation.RequestMethod.DELETE)
    public void handleDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
        S3RequestContext ctx = S3RequestContext.get(request);
        io.github.letsopen.localoss.auth.SigV4Verifier.SigningContext signingCtx =
                sigV4.verify(request, ctx, null);
        request.setAttribute(SIGNING_CTX_ATTR, signingCtx);
        ctx.setAnonymous(signingCtx == io.github.letsopen.localoss.auth.SigV4Verifier.ANONYMOUS);
        guardDelete(request, ctx);
        if (ctx.hasSubResource("uploadId") && ctx.hasKey()) {
            multipartStore.abort(ctx.getBucket(), ctx.getKey(), ctx.queryParam("uploadId"));
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        if (ctx.hasKey() && ctx.hasSubResource("tagging")) {
            deleteObjectTagging(ctx, response);
            return;
        }
        if (!ctx.hasKey() && ctx.hasSubResource("tagging")) {
            guardDelete(request, ctx);
            bucketTaggingDelete(ctx, response);
            return;
        }
        if (!ctx.hasKey() && ctx.hasSubResource("lifecycle")) {
            guardDelete(request, ctx);
            deleteLifecycle(ctx.getBucket(), response);
            return;
        }
        if (!ctx.hasKey() && (ctx.hasSubResource("cors") || ctx.hasSubResource("policy")
                || ctx.hasSubResource("publicAccessBlock") || ctx.hasSubResource("ownershipControls"))) {
            guardDelete(request, ctx);
            String sub = ctx.hasSubResource("cors") ? "cors"
                    : ctx.hasSubResource("policy") ? "policy"
                    : ctx.hasSubResource("publicAccessBlock") ? "publicAccessBlock"
                    : "ownershipControls";
            deleteBucketConfig(ctx.getBucket(), sub, response);
            return;
        }
        if (!ctx.hasBucket()) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Missing bucket");
        }
        if (!ctx.hasKey()) {
            storage.deleteBucket(ctx.getBucket());
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        storage.deleteObject(ctx.getBucket(), ctx.getKey());
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    // ==================== Multipart (Phase 3) ====================

    private void createMultipartUpload(S3RequestContext ctx, HttpServletRequest request,
                                       HttpServletResponse response) throws IOException {
        Map<String, String> contentHeaders = new HashMap<>();
        for (String h : new String[]{"Content-Type", "Content-Encoding", "Content-Language",
                "Content-Disposition", "Cache-Control", "Expires"}) {
            String v = request.getHeader(h);
            if (v != null) {
                contentHeaders.put(h, v);
            }
        }
        Map<String, String> userMeta = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        java.util.Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String n = names.nextElement();
            if (n != null && n.toLowerCase(Locale.ROOT).startsWith("x-amz-meta-")) {
                userMeta.put(n.substring("x-amz-meta-".length()).toLowerCase(Locale.ROOT),
                        request.getHeader(n));
            }
        }
        String uploadId = multipartStore.createUpload(ctx.getBucket(), ctx.getKey(),
                contentHeaders, userMeta.isEmpty() ? null : userMeta);
        StringBuilder xml = new StringBuilder(512);
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter
                .header("InitiateMultipartUploadResult"));
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("  ",
                "Bucket", ctx.getBucket()));
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("  ",
                "Key", ctx.getKey()));
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("  ",
                "UploadId", uploadId));
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter
                .footer("InitiateMultipartUploadResult"));
        writeXml(response, HttpServletResponse.SC_OK, xml.toString());
    }

    private void uploadPart(S3RequestContext ctx, HttpServletRequest request,
                            HttpServletResponse response) throws IOException {
        String uploadId = ctx.queryParam("uploadId");
        String partNumberRaw = ctx.queryParam("partNumber");
        int partNumber;
        try {
            partNumber = Integer.parseInt(partNumberRaw);
        } catch (NumberFormatException e) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Invalid partNumber");
        }
        try (InputStream body = request.getInputStream()) {
            var part = multipartStore.uploadPart(ctx.getBucket(), ctx.getKey(),
                    uploadId, partNumber, body);
            response.setHeader("ETag", part.etag());
            response.setStatus(HttpServletResponse.SC_OK);
        }
    }

    private void completeMultipartUpload(S3RequestContext ctx, HttpServletRequest request,
                                         HttpServletResponse response) throws IOException {
        String uploadId = ctx.queryParam("uploadId");
        byte[] body = request.getInputStream().readAllBytes();
        var parsed = io.github.letsopen.localoss.xml.S3XmlReader.parse(body);
        List<Integer> partNumbers = new java.util.ArrayList<>();
        for (String raw : parsed.list("CompleteMultipartUpload/Part/PartNumber")) {
            try {
                partNumbers.add(Integer.parseInt(raw.trim()));
            } catch (NumberFormatException e) {
                throw new S3Exception(S3ErrorCode.MALFORMED_XML,
                        "Invalid PartNumber: " + sanitizeForMsg(raw));
            }
        }
        if (partNumbers.isEmpty()) {
            throw new S3Exception(S3ErrorCode.MALFORMED_XML,
                    "No Part/PartNumber in CompleteMultipartUpload");
        }
        ObjectMetadata meta = multipartStore.complete(ctx.getBucket(), ctx.getKey(),
                uploadId, partNumbers);

        StringBuilder xml = new StringBuilder(1024);
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter
                .header("CompleteMultipartUploadResult"));
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("  ",
                "Location", "/" + ctx.getBucket() + "/" + ctx.getKey()));
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("  ",
                "Bucket", ctx.getBucket()));
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("  ",
                "Key", ctx.getKey()));
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("  ",
                "ETag", meta.getEtag()));
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter
                .footer("CompleteMultipartUploadResult"));
        writeXml(response, HttpServletResponse.SC_OK, xml.toString());
    }

    private void listParts(S3RequestContext ctx, String bucket,
                           HttpServletResponse response) throws IOException {
        String uploadId = ctx.queryParam("uploadId");
        var parts = multipartStore.listParts(bucket, ctx.getKey(), uploadId);
        StringBuilder xml = new StringBuilder(2048);
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.header("ListPartsResult"));
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("  ",
                "Bucket", bucket));
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("  ",
                "Key", ctx.getKey()));
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("  ",
                "UploadId", uploadId));
        for (var p : parts) {
            xml.append("  <Part>\n");
            xml.append("    <PartNumber>").append(p.number()).append("</PartNumber>\n");
            xml.append("    <LastModified>").append(p.lastModified()).append("</LastModified>\n");
            xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("    ",
                    "ETag", p.etag()));
            xml.append("    <Size>").append(p.size()).append("</Size>\n");
            xml.append("  </Part>\n");
        }
        xml.append("  <IsTruncated>false</IsTruncated>\n");
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.footer("ListPartsResult"));
        writeXml(response, HttpServletResponse.SC_OK, xml.toString());
    }

    private void listMultipartUploads(S3RequestContext ctx, String bucket,
                                      HttpServletResponse response) throws IOException {
        var uploads = multipartStore.listUploads(bucket);
        StringBuilder xml = new StringBuilder(2048);
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter
                .header("ListMultipartUploadsResult"));
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("  ",
                "Bucket", bucket));
        for (var u : uploads) {
            xml.append("  <Upload>\n");
            xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("    ",
                    "Key", u.key()));
            xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("    ",
                    "UploadId", u.uploadId()));
            xml.append("    <Initiated>").append(u.initiated()).append("</Initiated>\n");
            xml.append("  </Upload>\n");
        }
        xml.append("  <IsTruncated>false</IsTruncated>\n");
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter
                .footer("ListMultipartUploadsResult"));
        writeXml(response, HttpServletResponse.SC_OK, xml.toString());
    }

    // ==================== Tagging (Phase 4) ====================

    /** 标签数量与长度校验（S3：最多 50 个，key 1-128 字节，value 0-256 字节） */
    private void validateTags(Map<String, String> tags) {
        if (tags == null) {
            return;
        }
        if (tags.size() > 50) {
            throw new S3Exception(S3ErrorCode.INVALID_TAG, "Tag set cannot have more than 50 tags");
        }
        for (var e : tags.entrySet()) {
            int keyBytes = e.getKey().getBytes(StandardCharsets.UTF_8).length;
            int valBytes = e.getValue() == null ? 0
                    : e.getValue().getBytes(StandardCharsets.UTF_8).length;
            if (keyBytes < 1 || keyBytes > 128) {
                throw new S3Exception(S3ErrorCode.INVALID_TAG, "Tag key must be 1-128 bytes");
            }
            if (valBytes > 256) {
                throw new S3Exception(S3ErrorCode.INVALID_TAG, "Tag value must be 0-256 bytes");
            }
        }
    }

    private void getBucketTagging(S3RequestContext ctx, String bucket,
                                  HttpServletResponse response) throws IOException {
        if (!storage.bucketExists(bucket)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET);
        }
        Path bucketDir = keyValidator.bucketDir(bucket);
        Map<String, String> tags = storage.getBucketTags(bucketDir);
        writeTaggingXml(response, tags);
    }

    private void putBucketTagging(S3RequestContext ctx, HttpServletRequest request,
                                  HttpServletResponse response) throws IOException {
        if (!storage.bucketExists(ctx.getBucket())) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET);
        }
        Map<String, String> tags = parseTaggingXml(request);
        validateTags(tags);
        storage.setBucketTags(keyValidator.bucketDir(ctx.getBucket()), tags);
        response.setStatus(HttpServletResponse.SC_OK);
    }

    private void bucketTaggingDelete(S3RequestContext ctx, HttpServletResponse response) {
        if (!storage.bucketExists(ctx.getBucket())) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET);
        }
        storage.setBucketTags(keyValidator.bucketDir(ctx.getBucket()), Map.of());
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    private void getObjectTagging(S3RequestContext ctx, String bucket,
                                  HttpServletResponse response) throws IOException {
        Path target = keyValidator.toFilePath(bucket, ctx.getKey());
        keyValidator.checkSymlinkEscape(keyValidator.bucketDir(bucket), target);
        if (!storage.objectExists(target, ctx.getKey().endsWith("/"))) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_KEY);
        }
        ObjectMetadata meta = storage.getMeta(bucket, ctx.getKey(), target);
        writeTaggingXml(response, meta.getTags());
    }

    private void putObjectTagging(S3RequestContext ctx, HttpServletRequest request,
                                  HttpServletResponse response) throws IOException {
        String bucket = ctx.getBucket();
        String key = ctx.getKey();
        Path target = keyValidator.toFilePath(bucket, key);
        if (!storage.objectExists(target, key.endsWith("/"))) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_KEY);
        }
        Map<String, String> tags = parseTaggingXml(request);
        validateTags(tags);
        ObjectMetadata meta = storage.getMeta(bucket, key, target);
        meta.setTags(tags.isEmpty() ? null : new TreeMap<>(tags));
        storage.updateMeta(bucket, key, meta);
        response.setStatus(HttpServletResponse.SC_OK);
    }

    private void deleteObjectTagging(S3RequestContext ctx, HttpServletResponse response) {
        String bucket = ctx.getBucket();
        String key = ctx.getKey();
        Path target = keyValidator.toFilePath(bucket, key);
        if (!storage.objectExists(target, key.endsWith("/"))) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_KEY);
        }
        ObjectMetadata meta = storage.getMeta(bucket, key, target);
        meta.setTags(null);
        storage.updateMeta(bucket, key, meta);
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    private void writeTaggingXml(HttpServletResponse response, Map<String, String> tags)
            throws IOException {
        StringBuilder xml = new StringBuilder(512);
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.header("Tagging"));
        xml.append("  <TagSet>\n");
        if (tags != null) {
            for (var e : tags.entrySet()) {
                xml.append("    <Tag>\n");
                xml.append(io.github.letsopen.localoss.xml.S3XmlWriter
                        .elementIndented("      ", "Key", e.getKey()));
                xml.append(io.github.letsopen.localoss.xml.S3XmlWriter
                        .elementIndented("      ", "Value", e.getValue()));
                xml.append("    </Tag>\n");
            }
        }
        xml.append("  </TagSet>\n");
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.footer("Tagging"));
        writeXml(response, HttpServletResponse.SC_OK, xml.toString());
    }

    /** 解析 x-amz-tagging 头：整体 URL decode 后按 & 和 = 拆分 */
    static Map<String, String> parseTaggingHeader(String header) {
        Map<String, String> tags = new java.util.LinkedHashMap<>();
        if (header == null || header.isEmpty()) {
            return tags;
        }
        try {
            String decoded = java.net.URLDecoder.decode(header, StandardCharsets.UTF_8);
            for (String pair : decoded.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                if (eq < 0) {
                    tags.put(pair, "");
                } else {
                    tags.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
            }
        } catch (IllegalArgumentException e) {
            throw new S3Exception(S3ErrorCode.INVALID_TAG, "Malformed x-amz-tagging header");
        }
        return tags;
    }

    /** 解析 Tagging XML：<Tag><Key>k</Key><Value>v</Value></Tag> */
    private Map<String, String> parseTaggingXml(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readAllBytes();
        if (body.length == 0) {
            throw new S3Exception(S3ErrorCode.MALFORMED_XML, "Empty tagging XML");
        }
        var parsed = io.github.letsopen.localoss.xml.S3XmlReader.parse(body);
        List<String> keys = parsed.list("Tagging/TagSet/Tag/Key");
        List<String> values = parsed.list("Tagging/TagSet/Tag/Value");
        if (keys.isEmpty()) {
            return Map.of();
        }
        Map<String, String> tags = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            tags.put(keys.get(i), i < values.size() ? values.get(i) : "");
        }
        return tags;
    }

    // ==================== Bucket configs (Phase 5) ====================

    private void getBucketLocation(String bucket, HttpServletResponse response) throws IOException {
        if (!storage.bucketExists(bucket)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET);
        }
        // us-east-1 返回空 LocationConstraint（S3 规范）
        String region = props.getRegion();
        StringBuilder xml = new StringBuilder(256);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        if ("us-east-1".equals(region)) {
            xml.append("<LocationConstraint xmlns=\"")
                    .append(io.github.letsopen.localoss.xml.S3XmlWriter.NS).append("\"/>\n");
        } else {
            xml.append("<LocationConstraint xmlns=\"")
                    .append(io.github.letsopen.localoss.xml.S3XmlWriter.NS).append("\">")
                    .append(region).append("</LocationConstraint>\n");
        }
        writeXml(response, HttpServletResponse.SC_OK, xml.toString());
    }

    /** 通用桶级 XML 配置读接口（cors / publicAccessBlock / ownershipControls）：存取原样往返 */
    private void getBucketConfigXml(S3RequestContext ctx, String bucket, String field,
                                    String rootElement, HttpServletResponse response)
            throws IOException {
        if (!storage.bucketExists(bucket)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET);
        }
        var cfg = storage.getBucketConfig(keyValidator.bucketDir(bucket));
        Object value = cfg.get(field);
        if (!(value instanceof String rawXml) || rawXml.isBlank()) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET,
                    rootElement + " configuration does not exist");
        }
        // 原样返回 PUT 时的 XML（含 namespace）；若为旧格式（Map）则视为不存在
        writeXml(response, HttpServletResponse.SC_OK, rawXml);
    }

    private void putBucketConfigXml(S3RequestContext ctx, String bucket, String field,
                                    HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (!storage.bucketExists(bucket)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET);
        }
        byte[] body = request.getInputStream().readAllBytes();
        io.github.letsopen.localoss.xml.S3XmlReader.parse(body); // 校验 XML 合法性
        String xml = new String(body, StandardCharsets.UTF_8);
        // 规范化：若缺少 namespace，补上 S3 namespace（客户端兼容）
        if (!xml.contains("xmlns=")) {
            xml = xml.replaceFirst("<([A-Za-z]+)",
                    "<$1 xmlns=\"" + io.github.letsopen.localoss.xml.S3XmlWriter.NS + "\"");
        }
        var cfg = storage.getBucketConfig(keyValidator.bucketDir(bucket));
        cfg.put(field, xml);
        storage.writeBucketConfig(keyValidator.bucketDir(bucket), cfg);
        response.setStatus(HttpServletResponse.SC_OK);
    }

    private void deleteBucketConfig(String bucket, String field,
                                    HttpServletResponse response) throws IOException {
        if (!storage.bucketExists(bucket)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET);
        }
        var cfg = storage.getBucketConfig(keyValidator.bucketDir(bucket));
        cfg.remove(field);
        storage.writeBucketConfig(keyValidator.bucketDir(bucket), cfg);
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    private void getBucketPolicy(S3RequestContext ctx, String bucket,
                                 HttpServletResponse response) throws IOException {
        if (!storage.bucketExists(bucket)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET);
        }
        var cfg = storage.getBucketConfig(keyValidator.bucketDir(bucket));
        Object policy = cfg.get("policy");
        if (policy == null) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET, "Bucket policy does not exist");
        }
        String json = props.objectMapperForConfig().writeValueAsString(policy);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        response.setContentType("application/json");
        response.setContentLengthLong(body.length);
        response.setStatus(HttpServletResponse.SC_OK);
        response.getOutputStream().write(body);
    }

    private void putBucketPolicy(S3RequestContext ctx, HttpServletRequest request,
                                 HttpServletResponse response) throws IOException {
        if (!storage.bucketExists(ctx.getBucket())) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET);
        }
        byte[] body = request.getInputStream().readAllBytes();
        try {
            props.objectMapperForConfig().readValue(body, Map.class);
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.MALFORMED_POLICY, "Invalid policy JSON");
        }
        var cfg = storage.getBucketConfig(keyValidator.bucketDir(ctx.getBucket()));
        cfg.put("policy", props.objectMapperForConfig().readValue(body, Map.class));
        storage.writeBucketConfig(keyValidator.bucketDir(ctx.getBucket()), cfg);
        response.setStatus(HttpServletResponse.SC_OK);
    }

    private void getRequestPayment(String bucket, HttpServletResponse response) throws IOException {
        if (!storage.bucketExists(bucket)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET);
        }
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<RequestPaymentConfiguration xmlns=\""
                + io.github.letsopen.localoss.xml.S3XmlWriter.NS + "\">\n"
                + "  <Payer>BucketOwner</Payer>\n"
                + "</RequestPaymentConfiguration>\n";
        writeXml(response, HttpServletResponse.SC_OK, xml.toString());
    }

    // ==================== ACL (Phase: 有限执行) ====================

    private void putBucketAcl(S3RequestContext ctx, HttpServletRequest request,
                              HttpServletResponse response) throws IOException {
        if (!storage.bucketExists(ctx.getBucket())) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET);
        }
        String canned = aclEvaluator.parseCannedHeader(request.getHeader("x-amz-acl"));
        if (canned == null) {
            // XML ACL body：解析并要求包含 public-read 授权（AllUsers READ），
            // 其余 grant 视为 private
            byte[] body = request.getInputStream().readAllBytes();
            canned = parseAclXml(body);
        }
        storage.setBucketAcl(keyValidator.bucketDir(ctx.getBucket()), canned);
        response.setStatus(HttpServletResponse.SC_OK);
    }

    private void getBucketAcl(S3RequestContext ctx, String bucket,
                              HttpServletResponse response) throws IOException {
        if (!storage.bucketExists(bucket)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET);
        }
        String canned = storage.getBucketAcl(keyValidator.bucketDir(bucket));
        writeAclXml(response, canned == null ? "private" : canned);
    }

    private void putObjectAcl(S3RequestContext ctx, HttpServletRequest request,
                              HttpServletResponse response) throws IOException {
        String bucket = ctx.getBucket();
        String key = ctx.getKey();
        Path target = keyValidator.toFilePath(bucket, key);
        if (!storage.objectExists(target, key.endsWith("/"))) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_KEY);
        }
        String canned = aclEvaluator.parseCannedHeader(request.getHeader("x-amz-acl"));
        if (canned == null) {
            byte[] body = request.getInputStream().readAllBytes();
            canned = parseAclXml(body);
        }
        ObjectMetadata meta = storage.getMeta(bucket, key, target);
        meta.setAcl(canned);
        storage.updateMeta(bucket, key, meta);
        response.setStatus(HttpServletResponse.SC_OK);
    }

    private void getObjectAcl(S3RequestContext ctx, String bucket,
                              HttpServletResponse response) throws IOException {
        Path target = keyValidator.toFilePath(bucket, ctx.getKey());
        keyValidator.checkSymlinkEscape(keyValidator.bucketDir(bucket), target);
        if (!storage.objectExists(target, ctx.getKey().endsWith("/"))) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_KEY);
        }
        ObjectMetadata meta = storage.getMeta(bucket, ctx.getKey(), target);
        writeAclXml(response, meta.getAcl() == null ? "private" : meta.getAcl());
    }

    /** 解析 AccessControlPolicy XML：仅识别 AllUsers READ → public-read */
    private String parseAclXml(byte[] body) {
        if (body == null || body.length == 0) {
            return "private";
        }
        var parsed = io.github.letsopen.localoss.xml.S3XmlReader.parse(body);
        List<String> grantees = parsed.list("AccessControlPolicy/AccessControlList/Grant/Grantee/URI");
        List<String> permissions = parsed.list("AccessControlPolicy/AccessControlList/Grant/Permission");
        for (int i = 0; i < permissions.size(); i++) {
            if ("READ".equalsIgnoreCase(permissions.get(i))
                    && i < grantees.size()
                    && grantees.get(i).contains("AllUsers")) {
                return "public-read";
            }
        }
        return "private";
    }

    private void writeAclXml(HttpServletResponse response, String canned) throws IOException {
        boolean publicRead = "public-read".equals(canned);
        StringBuilder xml = new StringBuilder(512);
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.header("AccessControlPolicy"));
        xml.append("  <Owner><ID>local</ID><DisplayName>local</DisplayName></Owner>\n");
        xml.append("  <AccessControlList>\n");
        xml.append("    <Grant>\n");
        xml.append("      <Grantee xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:type=\"CanonicalUser\">\n");
        xml.append("        <ID>local</ID><DisplayName>local</DisplayName>\n");
        xml.append("      </Grantee>\n");
        xml.append("      <Permission>FULL_CONTROL</Permission>\n");
        xml.append("    </Grant>\n");
        if (publicRead) {
            xml.append("    <Grant>\n");
            xml.append("      <Grantee xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:type=\"Group\">\n");
            xml.append("        <URI>http://acs.amazonaws.com/groups/global/AllUsers</URI>\n");
            xml.append("      </Grantee>\n");
            xml.append("      <Permission>READ</Permission>\n");
            xml.append("    </Grant>\n");
        }
        xml.append("  </AccessControlList>\n");
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.footer("AccessControlPolicy"));
        writeXml(response, HttpServletResponse.SC_OK, xml.toString());
    }

    private boolean isAnonymousRequest(S3RequestContext ctx) {
        // handleGet 已计算 anonymous；此处基于凭证字段判断（预签名在 ctx）
        return ctx.getAnonymous() != null && ctx.getAnonymous();
    }

    private String safeBucketAcl(String bucket) {
        try {
            return storage.getBucketAcl(keyValidator.bucketDir(bucket));
        } catch (Exception e) {
            return null;
        }
    }

    private String sanitizeForMsg(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[\r\n]", "_");
    }

    private Map<String, String> headerMap(HttpServletRequest request) {
        Map<String, String> out = new HashMap<>();
        java.util.Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String n = names.nextElement();
            out.put(n.toLowerCase(Locale.ROOT), request.getHeader(n) == null ? "" : request.getHeader(n));
        }
        return out;
    }

    // ==================== Lifecycle (有限子集) ====================

    private void putLifecycle(S3RequestContext ctx, HttpServletRequest request,
                              HttpServletResponse response) throws IOException {
        if (!storage.bucketExists(ctx.getBucket())) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET);
        }
        byte[] body = request.getInputStream().readAllBytes();
        var parsed = io.github.letsopen.localoss.xml.S3XmlReader.parse(body);
        List<String> ids = parsed.list("LifecycleConfiguration/Rule/ID");
        List<String> prefixes = parsed.list("LifecycleConfiguration/Rule/Filter/Prefix");
        List<String> statuses = parsed.list("LifecycleConfiguration/Rule/Status");
        List<String> expDays = parsed.list("LifecycleConfiguration/Rule/Expiration/Days");
        List<String> expDates = parsed.list("LifecycleConfiguration/Rule/Expiration/Date");
        List<String> abortDays = parsed.list(
                "LifecycleConfiguration/Rule/AbortIncompleteMultipartUpload/DaysAfterInitiation");
        if (ids.isEmpty()) {
            throw new S3Exception(S3ErrorCode.MALFORMED_XML, "No Rule in LifecycleConfiguration");
        }
        // 规则数量一致性（Filter/Prefix 可选）
        List<Map<String, Object>> rules = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            String status = i < statuses.size() ? statuses.get(i) : "Enabled";
            if ("Disabled".equalsIgnoreCase(status)) {
                continue;
            }
            Map<String, Object> rule = new java.util.LinkedHashMap<>();
            rule.put("id", ids.get(i));
            rule.put("prefix", i < prefixes.size() ? prefixes.get(i) : "");
            if (i < expDays.size() && !expDays.get(i).isBlank()) {
                try {
                    rule.put("expirationDays", Integer.parseInt(expDays.get(i).trim()));
                } catch (NumberFormatException e) {
                    throw new S3Exception(S3ErrorCode.MALFORMED_XML,
                            "Invalid Expiration Days");
                }
            }
            if (i < expDates.size() && !expDates.get(i).isBlank()) {
                // S3 Date 形如 2027-01-01T00:00:00.000Z
                rule.put("expirationDate", expDates.get(i).trim());
            }
            if (i < abortDays.size() && !abortDays.get(i).isBlank()) {
                try {
                    rule.put("abortIncompleteMpuDays", Integer.parseInt(abortDays.get(i).trim()));
                } catch (NumberFormatException e) {
                    throw new S3Exception(S3ErrorCode.MALFORMED_XML,
                            "Invalid DaysAfterInitiation");
                }
            }
            if (rule.containsKey("expirationDays") || rule.containsKey("expirationDate")
                    || rule.containsKey("abortIncompleteMpuDays")) {
                rules.add(rule);
            }
        }
        var cfg = storage.getBucketConfig(keyValidator.bucketDir(ctx.getBucket()));
        cfg.put("lifecycle", rules);
        storage.writeBucketConfig(keyValidator.bucketDir(ctx.getBucket()), cfg);
        response.setStatus(HttpServletResponse.SC_OK);
    }

    private void getLifecycle(S3RequestContext ctx, String bucket,
                              HttpServletResponse response) throws IOException {
        if (!storage.bucketExists(bucket)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET);
        }
        Object rules = storage.getBucketConfig(keyValidator.bucketDir(bucket)).get("lifecycle");
        if (rules == null || (rules instanceof List<?> l && l.isEmpty())) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET,
                    "Bucket lifecycle configuration does not exist");
        }
        StringBuilder xml = new StringBuilder(1024);
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.header("LifecycleConfiguration"));
        for (Object o : (List<?>) rules) {
            @SuppressWarnings("unchecked")
            Map<String, Object> rule = (Map<String, Object>) o;
            xml.append("  <Rule>\n");
            xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.elementIndented("    ",
                    "ID", String.valueOf(rule.get("id"))));
            String prefix = String.valueOf(rule.getOrDefault("prefix", ""));
            xml.append("    <Filter><Prefix>").append(prefix).append("</Prefix></Filter>\n");
            xml.append("    <Status>Enabled</Status>\n");
            if (rule.containsKey("expirationDays")) {
                xml.append("    <Expiration><Days>").append(rule.get("expirationDays"))
                        .append("</Days></Expiration>\n");
            } else if (rule.containsKey("expirationDate")) {
                xml.append("    <Expiration><Date>").append(rule.get("expirationDate"))
                        .append("</Date></Expiration>\n");
            }
            if (rule.containsKey("abortIncompleteMpuDays")) {
                xml.append("    <AbortIncompleteMultipartUpload><DaysAfterInitiation>")
                        .append(rule.get("abortIncompleteMpuDays"))
                        .append("</DaysAfterInitiation></AbortIncompleteMultipartUpload>\n");
            }
            xml.append("  </Rule>\n");
        }
        xml.append(io.github.letsopen.localoss.xml.S3XmlWriter.footer("LifecycleConfiguration"));
        writeXml(response, HttpServletResponse.SC_OK, xml.toString());
    }

    private void deleteLifecycle(String bucket, HttpServletResponse response) throws IOException {
        if (!storage.bucketExists(bucket)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET);
        }
        var cfg = storage.getBucketConfig(keyValidator.bucketDir(bucket));
        cfg.remove("lifecycle");
        storage.writeBucketConfig(keyValidator.bucketDir(bucket), cfg);
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    // ==================== helpers ====================

    private void writeXml(HttpServletResponse response, int status, String xml) throws IOException {
        byte[] body = xml.getBytes(StandardCharsets.UTF_8);
        response.setContentType("application/xml");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLengthLong(body.length);
        response.setStatus(status);
        try (var out = response.getOutputStream()) {
            out.write(body);
        }
    }

    static String xmlEscape(String s) {
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
}
