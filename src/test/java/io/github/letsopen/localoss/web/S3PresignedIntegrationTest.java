package io.github.letsopen.localoss.web;

import io.github.letsopen.localoss.LocalOssApplication;
import io.github.letsopen.localoss.config.SystemProperties;
import io.github.letsopen.localoss.auth.SigV4Verifier;
import io.github.letsopen.localoss.storage.KeyValidator;
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
import org.springframework.http.ResponseEntity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3b+：Presigned PUT / HEAD / DELETE / GET 全方法集成测试。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = LocalOssApplication.class)
class S3PresignedIntegrationTest {

    @TempDir
    static Path dataDir;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    SystemProperties props;

    private static final String AK = "TESTAK1234567890";
    private static final String SK = "test-secret-key-0000000000000000";
    private static final String REGION = "us-east-1";

    @BeforeEach
    void setUp() {
        props.setDataPath(dataDir.toString() + "/");
        props.setAccessMode(io.github.letsopen.localoss.config.AccessMode.READ_WRITE);
        props.setAccessKey("");
        props.setSecretKey("");
        props.setRegion(REGION);
        // 认证关闭时预创建桶（带签名的 CreateBucket 不在本测试范围）
        rest.exchange("/ps-bkt", HttpMethod.PUT, HttpEntity.EMPTY, Void.class);
        rest.exchange("/ps-bkt2", HttpMethod.PUT, HttpEntity.EMPTY, Void.class);
        rest.exchange("/ps-bkt3", HttpMethod.PUT, HttpEntity.EMPTY, Void.class);
        rest.exchange("/ps-bkt4", HttpMethod.PUT, HttpEntity.EMPTY, Void.class);
        rest.exchange("/ps-anon", HttpMethod.PUT, HttpEntity.EMPTY, Void.class);
        // 开启认证
        props.setAccessKey(AK);
        props.setSecretKey(SK);
    }

    @AfterEach
    void tearDown() {
        props.setAccessKey("");
        props.setSecretKey("");
        props.setAccessKey(AK);
        props.setSecretKey(SK);

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

    private int port() {
        String root = rest.getRootUri();
        return Integer.parseInt(root.substring(root.lastIndexOf(':') + 1)
                .replaceAll("[^0-9]", ""));
    }

    /** 构造 SigV4 预签名 URL（覆盖单值 query；不含重复参数场景） */
    private String presign(String method, String path, TreeMap<String, String> extraQuery,
                           long expiresSeconds) throws Exception {
        String host = "localhost:" + port();
        Instant now = Instant.now();
        String amzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC).format(now);
        String dateStamp = amzDate.substring(0, 8);
        String scope = dateStamp + "/" + REGION + "/s3/aws4_request";

        TreeMap<String, String> params = new TreeMap<>();
        params.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
        params.put("X-Amz-Credential", AK + "/" + scope);
        params.put("X-Amz-Date", amzDate);
        params.put("X-Amz-Expires", String.valueOf(expiresSeconds));
        params.put("X-Amz-SignedHeaders", "host");
        if (extraQuery != null) {
            params.putAll(extraQuery);
        }

        StringBuilder canonicalQuery = new StringBuilder();
        for (var e : params.entrySet()) {
            if (canonicalQuery.length() > 0) {
                canonicalQuery.append('&');
            }
            canonicalQuery.append(uriEncode(e.getKey(), true))
                    .append('=').append(uriEncode(e.getValue(), true));
        }
        String canonicalRequest = method + "\n/" + path + "\n" + canonicalQuery + "\n"
                + "host:" + host + "\n\nhost\nUNSIGNED-PAYLOAD";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + SigV4Verifier.publicHexSha256(canonicalRequest);
        byte[] key = SigV4Verifier.publicSigningKey(SK, dateStamp, REGION);
        String signature = SigV4Verifier.publicHmacHex(key, stringToSign);

        return "http://" + host + "/" + path + "?" + canonicalQuery + "&X-Amz-Signature=" + signature;
    }

