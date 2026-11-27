package io.github.letsopen.localoss.web;

import io.github.letsopen.localoss.LocalOssApplication;
import io.github.letsopen.localoss.config.SystemProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S3 风格负面用例矩阵：所有不合法输入必须返回标准 Error XML（正确错误码+状态码）。
 *
 * <p>覆盖维度：
 * <ol>
 *   <li>URI 解析：非法编码、空桶段、穿越</li>
 *   <li>Bucket：非法名、重复创建、删非空、不存在</li>
 *   <li>Object：key 非法、缺失对象、Range 非法、响应头覆盖非法</li>
 *   <li>List：max-keys/encoding-type/continuation-token 非法</li>
 *   <li>Multipart：partNumber/uploadId 非法、Complete XML 缺陷</li>
 *   <li>XML：格式错误、XXE、超限</li>
 *   <li>Tagging/Lifecycle：非法标签、非法规则</li>
 *   <li>认证开启时：未签名、坏签名、时间偏差、坏 AK</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = LocalOssApplication.class)
class S3NegativeCasesIntegrationTest {

    @TempDir
    static Path dataDir;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    SystemProperties props;

    @BeforeEach
    void setUp() {
        props.setDataPath(dataDir.toString() + "/");
        props.setAccessMode(io.github.letsopen.localoss.config.AccessMode.READ_WRITE);
        props.setAccessKey("");
        props.setSecretKey("");
        props.setCredentials(java.util.List.of());
        props.setAllowAnonymousRead(false);
    }

    @AfterEach
    void tearDown() {
        try (var s = Files.newDirectoryStream(dataDir)) {
            for (Path p : s) {
                deleteRecursively(p);
            }
        } catch (Exception ignored) {
        }
    }

    private static void deleteRecursively(Path dir) {
        try (var s = Files.newDirectoryStream(dir)) {
            for (Path p : s) {
                if (Files.isDirectory(p)) {
                    deleteRecursively(p);
                } else {
                    Files.deleteIfExists(p);
                }
            }
            Files.deleteIfExists(dir);
        } catch (Exception ignored) {
        }
    }

    private void createBucket(String bucket) {
        assertEquals(200, rest.exchange("/" + bucket, HttpMethod.PUT,
                HttpEntity.EMPTY, Void.class).getStatusCode().value());
    }

    private void put(String bucket, String key, String payload) {
        assertEquals(200, rest.exchange("/" + bucket + "/" + key, HttpMethod.PUT,
                new HttpEntity<>(payload), Void.class).getStatusCode().value(), "PUT " + key);
    }

    private int port() {
        String root = rest.getRootUri();
        return Integer.parseInt(root.substring(root.lastIndexOf(':') + 1)
                .replaceAll("[^0-9]", ""));
    }

    private ResponseEntity<String> get(URI uri) {
        return rest.getForEntity(uri, String.class);
    }

    private void assertS3Error(ResponseEntity<String> r, int status, String code) {
        assertEquals(status, r.getStatusCode().value(),
                "expected " + status + " got " + r.getStatusCode().value() + ": " + r.getBody());
        // HEAD 响应无 body（HTTP 语义）；其余必须返回 Error XML
        String body = r.getBody();
        if (body == null || body.isEmpty()) {
            // HEAD 情况：仅状态码可判断
            return;
        }
        assertTrue(body.startsWith("<?xml"), "must be XML: " + body);
        assertTrue(body.contains("<Error>"), "must be Error XML: " + body);
        assertTrue(body.contains("<Code>" + code + "</Code>"),
                "expected code " + code + " in: " + body);
        assertTrue(body.contains("<RequestId>"), "must have RequestId");
        assertTrue(body.contains("<Resource>"), "must have Resource");
        assertNotNull(r.getHeaders().getFirst("x-amz-request-id"), "must have request-id header");
    }

    // ==================== 1. URI 解析 ====================

