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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 HTTP 集成测试：CopyObject、DeleteObjects、ListObjects v1/v2、响应头覆盖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = LocalOssApplication.class)
class S3Phase2IntegrationTest {

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
        // Phase 1-3 HTTP 行为测试暂不签名（Phase 3b 认证专项测试覆盖）
        props.setAccessKey("");
        props.setSecretKey("");
    }

    @AfterEach
    void tearDown() {
        // 清理桶目录
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

    private void put(String bucket, String key, String payload) {
        ResponseEntity<Void> r = rest.exchange("/" + bucket + "/" + key, HttpMethod.PUT,
                new HttpEntity<>(payload), Void.class);
        assertEquals(200, r.getStatusCode().value(), "PUT " + key);
    }

    private void createBucket(String bucket) {
        ResponseEntity<Void> r = rest.exchange("/" + bucket, HttpMethod.PUT,
                HttpEntity.EMPTY, Void.class);
        assertEquals(200, r.getStatusCode().value());
    }

    // ==================== CopyObject ====================

    @Test
    void copyObject_sameBucket() {
        createBucket("p2src");
        put("p2src", "orig/a.txt", "copy me");
        HttpHeaders h = new HttpHeaders();
        h.set("x-amz-copy-source", "/p2src/orig/a.txt");
        ResponseEntity<String> r = rest.exchange("/p2src/copied/a.txt", HttpMethod.PUT,
                new HttpEntity<>(h), String.class);
        assertEquals(200, r.getStatusCode().value());
        assertTrue(r.getBody().contains("<CopyObjectResult"));
        assertTrue(r.getBody().contains("<ETag>"));
        ResponseEntity<byte[]> get = rest.getForEntity("/p2src/copied/a.txt", byte[].class);
        assertEquals("copy me", new String(get.getBody(), StandardCharsets.UTF_8));
    }

    @Test
    void copyObject_crossBucket() {
        createBucket("p2s1");
        createBucket("p2s2");
        put("p2s1", "x.bin", "cross-bucket");
        HttpHeaders h = new HttpHeaders();
        h.set("x-amz-copy-source", "/p2s1/x.bin");
        ResponseEntity<String> r = rest.exchange("/p2s2/y.bin", HttpMethod.PUT,
                new HttpEntity<>(h), String.class);
        assertEquals(200, r.getStatusCode().value());
        ResponseEntity<byte[]> get = rest.getForEntity("/p2s2/y.bin", byte[].class);
        assertEquals("cross-bucket", new String(get.getBody(), StandardCharsets.UTF_8));
    }

    @Test
    void copyObject_missingSource_404() {
        createBucket("p2s3");
        HttpHeaders h = new HttpHeaders();
        h.set("x-amz-copy-source", "/p2s3/no-such-key");
        ResponseEntity<String> r = rest.exchange("/p2s3/out.txt", HttpMethod.PUT,
                new HttpEntity<>(h), String.class);
        assertEquals(404, r.getStatusCode().value());
        assertTrue(r.getBody().contains("NoSuchKey"));
    }

    // ==================== DeleteObjects ====================

    @Test
    void deleteObjects_batch() {
        createBucket("p2del");
        put("p2del", "a.txt", "1");
        put("p2del", "b.txt", "2");
        put("p2del", "c.txt", "3");
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Delete xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <Object><Key>a.txt</Key></Object>
                  <Object><Key>b.txt</Key></Object>
                </Delete>""";
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_XML);
        ResponseEntity<String> r = rest.exchange("/p2del?delete", HttpMethod.POST,
                new HttpEntity<>(xml, h), String.class);
        assertEquals(200, r.getStatusCode().value());
        assertTrue(r.getBody().contains("<Deleted>"));
        assertTrue(r.getBody().contains("<Key>a.txt</Key>"));
        assertTrue(r.getBody().contains("<Key>b.txt</Key>"));
        assertFalse(r.getBody().contains("c.txt"));
        assertEquals(404, rest.getForEntity("/p2del/a.txt", String.class).getStatusCode().value());
        assertEquals(200, rest.getForEntity("/p2del/c.txt", String.class).getStatusCode().value());
    }

    @Test
    void deleteObjects_malformedXml_400() {
        createBucket("p2del2");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_XML);
        ResponseEntity<String> r = rest.exchange("/p2del2?delete", HttpMethod.POST,
                new HttpEntity<>("not-xml", h), String.class);
        assertEquals(400, r.getStatusCode().value());
        assertTrue(r.getBody().contains("MalformedXML"));
    }

    // ==================== ListObjects v1 / v2 ====================

    @Test
    void listV2_basicAndPrefix() {
        createBucket("p2list");
        put("p2list", "a.txt", "1");
        put("p2list", "dir/b.txt", "2");
        put("p2list", "dir/c.txt", "3");

        ResponseEntity<String> r = rest.getForEntity("/p2list?list-type=2", String.class);
        assertEquals(200, r.getStatusCode().value());
        assertTrue(r.getBody().contains("<KeyCount>3</KeyCount>"));
        assertTrue(r.getBody().contains("<Key>a.txt</Key>"));
        assertTrue(r.getBody().contains("<Key>dir/b.txt</Key>"));
        assertTrue(r.getBody().contains("<Key>dir/c.txt</Key>"));

        ResponseEntity<String> withPrefix = rest.getForEntity(
                "/p2list?list-type=2&prefix=dir/", String.class);
        assertTrue(withPrefix.getBody().contains("<Key>dir/b.txt</Key>"));
        assertFalse(withPrefix.getBody().contains("<Key>a.txt</Key>"));
    }

    @Test
    void listV2_delimiterCommonPrefixes() {
        createBucket("p2delim");
        put("p2delim", "sample.jpg", "1");
        put("p2delim", "photos/2026/jan.jpg", "2");
        put("p2delim", "photos/2026/feb.jpg", "3");

        ResponseEntity<String> r = rest.getForEntity(
                "/p2list-none?list-type=2&delimiter=/".replace("p2list-none", "p2delim"),
                String.class);
        assertEquals(200, r.getStatusCode().value());
        assertTrue(r.getBody().contains("<Key>sample.jpg</Key>"));
        assertTrue(r.getBody().contains("<CommonPrefixes>"));
        assertTrue(r.getBody().contains("<Prefix>photos/</Prefix>"));
        // nested keys rolled up, not listed
        assertFalse(r.getBody().contains("<Key>photos/2026/jan.jpg</Key>"));
    }

    @Test
    void listV2_maxKeysAndContinuation() {
        createBucket("p2page");
        for (int i = 0; i < 5; i++) {
            put("p2page", String.format("key-%02d", i), "v" + i);
        }
        ResponseEntity<String> page1 = rest.getForEntity(
                "/p2page?list-type=2&max-keys=2", String.class);
        assertTrue(page1.getBody().contains("<IsTruncated>true</IsTruncated>"));
        assertTrue(page1.getBody().contains("<KeyCount>2</KeyCount>"));
        String token = extractTag(page1.getBody(), "NextContinuationToken");
        assertNotNull(token);

        ResponseEntity<String> page2 = rest.getForEntity(
                "/p2page?list-type=2&max-keys=2&continuation-token=" + token, String.class);
        assertTrue(page2.getBody().contains("<KeyCount>2</KeyCount>"));
        String token2 = extractTag(page2.getBody(), "NextContinuationToken");
        assertNotNull(token2);

        ResponseEntity<String> page3 = rest.getForEntity(
                "/p2page?list-type=2&max-keys=2&continuation-token=" + token2, String.class);
        assertTrue(page3.getBody().contains("<IsTruncated>false</IsTruncated>"));
        assertTrue(page3.getBody().contains("<KeyCount>1</KeyCount>"));
    }

    @Test
    void listV2_startAfter() {
        createBucket("p2start");
        put("p2start", "a", "1");
        put("p2start", "b", "2");
        put("p2start", "c", "3");
        ResponseEntity<String> r = rest.getForEntity(
                "/p2start?list-type=2&start-after=a", String.class);
        assertFalse(r.getBody().contains("<Key>a</Key>"));
        assertTrue(r.getBody().contains("<Key>b</Key>"));
    }

    @Test
    void listV1_markerAndNextMarker() {
        createBucket("p2v1");
        put("p2v1", "a", "1");
        put("p2v1", "b", "2");
        put("p2v1", "c", "3");
        ResponseEntity<String> r = rest.getForEntity("/p2v1?max-keys=1", String.class);
        assertTrue(r.getBody().contains("<Marker></Marker>"));
        assertTrue(r.getBody().contains("<IsTruncated>true</IsTruncated>"));
        assertEquals("a", extractTag(r.getBody(), "NextMarker"));
        assertTrue(r.getBody().contains("<Key>a</Key>"));
        ResponseEntity<String> page2 = rest.getForEntity("/p2v1?max-keys=1&marker=a", String.class);
        assertTrue(page2.getBody().contains("<Key>b</Key>"));
        assertFalse(page2.getBody().contains("<Key>a</Key>"));
    }

    @Test
    void listV2_encodingTypeUrl() {
        createBucket("p2enc");
        put("p2enc", "sp ace(1).txt", "1");
        ResponseEntity<String> r = rest.getForEntity(
                "/p2enc?list-type=2&encoding-type=url", String.class);
        assertTrue(r.getBody().contains("<Key>sp%20ace%281%29.txt</Key>"));
        assertTrue(r.getBody().contains("<EncodingType>url</EncodingType>"));
    }

    @Test
    void list_missingBucket_404() {
        ResponseEntity<String> r = rest.getForEntity("/p2-no-bucket-xyz?list-type=2", String.class);
        assertEquals(404, r.getStatusCode().value());
        assertTrue(r.getBody().contains("NoSuchBucket"));
    }

    // ==================== 响应头覆盖 ====================

    @Test
    void getObject_responseHeaderOverride() {
        createBucket("p2ovr");
        put("p2ovr", "a.txt", "payload");
        // 用完整 URI 避免 RestTemplate 对已编码 URL 二次编码
        String port = String.valueOf(rest.getRestTemplate().getUriTemplateHandler().toString().isEmpty()
                ? 0 : findPort());
        java.net.URI uri = java.net.URI.create(
                "http://localhost:" + port + "/p2ovr/a.txt?response-content-type=application%2Fpdf"
                        + "&response-content-disposition=attachment%3B%20filename%3D%22x.pdf%22");
        ResponseEntity<String> r = rest.getForEntity(uri, String.class);
        assertEquals(200, r.getStatusCode().value());
        assertEquals("application/pdf", r.getHeaders().getFirst("Content-Type"));
        assertEquals("attachment; filename=\"x.pdf\"",
                r.getHeaders().getFirst("Content-Disposition"));
    }

    private int findPort() {
        // TestRestTemplate 的根 URI 已含端口
        String root = rest.getRootUri();
        int idx = root.lastIndexOf(':');
        return Integer.parseInt(root.substring(idx + 1).replaceAll("[^0-9]", ""));
    }

    // ==================== checksum ====================

    @Test
    void putObject_sha256Checksum_ok() throws Exception {
        createBucket("p2sha");
        String payload = "hello checksum";
        String sha256b64 = java.util.Base64.getEncoder().encodeToString(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(payload.getBytes(StandardCharsets.UTF_8)));
        HttpHeaders h = new HttpHeaders();
        h.set("x-amz-checksum-sha256", sha256b64);
        ResponseEntity<Void> r = rest.exchange("/p2sha/a.bin", HttpMethod.PUT,
                new HttpEntity<>(payload, h), Void.class);
        assertEquals(200, r.getStatusCode().value());
        assertNotNull(r.getHeaders().getFirst("x-amz-checksum-sha256"));
    }

    @Test
    void putObject_sha256Checksum_mismatch_400() {
        createBucket("p2sha2");
        HttpHeaders h = new HttpHeaders();
        h.set("x-amz-checksum-sha256", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        ResponseEntity<String> r = rest.exchange("/p2sha2/a.bin", HttpMethod.PUT,
                new HttpEntity<>("mismatched", h), String.class);
        assertEquals(400, r.getStatusCode().value());
        assertTrue(r.getBody().contains("BadDigest"));
    }

    // ==================== helpers ====================

    private static String extractTag(String xml, String tag) {
        int start = xml.indexOf("<" + tag + ">");
        if (start < 0) {
            return null;
        }
        int contentStart = start + tag.length() + 2;
        int end = xml.indexOf("</" + tag + ">", contentStart);
        return end < 0 ? null : xml.substring(contentStart, end);
    }
}
