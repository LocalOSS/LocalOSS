package io.github.letsopen.localoss.web;

import io.github.letsopen.localoss.LocalOssApplication;
import io.github.letsopen.localoss.auth.SigV4Verifier;
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

import java.nio.file.Files;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ACL 有限执行集成测试（单租户语义：public-read = 匿名可读）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = LocalOssApplication.class)
class S3AclIntegrationTest {

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
        props.setAccessKey("TESTAK0000000000");
        props.setSecretKey("test-secret-000000000000000000");
        props.setAllowAnonymousRead(false);
    }

    @AfterEach
    void tearDown() {
        props.setAllowAnonymousRead(false);
        props.setAccessKey("");
        props.setSecretKey("");
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
        // 用无签名的 PUT 需要关闭认证；改为预签名 PUT
        String url = presign("PUT", bucket, null, 300);
        assertEquals(200, rest.exchange(java.net.URI.create(url), HttpMethod.PUT,
                HttpEntity.EMPTY, Void.class).getStatusCode().value(), "create " + bucket);
    }

    private void put(String bucket, String key, String payload, String acl) {
        String url = presign("PUT", bucket + "/" + key, null, 300);
        HttpHeaders h = new HttpHeaders();
        if (acl != null) {
            h.set("x-amz-acl", acl);
        }
        ResponseEntity<String> r = rest.exchange(java.net.URI.create(url), HttpMethod.PUT,
                new HttpEntity<>(payload, h), String.class);
        assertEquals(200, r.getStatusCode().value(), "PUT " + key + " -> " + r.getBody());
    }

    private static final String AK = "TESTAK0000000000";
    private static final String SK = "test-secret-000000000000000000";
    private static final String REGION = "us-east-1";

    private String presign(String method, String path, TreeMap<String, String> extra,
                           long expires) {
        return presign(method, path, extra, expires, null);
    }

    private String presign(String method, String uriPath, TreeMap<String, String> extraParams,
                           long expires, TreeMap<String, String> extraQuery) {
        try {
            String host = "localhost:" + port();
            java.time.Instant now = java.time.Instant.now();
            String amzDate = java.time.format.DateTimeFormatter
                    .ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC)
                    .format(now);
            String dateStamp = amzDate.substring(0, 8);
            String scope = dateStamp + "/" + REGION + "/s3/aws4_request";
            java.util.TreeMap<String, String> params = new java.util.TreeMap<>();
            params.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
            params.put("X-Amz-Credential", AK + "/" + scope);
            params.put("X-Amz-Date", amzDate);
            params.put("X-Amz-Expires", String.valueOf(expires));
            params.put("X-Amz-SignedHeaders", "host");
            if (extraQuery != null) {
                params.putAll(extraQuery);
            }
            if (extraParams != null) {
                params.putAll(extraParams);
            }
            StringBuilder cq = new StringBuilder();
            for (var e : params.entrySet()) {
                if (cq.length() > 0) {
                    cq.append('&');
                }
                cq.append(S3PresignedIntegrationTest.uriEncode(e.getKey(), true))
                        .append('=').append(S3PresignedIntegrationTest.uriEncode(e.getValue(), true));
            }
            String canonicalRequest = method + "\n/" + uriPath + "\n" + cq + "\n"
                    + "host:" + host + "\n\nhost\nUNSIGNED-PAYLOAD";
            String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                    + SigV4Verifier.publicHexSha256(canonicalRequest);
            byte[] key = SigV4Verifier.publicSigningKey(SK, dateStamp, REGION);
            String signature = SigV4Verifier.publicHmacHex(key, stringToSign);
            return "http://" + host + "/" + uriPath + "?" + cq + "&X-Amz-Signature=" + signature;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private int port() {
        String root = rest.getRootUri();
        return Integer.parseInt(root.substring(root.lastIndexOf(':') + 1)
                .replaceAll("[^0-9]", ""));
    }

    private String aclXml(boolean publicRead) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<AccessControlPolicy xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">\n");
        sb.append("  <Owner><ID>local</ID><DisplayName>local</DisplayName></Owner>\n");
        sb.append("  <AccessControlList>\n");
        sb.append("    <Grant><Grantee xsi:type=\"CanonicalUser\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">")
                .append("<ID>local</ID></Grantee><Permission>FULL_CONTROL</Permission></Grant>\n");
        if (publicRead) {
            sb.append("    <Grant><Grantee xsi:type=\"Group\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">")
                    .append("<URI>http://acs.amazonaws.com/groups/global/AllUsers</URI></Grantee>")
                    .append("<Permission>READ</Permission></Grant>\n");
        }
        sb.append("  </AccessControlList>\n");
        sb.append("</AccessControlPolicy>");
        return sb.toString();
    }

    // ==================== Object ACL ====================

    @Test
    void objectAcl_publicRead_allowsAnonymousGet() {
        createBucket("acl-b1");
        put("acl-b1", "public.txt", "visible", "public-read");
        put("acl-b1", "private.txt", "secret", "private");

        // 匿名（认证关闭但 allow-anonymous-read=false）
        ResponseEntity<String> pub = rest.getForEntity("/acl-b1/public.txt", String.class);
        assertEquals(200, pub.getStatusCode().value(), "public-read object must be readable");
        assertEquals("visible", pub.getBody());

        ResponseEntity<String> priv = rest.getForEntity("/acl-b1/private.txt", String.class);
        assertEquals(403, priv.getStatusCode().value(), "private object must deny anonymous");
        assertTrue(priv.getBody().contains("AccessDenied"));
    }

    @Test
    void objectAcl_putAcl_updatesVisibility() {
        createBucket("acl-b2");
        put("acl-b2", "doc.txt", "data", "private");
        assertEquals(403, rest.getForEntity("/acl-b2/doc.txt", String.class).getStatusCode().value());

        // PUT ?acl with public-read XML（presigned）
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_XML);
        h.set("x-amz-acl", "public-read");
        String putUrl = presign("PUT", "acl-b2/doc.txt", null, 300, new java.util.TreeMap<>(Map.of("acl", "")));
        assertEquals(200, rest.exchange(URI.create(putUrl), HttpMethod.PUT,
                new HttpEntity<>(aclXml(true), h), Void.class).getStatusCode().value());

        assertEquals(200, rest.getForEntity("/acl-b2/doc.txt", String.class).getStatusCode().value());

        // 再改回 private（仅 XML，无 canned 头）
        HttpHeaders h2 = new HttpHeaders();
        h2.setContentType(MediaType.APPLICATION_XML);
        assertEquals(200, rest.exchange(URI.create(putUrl), HttpMethod.PUT,
                new HttpEntity<>(aclXml(false), h2), Void.class).getStatusCode().value());
        assertEquals(403, rest.getForEntity("/acl-b2/doc.txt", String.class).getStatusCode().value());
    }

    @Test
    void objectAcl_getAcl_xml() {
        createBucket("acl-b3");
        put("acl-b3", "a.txt", "x", "public-read");
        ResponseEntity<String> r = rest.getForEntity("/acl-b3/a.txt?acl", String.class);
        assertEquals(200, r.getStatusCode().value());
        assertTrue(r.getBody().contains("AccessControlPolicy"));
        assertTrue(r.getBody().contains("AllUsers"));
        assertTrue(r.getBody().contains("<Permission>READ</Permission>"));
    }

    @Test
    void objectAcl_invalidCanned_400() {
        createBucket("acl-b4");
        HttpHeaders h = new HttpHeaders();
        h.set("x-amz-acl", "log-delivery-write");
        ResponseEntity<String> r = rest.exchange("/acl-b4/x.txt", HttpMethod.PUT,
                new HttpEntity<>("x", h), String.class);
        assertEquals(400, r.getStatusCode().value());
        assertTrue(r.getBody().contains("InvalidArgument"));
    }

    @Test
    void objectAcl_grantHeaders_501() {
        createBucket("acl-b5");
        HttpHeaders h = new HttpHeaders();
        h.set("x-amz-grant-read", "id=someone");
        String putUrl = presign("PUT", "acl-b5/x.txt", null, 300);
        ResponseEntity<String> r = rest.exchange(URI.create(putUrl), HttpMethod.PUT,
                new HttpEntity<>("x", h), String.class);
        assertEquals(501, r.getStatusCode().value());
        assertTrue(r.getBody().contains("NotImplemented"));
    }

    // ==================== Bucket ACL ====================

    @Test
    void bucketAcl_putGet() throws Exception {
        createBucket("acl-bb");
        // presigned PUT ?acl（canned 头）
        String putUrl = presign("PUT", "acl-bb", null, 300, new java.util.TreeMap<>(Map.of("acl", "")));
        HttpHeaders h = new HttpHeaders();
        h.set("x-amz-acl", "public-read");
        ResponseEntity<String> putR = rest.exchange(URI.create(putUrl), HttpMethod.PUT,
                new HttpEntity<>("", h), String.class);
        System.out.println("BKT-ACL-PUT: " + putR.getStatusCode() + " " + putR.getBody());
        assertEquals(200, putR.getStatusCode().value());

        String getUrl = presign("GET", "acl-bb", null, 300, new java.util.TreeMap<>(Map.of("acl", "")));
        ResponseEntity<String> get = rest.getForEntity(URI.create(getUrl), String.class);
        assertEquals(200, get.getStatusCode().value());
        assertTrue(get.getBody().contains("AllUsers"));
    }

    @Test
    void bucketAcl_missingBucket_404() {
        java.net.URI uri = java.net.URI.create(
                "http://localhost:" + port() + "/acl-no-bucket?acl");
        ResponseEntity<String> r = rest.getForEntity(uri, String.class);
        assertEquals(404, r.getStatusCode().value());
    }

    // ==================== CopyObject ACL 继承 ====================

    @Test
    void copyObject_inheritsPublicRead() {
        createBucket("acl-b6");
        put("acl-b6", "src.txt", "copy", "public-read");
        HttpHeaders h = new HttpHeaders();
        h.set("x-amz-copy-source", "/acl-b6/src.txt");
        String putUrl = presign("PUT", "acl-b6/dst.txt", null, 300);
        assertEquals(200, rest.exchange(URI.create(putUrl), HttpMethod.PUT,
                new HttpEntity<>(h), Void.class).getStatusCode().value());

        // 复制出的对象继承 public-read
        assertEquals(200, rest.getForEntity("/acl-b6/dst.txt", String.class).getStatusCode().value());
    }

    // ==================== 与全局匿名读的叠加 ====================

    @Test
    void acl_vs_globalAnonymousRead_semantics() {
        // 语义：全局开关决定"未显式设置 ACL"的对象；显式 ACL 始终优先
        createBucket("acl-b7");
        put("acl-b7", "p.txt", "pub", "public-read");
        put("acl-b7", "s.txt", "sec", "private");
        put("acl-b7", "n.txt", "none", null);

        // 全局关闭：public-read 可读；private 与默认均拒绝
        props.setAllowAnonymousRead(false);
        assertEquals(200, rest.getForEntity("/acl-b7/p.txt", String.class).getStatusCode().value());
        assertEquals(403, rest.getForEntity("/acl-b7/s.txt", String.class).getStatusCode().value());
        assertEquals(403, rest.getForEntity("/acl-b7/n.txt", String.class).getStatusCode().value());

        // 全局开启：private 仍拒绝；public-read 与默认均匿名可读
        props.setAllowAnonymousRead(true);
        assertEquals(200, rest.getForEntity("/acl-b7/p.txt", String.class).getStatusCode().value());
        assertEquals(403, rest.getForEntity("/acl-b7/s.txt", String.class).getStatusCode().value());
        assertEquals(200, rest.getForEntity("/acl-b7/n.txt", String.class).getStatusCode().value());

        props.setAllowAnonymousRead(false);
    }
}