    @Test
    void uri_literalPercentKey_ok() {
        // key 中的 % 是合法字符（%25 编码的 %）——应正常往返
        createBucket("neg-pct");
        ResponseEntity<Void> put = rest.exchange(
                "http://localhost:" + port() + "/neg-pct/100%25done.txt",
                HttpMethod.PUT, new HttpEntity<>("x"), Void.class);
        assertEquals(200, put.getStatusCode().value());
        ResponseEntity<String> get = rest.getForEntity(
                "http://localhost:" + port() + "/neg-pct/100%25done.txt", String.class);
        assertEquals(200, get.getStatusCode().value());
        assertEquals("x", get.getBody());
    }

    @Test
    void uri_encodedDotDot_rejected() throws Exception {
        createBucket("neg-trav");
        // 用原始 socket 发送（绕过 RestTemplate 的客户端规范化）
        try (var sock = new java.net.Socket("localhost", port())) {
            sock.getOutputStream().write((
                    "PUT /neg-trav/%2e%2e/escape HTTP/1.1\r\n"
                    + "Host: localhost\r\nContent-Length: 1\r\nConnection: close\r\n"
                    + "\r\nx").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String resp = new String(sock.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            int status = Integer.parseInt(resp.split(" ")[1]);
            assertTrue(status == 400 || status == 403 || status == 404,
                    "dot-dot must be rejected, got " + status);
        }
        // 确认没有写到桶外（bucket 根之外不得出现 escape 目录/文件）
        assertFalse(Files.exists(dataDir.resolve("escape")),
                "traversal must not escape bucket");
        assertFalse(Files.exists(dataDir.resolve("neg-trav").resolve("escape")),
                "normalized escape must not be written either");
    }

    @Test
    void uri_emptyBucketSegment_400() {
        assertS3Error(get(URI.create("http://localhost:" + port() + "//key")),
                400, "InvalidBucketName");
    }

    // ==================== 2. Bucket ====================

    @Test
    void bucket_invalidNames_all400() {
        for (String bad : new String[]{"ab", "-abc", "abc-", "192.168.5.4", "ex..ample",
                "xn--foo", "MyBucket", "my_bucket", ".localoss"}) {
            ResponseEntity<String> r = rest.exchange("/" + bad, HttpMethod.PUT,
                    HttpEntity.EMPTY, String.class);
            assertS3Error(r, 400, "InvalidBucketName");
        }
    }

    @Test
    void bucket_createDuplicate_409() {
        createBucket("neg-dupe");
        ResponseEntity<String> r = rest.exchange("/neg-dupe", HttpMethod.PUT,
                HttpEntity.EMPTY, String.class);
        assertS3Error(r, 409, "BucketAlreadyOwnedByYou");
    }

    @Test
    void bucket_deleteNonEmpty_409() {
        createBucket("neg-nonempty");
        put("neg-nonempty", "a.txt", "x");
        assertS3Error(rest.exchange("/neg-nonempty", HttpMethod.DELETE,
                HttpEntity.EMPTY, String.class), 409, "BucketNotEmpty");
    }

    @Test
    void bucket_missing_headAndDelete_404() {
        ResponseEntity<Void> head = rest.exchange("/neg-ghost-404", HttpMethod.HEAD,
                HttpEntity.EMPTY, Void.class);
        assertEquals(404, head.getStatusCode().value());
        assertS3Error(rest.exchange("/neg-ghost-404", HttpMethod.DELETE,
                HttpEntity.EMPTY, String.class), 404, "NoSuchBucket");
    }

    @Test
    void bucket_missing_operations_404() {
        // GET object / PUT object / subresource 到不存在的桶
        assertS3Error(get(URI.create("http://localhost:" + port() + "/neg-ghost/o.txt")),
                404, "NoSuchBucket");
        assertS3Error(rest.exchange("/neg-ghost/o.txt", HttpMethod.PUT,
                new HttpEntity<>("x"), String.class), 404, "NoSuchBucket");
        assertS3Error(get(URI.create("http://localhost:" + port() + "/neg-ghost?location")),
                404, "NoSuchBucket");
        assertS3Error(get(URI.create("http://localhost:" + port() + "/neg-ghost?tagging")),
                404, "NoSuchBucket");
    }

    // ==================== 3. Object ====================

    @Test
    void object_keyTooLong_400() {
        createBucket("neg-key2");
        String longKey = "a/".repeat(600) + "end";  // >1024 字节
        assertS3Error(rest.exchange("http://localhost:" + port() + "/neg-key2/" + longKey,
                HttpMethod.PUT, new HttpEntity<>("x"), String.class), 400, "InvalidArgument");
    }

    @Test
    void object_invalidKeys_400() throws Exception {
        createBucket("neg-key");
        // 双斜杠 / dot 段：客户端会规范化，用原始 socket
        String[] badPaths = {"/neg-key/a//b", "/neg-key/a/./b", "/neg-key/.localoss/evil"};
        for (String path : badPaths) {
            try (var sock = new java.net.Socket("localhost", port())) {
                sock.getOutputStream().write((
                        "PUT " + path + " HTTP/1.1\r\n"
                        + "Host: localhost\r\nContent-Length: 1\r\nConnection: close\r\n"
                        + "\r\nx").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                String resp = new String(sock.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                int status = Integer.parseInt(resp.split(" ")[1]);
                assertTrue(status == 400,
                        path + " must be 400, got " + status + " body="
                                + resp.substring(resp.indexOf("\r\n\r\n") + 4,
                                        Math.min(resp.length(),
                                                resp.indexOf("\r\n\r\n") + 154)));
            }
        }
        // 超长 segment（>200 字节）：RestTemplate 不会改它
        String longSeg = "k".repeat(201);
        assertS3Error(rest.exchange("http://localhost:" + port() + "/neg-key/" + longSeg,
                HttpMethod.PUT, new HttpEntity<>("x"), String.class), 400, "InvalidArgument");
    }

    @Test
    void object_conflictPrefix_409() {
        createBucket("neg-conf");
        put("neg-conf", "a", "file");
        assertS3Error(rest.exchange("/neg-conf/a/b", HttpMethod.PUT,
                new HttpEntity<>("x"), String.class), 409, "KeyConflict");
        // 反向：先有 a/b 再写 a
        createBucket("neg-conf2");
        put("neg-conf2", "x/y.txt", "child");
        assertS3Error(rest.exchange("/neg-conf2/x", HttpMethod.PUT,
                new HttpEntity<>("x"), String.class), 409, "KeyConflict");
    }

    @Test
    void object_missingKey_404() {
        createBucket("neg-nk");
        assertS3Error(get(URI.create("http://localhost:" + port() + "/neg-nk/no-such")),
                404, "NoSuchKey");
        ResponseEntity<Void> head = rest.exchange("/neg-nk/no-such", HttpMethod.HEAD,
                HttpEntity.EMPTY, Void.class);
        assertEquals(404, head.getStatusCode().value());
        // DELETE 不存在的 key：S3 幂等语义返回 204
        assertEquals(204, rest.exchange("/neg-nk/no-such", HttpMethod.DELETE,
                HttpEntity.EMPTY, String.class).getStatusCode().value());
        assertS3Error(get(URI.create("http://localhost:" + port() + "/neg-nk/no-such?tagging")),
                404, "NoSuchKey");
        assertS3Error(get(URI.create("http://localhost:" + port() + "/neg-nk/no-such?acl")),
                404, "NoSuchKey");
    }

    @Test
    void object_rangeMalformed_416or400() {
        createBucket("neg-range");
        put("neg-range", "a.bin", "0123456789");
        HttpHeaders h = new HttpHeaders();
        // 非数字
        h.set("Range", "bytes=abc-5");
        assertS3Error(rest.exchange(URI.create("http://localhost:" + port() + "/neg-range/a.bin"),
                HttpMethod.GET, new HttpEntity<>(h), String.class), 416, "InvalidRange");
        // 逆向范围
        HttpHeaders h2 = new HttpHeaders();
        h2.set("Range", "bytes=9-2");
        assertS3Error(rest.exchange(URI.create("http://localhost:" + port() + "/neg-range/a.bin"),
                HttpMethod.GET, new HttpEntity<>(h2), String.class), 416, "InvalidRange");
        // 完全超界
        HttpHeaders h3 = new HttpHeaders();
        h3.set("Range", "bytes=100-200");
        assertS3Error(rest.exchange(URI.create("http://localhost:" + port() + "/neg-range/a.bin"),
                HttpMethod.GET, new HttpEntity<>(h3), String.class), 416, "InvalidRange");
        // 无 dash
        HttpHeaders h4 = new HttpHeaders();
        h4.set("Range", "bytes=5");
        assertS3Error(rest.exchange(URI.create("http://localhost:" + port() + "/neg-range/a.bin"),
                HttpMethod.GET, new HttpEntity<>(h4), String.class), 416, "InvalidRange");
    }

    @Test
    void object_checksumMismatch_400() {
        createBucket("neg-md5");
        HttpHeaders h = new HttpHeaders();
        h.set("Content-MD5", java.util.Base64.getEncoder().encodeToString(new byte[16]));
        assertS3Error(rest.exchange("/neg-md5/a.txt", HttpMethod.PUT,
                new HttpEntity<>("data", h), String.class), 400, "BadDigest");
        // SHA256 不匹配
        HttpHeaders h2 = new HttpHeaders();
        h2.set("x-amz-checksum-sha256", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        assertS3Error(rest.exchange("/neg-md5/b.txt", HttpMethod.PUT,
                new HttpEntity<>("data", h2), String.class), 400, "BadDigest");
    }

    @Test
    void object_userMetadataTooLarge_400() {
        createBucket("neg-meta");
        HttpHeaders h = new HttpHeaders();
        h.set("x-amz-meta-big", "v".repeat(3000));
        assertS3Error(rest.exchange("/neg-meta/a.txt", HttpMethod.PUT,
                new HttpEntity<>("x", h), String.class), 400, "InvalidArgument");
    }

    @Test
    void object_tooLargeSinglePut_400() {
        createBucket("neg-big");
        props.setMaxSinglePutSize(1024);
        try {
            assertS3Error(rest.exchange("/neg-big/a.bin", HttpMethod.PUT,
                    new HttpEntity<>("x".repeat(2048)), String.class), 400, "EntityTooLarge");
        } finally {
            props.setMaxSinglePutSize(5L * 1024 * 1024 * 1024);
        }
    }

    // ==================== 4. List ====================

    @Test
    void list_badContinuationToken_400() {
        createBucket("neg-list");
        assertS3Error(get(URI.create("http://localhost:" + port()
                        + "/neg-list?list-type=2&continuation-token=%21%21bad%21%21")),
                400, "InvalidArgument");
    }

    @Test
    void list_badEncodingType_ignoredOrEchoed() {
        createBucket("neg-list2");
        put("neg-list2", "a.txt", "x");
        // encoding-type 非 url 值：S3 忽略之（返回 200 且不编码）
        ResponseEntity<String> r = rest.getForEntity(URI.create(
                "http://localhost:" + port() + "/neg-list2?list-type=2&encoding-type=bogus"),
                String.class);
        assertEquals(200, r.getStatusCode().value());
    }

    @Test
    void list_negativeMaxKeys_clamped() {
        createBucket("neg-list3");
        put("neg-list3", "a.txt", "x");
        // max-keys=-5 → 钳制为 0（空结果而非报错，S3 行为）
        ResponseEntity<String> r = rest.getForEntity(URI.create(
                "http://localhost:" + port() + "/neg-list3?list-type=2&max-keys=-5"),
                String.class);
        assertEquals(200, r.getStatusCode().value());
        assertTrue(r.getBody().contains("<KeyCount>0</KeyCount>"));
    }

    // ==================== 5. Multipart ====================

    private String initiate(String bucket, String key) {
        ResponseEntity<String> r = rest.exchange("/" + bucket + "/" + key + "?uploads",
                HttpMethod.POST, HttpEntity.EMPTY, String.class);
        assertEquals(200, r.getStatusCode().value());
        int s = r.getBody().indexOf("<UploadId>") + "<UploadId>".length();
        int e = r.getBody().indexOf("</UploadId>");
        return r.getBody().substring(s, e);
    }

    @Test
    void mpu_invalidPartNumber_400() {
        createBucket("neg-mpu");
        String uid = initiate("neg-mpu", "big.bin");
        // 0
        assertS3Error(rest.exchange(URI.create("http://localhost:" + port()
                        + "/neg-mpu/big.bin?partNumber=0&uploadId=" + uid),
                HttpMethod.PUT, new HttpEntity<>("x"), String.class), 400, "InvalidArgument");
        // 10001
        assertS3Error(rest.exchange(URI.create("http://localhost:" + port()
                        + "/neg-mpu/big.bin?partNumber=10001&uploadId=" + uid),
                HttpMethod.PUT, new HttpEntity<>("x"), String.class), 400, "InvalidArgument");
        // 非数字
        assertS3Error(rest.exchange(URI.create("http://localhost:" + port()
                        + "/neg-mpu/big.bin?partNumber=abc&uploadId=" + uid),
                HttpMethod.PUT, new HttpEntity<>("x"), String.class), 400, "InvalidArgument");
    }

    @Test
    void mpu_unknownUploadId_404() {
        createBucket("neg-mpu2");
        assertS3Error(rest.exchange(URI.create("http://localhost:" + port()
                        + "/neg-mpu2/k.bin?partNumber=1&uploadId=no-such"),
                HttpMethod.PUT, new HttpEntity<>("x"), String.class), 404, "NoSuchUpload");
        assertS3Error(get(URI.create("http://localhost:" + port()
                        + "/neg-mpu2/k.bin?uploadId=no-such")), 404, "NoSuchUpload");
        // Abort 未知的
        assertS3Error(rest.exchange(URI.create("http://localhost:" + port()
                        + "/neg-mpu2/k.bin?uploadId=no-such"), HttpMethod.DELETE,
                HttpEntity.EMPTY, String.class), 404, "NoSuchUpload");
    }

    @Test
    void mpu_complete_malformedXml_400() {
        createBucket("neg-mpu3");
        String uid = initiate("neg-mpu3", "k.bin");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_XML);
        // 缺 PartNumber
        assertS3Error(rest.exchange(URI.create("http://localhost:" + port()
                        + "/neg-mpu3/k.bin?uploadId=" + uid), HttpMethod.POST,
                new HttpEntity<>("<CompleteMultipartUpload/>", h), String.class),
                400, "MalformedXML");
        // PartNumber 非数字
        assertS3Error(rest.exchange(URI.create("http://localhost:" + port()
                        + "/neg-mpu3/k.bin?uploadId=" + uid), HttpMethod.POST,
                new HttpEntity<>("<CompleteMultipartUpload><Part><PartNumber>abc</PartNumber>"
                        + "</Part></CompleteMultipartUpload>", h), String.class),
                400, "MalformedXML");
        // 乱序
        assertS3Error(rest.exchange(URI.create("http://localhost:" + port()
                        + "/neg-mpu3/k.bin?uploadId=" + uid), HttpMethod.POST,
                new HttpEntity<>("<CompleteMultipartUpload><Part><PartNumber>3</PartNumber>"
                        + "</Part><Part><PartNumber>1</PartNumber></Part></CompleteMultipartUpload>",
                        h), String.class), 400, "InvalidPartOrder");
    }

    @Test
    void mpu_complete_partNotUploaded_400() {
        createBucket("neg-mpu4");
        String uid = initiate("neg-mpu4", "k.bin");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_XML);
        assertS3Error(rest.exchange(URI.create("http://localhost:" + port()
                        + "/neg-mpu4/k.bin?uploadId=" + uid), HttpMethod.POST,
                new HttpEntity<>("<CompleteMultipartUpload><Part><PartNumber>1</PartNumber>"
                        + "</Part></CompleteMultipartUpload>", h), String.class),
                400, "InvalidPart");
    }

    // ==================== 6. XML 解析 ====================

    @Test
    void xml_malformed_deleteObjects_400() {
        createBucket("neg-xml");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_XML);
        assertS3Error(rest.exchange("/neg-xml?delete", HttpMethod.POST,
                new HttpEntity<>("<<<bad", h), String.class), 400, "MalformedXML");
        // 空体
        assertS3Error(rest.exchange("/neg-xml?delete", HttpMethod.POST,
                new HttpEntity<>("", h), String.class), 400, "MalformedXML");
        // 无 Object/Key
        assertS3Error(rest.exchange("/neg-xml?delete", HttpMethod.POST,
                new HttpEntity<>("<Delete/>", h), String.class), 400, "MalformedXML");
    }

    @Test
    void xml_xxe_blocked() {
        createBucket("neg-xxe");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_XML);
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <Delete><Object><Key>&xxe;</Key></Object></Delete>""";
        assertS3Error(rest.exchange("/neg-xxe?delete", HttpMethod.POST,
                new HttpEntity<>(xxe, h), String.class), 400, "MalformedXML");
    }

    @Test
    void xml_tagging_malformed_400() {
        createBucket("neg-tag");
        put("neg-tag", "a.txt", "x");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_XML);
        assertS3Error(rest.exchange("/neg-tag/a.txt?tagging", HttpMethod.PUT,
                new HttpEntity<>("not-xml", h), String.class), 400, "MalformedXML");
    }

    @Test
    void tagging_invalid_400() {
        createBucket("neg-tag2");
        put("neg-tag2", "a.txt", "x");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_XML);
        // 51 个标签
        StringBuilder sb = new StringBuilder("<Tagging><TagSet>");
        for (int i = 0; i < 51; i++) {
            sb.append("<Tag><Key>k").append(i).append("</Key><Value>v</Value></Tag>");
        }
        sb.append("</TagSet></Tagging>");
        assertS3Error(rest.exchange("/neg-tag2/a.txt?tagging", HttpMethod.PUT,
                new HttpEntity<>(sb.toString(), h), String.class), 400, "InvalidTag");
    }

    // ==================== 7. Lifecycle ====================

    @Test
    void lifecycle_malformed_400() {
        createBucket("neg-lc");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_XML);
        // 无 Rule
        assertS3Error(rest.exchange("/neg-lc?lifecycle", HttpMethod.PUT,
                new HttpEntity<>("<LifecycleConfiguration/>", h), String.class),
                400, "MalformedXML");
        // Days 非数字
        assertS3Error(rest.exchange("/neg-lc?lifecycle", HttpMethod.PUT,
                new HttpEntity<>("<LifecycleConfiguration><Rule><ID>r</ID>"
                        + "<Filter><Prefix></Prefix></Filter><Status>Enabled</Status>"
                        + "<Expiration><Days>abc</Days></Expiration></Rule>"
                        + "</LifecycleConfiguration>", h), String.class),
                400, "MalformedXML");
    }

    @Test
    void lifecycle_missingConfig_404() {
        createBucket("neg-lc2");
        assertS3Error(get(URI.create("http://localhost:" + port() + "/neg-lc2?lifecycle")),
                404, "NoSuchBucket");
        assertS3Error(get(URI.create("http://localhost:" + port() + "/neg-lc2?cors")),
                404, "NoSuchBucket");
        assertS3Error(get(URI.create("http://localhost:" + port() + "/neg-lc2?policy")),
                404, "NoSuchBucket");
    }

    @Test
    void policy_malformed_400() {
        createBucket("neg-pol");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        assertS3Error(rest.exchange("/neg-pol?policy", HttpMethod.PUT,
                new HttpEntity<>("not-json", h), String.class), 400, "MalformedPolicy");
    }

    // ==================== 8. 认证 ====================

    @Test
    void auth_anonymousRejected_403() {
        createBucket("neg-auth");
        props.setAccessKey("AK1");
        props.setSecretKey("sk1");
        try {
            // 无签名 GET
            assertS3Error(get(URI.create("http://localhost:" + port() + "/neg-auth")),
                    403, "AccessDenied");
            // 无签名 PUT
            assertS3Error(rest.exchange("/neg-auth/x.txt", HttpMethod.PUT,
                    new HttpEntity<>("x"), String.class), 403, "AccessDenied");
            // 无签名 DELETE
            assertS3Error(rest.exchange("/neg-auth/x.txt", HttpMethod.DELETE,
                    HttpEntity.EMPTY, String.class), 403, "AccessDenied");
        } finally {
            props.setAccessKey("");
            props.setSecretKey("");
        }
    }

    @Test
    void auth_badAccessKey_403() {
        createBucket("neg-auth2");
        props.setAccessKey("AK1");
        props.setSecretKey("sk1");
        try {
            // 篡改 Authorization 中的 AK
            HttpHeaders h = new HttpHeaders();
            h.set("Authorization", "AWS4-HMAC-SHA256 Credential=WRONGAK/20261124/us-east-1/s3/aws4_request,"
                    + "SignedHeaders=host,Signature=" + "0".repeat(64));
            h.set("x-amz-date", "20261124T000000Z");
            h.set("x-amz-content-sha256", "UNSIGNED-PAYLOAD");
            assertS3Error(rest.exchange(URI.create("http://localhost:" + port() + "/neg-auth2"),
                    HttpMethod.GET, new HttpEntity<>(h), String.class), 403, "InvalidAccessKeyId");
        } finally {
            props.setAccessKey("");
            props.setSecretKey("");
        }
    }

    @Test
    void auth_clockSkew_403() {
        createBucket("neg-auth3");
        props.setAccessKey("AK1");
        props.setSecretKey("sk1");
        try {
            // 预签名 URL：X-Amz-Date 是 2 小时前（超过 15 分钟偏差）
            String old = java.time.Instant.now().minusSeconds(7200)
                    .atZone(java.time.ZoneOffset.UTC)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"));
            String url = "http://localhost:" + port() + "/neg-auth3?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                    + "&X-Amz-Credential=AK1/20261124/us-east-1/s3/aws4_request"
                    + "&X-Amz-Date=" + old + "&X-Amz-Expires=86400"
                    + "&X-Amz-SignedHeaders=host&X-Amz-Signature=" + "0".repeat(64);
            assertS3Error(get(URI.create(url)), 403, "RequestTimeTooSkewed");
        } finally {
            props.setAccessKey("");
            props.setSecretKey("");
        }
    }

    @Test
    void auth_badSignature_403() {
        createBucket("neg-auth4");
        props.setAccessKey("AK1");
        props.setSecretKey("sk1");
        try {
            HttpHeaders h = new HttpHeaders();
            h.set("Authorization", "AWS4-HMAC-SHA256 Credential=AK1/20261124/us-east-1/s3/aws4_request,"
                    + "SignedHeaders=host,Signature=" + "0".repeat(64));
            h.set("x-amz-date", java.time.Instant.now()
                    .atZone(java.time.ZoneOffset.UTC)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")));
            h.set("x-amz-content-sha256", "UNSIGNED-PAYLOAD");
            assertS3Error(rest.exchange(URI.create("http://localhost:" + port() + "/neg-auth4"),
                    HttpMethod.GET, new HttpEntity<>(h), String.class), 403,
                    "SignatureDoesNotMatch");
        } finally {
            props.setAccessKey("");
            props.setSecretKey("");
        }
    }

    @Test
    void auth_expiredPresign_403() throws Exception {
        createBucket("neg-auth5");
        props.setAccessKey("AK1");
        props.setSecretKey("sk1");
        try {
            // 有效期内但 X-Amz-Date 是 1 小时前 → 时钟偏差先触发
            String old = java.time.Instant.now().minusSeconds(3600)
                    .atZone(java.time.ZoneOffset.UTC)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"));
            String url = "http://localhost:" + port() + "/neg-auth5/o.txt"
                    + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                    + "&X-Amz-Credential=AK1/20261124/us-east-1/s3/aws4_request"
                    + "&X-Amz-Date=" + old + "&X-Amz-Expires=604800"
                    + "&X-Amz-SignedHeaders=host&X-Amz-Signature=" + "0".repeat(64);
            assertS3Error(get(URI.create(url)), 403, "RequestTimeTooSkewed");
        } finally {
            props.setAccessKey("");
            props.setSecretKey("");
        }
    }

    @Test
    void auth_malformedAuthorization_400or403() {
        createBucket("neg-auth6");
        props.setAccessKey("AK1");
        props.setSecretKey("sk1");
        try {
            HttpHeaders h = new HttpHeaders();
            h.set("Authorization", "AWS4-HMAC-SHA256 garbage-without-equals");
            h.set("x-amz-date", "20261124T000000Z");
            h.set("x-amz-content-sha256", "UNSIGNED-PAYLOAD");
            ResponseEntity<String> r = rest.exchange(
                    URI.create("http://localhost:" + port() + "/neg-auth6"),
                    HttpMethod.GET, new HttpEntity<>(h), String.class);
            assertTrue(r.getStatusCode().value() == 400 || r.getStatusCode().value() == 403,
                    "must be 4xx: " + r.getStatusCode().value());
            assertTrue(r.getBody().contains("<Error>"));
        } finally {
            props.setAccessKey("");
            props.setSecretKey("");
        }
    }

    // ==================== 9. AccessMode ====================

    @Test
    void readOnlyMode_allWritePaths_403() {
        createBucket("neg-ro");
        props.setAccessMode(io.github.letsopen.localoss.config.AccessMode.READ_ONLY);
        try {
            assertS3Error(rest.exchange("/neg-ro/x.txt", HttpMethod.PUT,
                    new HttpEntity<>("x"), String.class), 403, "AccessDenied");
            assertS3Error(rest.exchange("/neg-ro/x.txt", HttpMethod.DELETE,
                    HttpEntity.EMPTY, String.class), 403, "AccessDenied");
            assertS3Error(rest.exchange("/new-bkt-ro", HttpMethod.PUT,
                    HttpEntity.EMPTY, String.class), 403, "AccessDenied");
            // POST ?delete 也拒绝
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_XML);
            assertS3Error(rest.exchange("/neg-ro?delete", HttpMethod.POST,
                    new HttpEntity<>("<Delete><Object><Key>k</Key></Object></Delete>", h),
                    String.class), 403, "AccessDenied");
        } finally {
            props.setAccessMode(io.github.letsopen.localoss.config.AccessMode.READ_WRITE);
        }
    }

    @Test
    void disabledMode_allPaths_503() {
        props.setAccessMode(io.github.letsopen.localoss.config.AccessMode.DISABLED);
        try {
            assertS3Error(get(URI.create("http://localhost:" + port() + "/")),
                    503, "ServiceUnavailable");
            assertS3Error(rest.exchange("/neg-dis", HttpMethod.PUT,
                    HttpEntity.EMPTY, String.class), 503, "ServiceUnavailable");
        } finally {
            props.setAccessMode(io.github.letsopen.localoss.config.AccessMode.READ_WRITE);
        }
    }

    // ==================== 10. 杂项边界 ====================

    @Test
    void unknownSubresource_501() {
        createBucket("neg-sub");
        assertS3Error(get(URI.create("http://localhost:" + port() + "/neg-sub?versioning")),
                501, "NotImplemented");
        assertS3Error(get(URI.create("http://localhost:" + port() + "/neg-sub?website")),
                501, "NotImplemented");
        assertS3Error(get(URI.create("http://localhost:" + port() + "/neg-sub?encryption")),
                501, "NotImplemented");
        assertS3Error(get(URI.create("http://localhost:" + port() + "/neg-sub/o.txt?attributes")),
                501, "NotImplemented");
    }

    @Test
    void deleteObjects_overLimit_400() {
        createBucket("neg-del");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_XML);
        StringBuilder sb = new StringBuilder("<Delete>");
        for (int i = 0; i < 1001; i++) {
            sb.append("<Object><Key>k").append(i).append("</Key></Object>");
        }
        sb.append("</Delete>");
        assertS3Error(rest.exchange("/neg-del?delete", HttpMethod.POST,
                new HttpEntity<>(sb.toString(), h), String.class), 400, "InvalidArgument");
    }

    @Test
    void errorXml_containsRequiredFields() {
        createBucket("neg-fmt");
        ResponseEntity<String> r = get(URI.create(
                "http://localhost:" + port() + "/neg-fmt/no-such-key"));
        assertS3Error(r, 404, "NoSuchKey");
        String body = r.getBody();
        // Key 参数回显
        assertTrue(body.contains("<Resource>/neg-fmt/no-such-key</Resource>"));
        // HostId 存在
        assertTrue(body.contains("<HostId>"));
        // XML 声明
        assertTrue(body.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        // Content-Type
        assertEquals(MediaType.APPLICATION_XML, r.getHeaders().getContentType());
    }
}