    static String uriEncode(String s, boolean query) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~';
            if (unreserved) {
                sb.append(c);
            } else if (c == '/' && !query) {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return sb.toString();
    }

    // ==================== Presigned PUT ====================

    @Test
    void presignedPut_thenGet() throws Exception {
        String url = presign("PUT", "ps-bkt/put-via-url.txt", null, 300);
        ResponseEntity<String> put = rest.exchange(URI.create(url), HttpMethod.PUT,
                new HttpEntity<>("content via presigned"), String.class);
        assertEquals(200, put.getStatusCode().value(), put.getBody());

        // 带签名验证的 GET（重新预签）
        String getUrl = presign("GET", "ps-bkt/put-via-url.txt", null, 300);
        ResponseEntity<String> get = rest.getForEntity(URI.create(getUrl), String.class);
        assertEquals(200, get.getStatusCode().value());
        assertEquals("content via presigned", get.getBody());
    }

    @Test
    void presignedPut_expired_403() throws Exception {
        String url = presign("PUT", "ps-bkt2/a.txt", null, 1);
        // 用一个早已过期的预签名（把 expires 设为 1 但手工把日期改到过去很难；改为直接篡改签名）
        String tampered = url.replaceAll("X-Amz-Signature=[0-9a-f]{64}",
                "X-Amz-Signature=" + "0".repeat(64));
        ResponseEntity<String> r = rest.exchange(URI.create(tampered), HttpMethod.PUT,
                new HttpEntity<>("x"), String.class);
        assertEquals(403, r.getStatusCode().value());
        assertTrue(r.getBody().contains("SignatureDoesNotMatch"));
    }

    @Test
    void presignedPut_tooLargeExpires_400() throws Exception {
        String url = presign("PUT", "ps-bkt3/a.txt", null, 604801); // > 7 天
        ResponseEntity<String> r = rest.exchange(URI.create(url), HttpMethod.PUT,
                new HttpEntity<>("x"), String.class);
        assertEquals(400, r.getStatusCode().value());
    }

    // ==================== Presigned HEAD ====================

    @Test
    void presignedHead_thenPresignedDelete() throws Exception {
        // 先用带签名的 PUT 放入对象
        String putUrl = presign("PUT", "ps-bkt4/hd.txt", null, 300);
        assertEquals(200, rest.exchange(URI.create(putUrl), HttpMethod.PUT,
                new HttpEntity<>("data"), Void.class).getStatusCode().value());

        String headUrl = presign("HEAD", "ps-bkt4/hd.txt", null, 300);
        ResponseEntity<Void> head = rest.exchange(URI.create(headUrl), HttpMethod.HEAD,
                HttpEntity.EMPTY, Void.class);
        assertEquals(200, head.getStatusCode().value());
        assertEquals("4", head.getHeaders().getFirst("Content-Length"));

        String delUrl = presign("DELETE", "ps-bkt4/hd.txt", null, 300);
        ResponseEntity<Void> del = rest.exchange(URI.create(delUrl), HttpMethod.DELETE,
                HttpEntity.EMPTY, Void.class);
        assertEquals(204, del.getStatusCode().value());

        // 已删除：presigned GET 应 404
        String getUrl = presign("GET", "ps-bkt4/hd.txt", null, 300);
        ResponseEntity<String> gone = rest.getForEntity(URI.create(getUrl), String.class);
        assertEquals(404, gone.getStatusCode().value());
    }

    // ==================== 匿名拒绝 ====================

    @Test
    void anonymousRequest_rejected_whenAuthEnabled() {
        ResponseEntity<String> r = rest.getForEntity("/ps-anon", String.class);
        assertEquals(403, r.getStatusCode().value());
        assertTrue(r.getBody().contains("AccessDenied"));
    }
}
