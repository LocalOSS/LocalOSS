package io.github.letsopen.localoss.web;

import io.github.letsopen.localoss.LocalOssApplication;
import io.github.letsopen.localoss.config.SystemProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 HTTP 集成测试（计划 8.2 节）。
 *
 * <p>注意：Phase 1 暂不带 SigV4 签名（Phase 1 认证部分留待 Phase 1.5 / Phase 3 完善），
 * 这里直接验证路由、存储、错误 XML、Access Mode 等协议行为。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = LocalOssApplication.class)
class S3ControllerIntegrationTest {

    @TempDir
    static Path dataDir;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    SystemProperties props;

    private final String base = "";

    @BeforeEach
    void setUp() throws Exception {
        dataDir = Files.createTempDirectory("localoss-it");
        props.setDataPath(dataDir.toString() + "/");
        props.setAccessMode(io.github.letsopen.localoss.config.AccessMode.READ_WRITE);
        // Phase 1-3 HTTP 行为测试暂不签名（Phase 3b 认证专项测试覆盖）
        props.setAccessKey("");
        props.setSecretKey("");
    }

    @AfterEach
    void tearDown() {
        deleteRecursively(dataDir);
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

    // ==================== Bucket ====================

    @Test
    void createBucket_returns200WithLocation() {
        ResponseEntity<Void> r = rest.exchange("/it-bucket", HttpMethod.PUT,
                HttpEntity.EMPTY, Void.class);
        assertEquals(200, r.getStatusCode().value());
        assertEquals("/it-bucket", r.getHeaders().getFirst("Location"));
        assertTrue(Files.isDirectory(dataDir.resolve("it-bucket")));
    }

    @Test
    void createBucketTwice_returns409() {
        rest.exchange("/it-dupe", HttpMethod.PUT, HttpEntity.EMPTY, Void.class);
        ResponseEntity<String> r = rest.exchange("/it-dupe", HttpMethod.PUT,
                HttpEntity.EMPTY, String.class);
        assertEquals(409, r.getStatusCode().value());
        assertTrue(r.getBody().contains("BucketAlreadyOwnedByYou"));
        assertTrue(r.getBody().contains("<Error>"));
    }

    @Test
    void createBucket_invalidName_returns400Xml() {
        ResponseEntity<String> r = rest.exchange("/ab", HttpMethod.PUT,
                HttpEntity.EMPTY, String.class);
        assertEquals(400, r.getStatusCode().value());
        assertTrue(r.getBody().contains("InvalidBucketName"));
    }

    @Test
    void headBucket_exists_200_missing_404() {
        rest.exchange("/it-hb", HttpMethod.PUT, HttpEntity.EMPTY, Void.class);
        ResponseEntity<Void> ok = rest.exchange("/it-hb", HttpMethod.HEAD,
                HttpEntity.EMPTY, Void.class);
        assertEquals(200, ok.getStatusCode().value());
        ResponseEntity<Void> no = rest.exchange("/no-such-bucket-xyz", HttpMethod.HEAD,
                HttpEntity.EMPTY, Void.class);
        assertEquals(404, no.getStatusCode().value());
    }

    @Test
    void deleteBucket_empty_204_nonEmpty_409() {
        rest.exchange("/it-del", HttpMethod.PUT, HttpEntity.EMPTY, Void.class);
        ResponseEntity<Void> ok = rest.exchange("/it-del", HttpMethod.DELETE,
                HttpEntity.EMPTY, Void.class);
        assertEquals(204, ok.getStatusCode().value());

        rest.exchange("/it-del2", HttpMethod.PUT, HttpEntity.EMPTY, Void.class);
        rest.exchange("/it-del2/a.txt", HttpMethod.PUT,
                new HttpEntity<>("hello"), Void.class);
        ResponseEntity<String> bad = rest.exchange("/it-del2", HttpMethod.DELETE,
                HttpEntity.EMPTY, String.class);
        assertEquals(409, bad.getStatusCode().value());
        assertTrue(bad.getBody().contains("BucketNotEmpty"));
    }

    @Test
    void listBuckets_xml() {
        rest.exchange("/it-list", HttpMethod.PUT, HttpEntity.EMPTY, Void.class);
        ResponseEntity<String> r = rest.getForEntity("/", String.class);
        assertEquals(200, r.getStatusCode().value());
        assertTrue(r.getBody().contains("ListAllMyBucketsResult"));
        assertTrue(r.getBody().contains("http://s3.amazonaws.com/doc/2006-03-01/"));
        assertTrue(r.getBody().contains("it-list"));
    }

    @Test
    void getBucketLocation_returnsRegion() {
        rest.exchange("/it-loc", HttpMethod.PUT, HttpEntity.EMPTY, Void.class);
        ResponseEntity<String> r = rest.getForEntity("/it-loc?location", String.class);
        assertEquals(200, r.getStatusCode().value());
        assertTrue(r.getBody().contains("LocationConstraint"));
        // 默认 region us-east-1 时 LocationConstraint 为空元素
        assertTrue(r.getBody().contains("xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\""));
    }

    // ==================== Object ====================

    @Test
    void putGetHeadDelete_objectLifecycle() {
        rest.exchange("/it-obj", HttpMethod.PUT, HttpEntity.EMPTY, Void.class);
        String payload = "phase1 payload";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        ResponseEntity<Void> put = rest.exchange("/it-obj/dir/a.txt", HttpMethod.PUT,
                new HttpEntity<>(payload, headers), Void.class);
        assertEquals(200, put.getStatusCode().value());
        assertNotNull(put.getHeaders().getFirst("ETag"));

        ResponseEntity<byte[]> get = rest.getForEntity("/it-obj/dir/a.txt", byte[].class);
        assertEquals(200, get.getStatusCode().value());
        assertEquals(payload, new String(get.getBody(), StandardCharsets.UTF_8));
        assertEquals(MediaType.TEXT_PLAIN, get.getHeaders().getContentType());

        ResponseEntity<Void> head = rest.exchange("/it-obj/dir/a.txt", HttpMethod.HEAD,
                HttpEntity.EMPTY, Void.class);
        assertEquals(200, head.getStatusCode().value());
        assertEquals(String.valueOf(payload.length()),
                head.getHeaders().getFirst("Content-Length"));
        assertNotNull(head.getHeaders().getFirst("ETag"));

        ResponseEntity<Void> del = rest.exchange("/it-obj/dir/a.txt", HttpMethod.DELETE,
                HttpEntity.EMPTY, Void.class);
        assertEquals(204, del.getStatusCode().value());

        ResponseEntity<String> gone = rest.getForEntity("/it-obj/dir/a.txt", String.class);
        assertEquals(404, gone.getStatusCode().value());
        assertTrue(gone.getBody().contains("NoSuchKey"));
    }

    @Test
    void getObject_range() {
        rest.exchange("/it-range", HttpMethod.PUT, HttpEntity.EMPTY, Void.class);
        rest.exchange("/it-range/a.bin", HttpMethod.PUT,
                new HttpEntity<>("0123456789"), Void.class);
        HttpHeaders h = new HttpHeaders();
        h.set("Range", "bytes=2-5");
        ResponseEntity<byte[]> r = rest.exchange("/it-range/a.bin", HttpMethod.GET,
                new HttpEntity<>(h), byte[].class);
        assertEquals(206, r.getStatusCode().value());
        assertEquals("2345", new String(r.getBody(), StandardCharsets.UTF_8));
        assertEquals("bytes 2-5/10", r.getHeaders().getFirst("Content-Range"));
    }

    @Test
    void getObject_invalidRange_416() {
        rest.exchange("/it-rng2", HttpMethod.PUT, HttpEntity.EMPTY, Void.class);
        rest.exchange("/it-rng2/a.bin", HttpMethod.PUT,
                new HttpEntity<>("0123456789"), Void.class);
        HttpHeaders h = new HttpHeaders();
        h.set("Range", "bytes=100-200");
        ResponseEntity<String> r = rest.exchange("/it-rng2/a.bin", HttpMethod.GET,
                new HttpEntity<>(h), String.class);
        assertEquals(416, r.getStatusCode().value());
        assertTrue(r.getBody().contains("InvalidRange"));
    }

    @Test
    void putObject_withMetadata_echoedOnGet() {
        rest.exchange("/it-meta", HttpMethod.PUT, HttpEntity.EMPTY, Void.class);
        HttpHeaders h = new HttpHeaders();
        h.set("x-amz-meta-author", "bob");
        rest.exchange("/it-meta/a.txt", HttpMethod.PUT, new HttpEntity<>("v", h), Void.class);
        ResponseEntity<Void> head = rest.exchange("/it-meta/a.txt", HttpMethod.HEAD,
                HttpEntity.EMPTY, Void.class);
        assertEquals("bob", head.getHeaders().getFirst("x-amz-meta-author"));
    }

    @Test
    void putObject_contentMd5Mismatch_400() {
        rest.exchange("/it-md5", HttpMethod.PUT, HttpEntity.EMPTY, Void.class);
        HttpHeaders h = new HttpHeaders();
        // MD5 of "x" is not this value
        h.set("Content-MD5", java.util.Base64.getEncoder().encodeToString(new byte[16]));
        ResponseEntity<String> r = rest.exchange("/it-md5/a.txt", HttpMethod.PUT,
                new HttpEntity<>("x", h), String.class);
        assertEquals(400, r.getStatusCode().value());
        assertTrue(r.getBody().contains("BadDigest"));
    }

    @Test
    void putObject_conflict_409() {
        rest.exchange("/it-conf", HttpMethod.PUT, HttpEntity.EMPTY, Void.class);
        rest.exchange("/it-conf/a", HttpMethod.PUT, new HttpEntity<>("v"), Void.class);
        ResponseEntity<String> r = rest.exchange("/it-conf/a/b", HttpMethod.PUT,
                new HttpEntity<>("v2"), String.class);
        assertEquals(409, r.getStatusCode().value());
        assertTrue(r.getBody().contains("KeyConflict"));
    }

    @Test
    void listObjects_showsKeys() {
        rest.exchange("/it-ls", HttpMethod.PUT, HttpEntity.EMPTY, Void.class);
        rest.exchange("/it-ls/a.txt", HttpMethod.PUT, new HttpEntity<>("1"), Void.class);
        rest.exchange("/it-ls/b.txt", HttpMethod.PUT, new HttpEntity<>("2"), Void.class);
        ResponseEntity<String> r = rest.getForEntity("/it-ls", String.class);
        assertEquals(200, r.getStatusCode().value());
        assertTrue(r.getBody().contains("ListBucketResult"));
        assertTrue(r.getBody().contains("<Key>a.txt</Key>"));
        assertTrue(r.getBody().contains("<Key>b.txt</Key>"));
        assertTrue(r.getBody().contains("<ETag>"));
    }

    @Test
    void listObjects_missingBucket_404() {
        ResponseEntity<String> r = rest.getForEntity("/ghost-bucket-xyz", String.class);
        assertEquals(404, r.getStatusCode().value());
        assertTrue(r.getBody().contains("NoSuchBucket"));
    }

    // ==================== access mode ====================

    @Test
    void readOnlyMode_rejectsWrites() {
        rest.exchange("/it-ro", HttpMethod.PUT, HttpEntity.EMPTY, Void.class);
        props.setAccessMode(io.github.letsopen.localoss.config.AccessMode.READ_ONLY);
        ResponseEntity<String> r = rest.exchange("/it-ro/a.txt", HttpMethod.PUT,
                new HttpEntity<>("x"), String.class);
        assertEquals(403, r.getStatusCode().value());
        assertTrue(r.getBody().contains("AccessDenied"));
        // read ok
        ResponseEntity<String> list = rest.getForEntity("/it-ro", String.class);
        assertEquals(200, list.getStatusCode().value());
    }

    @Test
    void writeOnlyMode_rejectsReads_allowsHead() {
        rest.exchange("/it-wo", HttpMethod.PUT, HttpEntity.EMPTY, Void.class);
        rest.exchange("/it-wo/a.txt", HttpMethod.PUT, new HttpEntity<>("x"), Void.class);
        props.setAccessMode(io.github.letsopen.localoss.config.AccessMode.WRITE_ONLY);
        ResponseEntity<String> get = rest.getForEntity("/it-wo/a.txt", String.class);
        assertEquals(403, get.getStatusCode().value());
        ResponseEntity<Void> head = rest.exchange("/it-wo/a.txt", HttpMethod.HEAD,
                HttpEntity.EMPTY, Void.class);
        assertEquals(200, head.getStatusCode().value());
    }

    @Test
    void disabledMode_returns503() {
        props.setAccessMode(io.github.letsopen.localoss.config.AccessMode.DISABLED);
        ResponseEntity<String> r = rest.exchange("/it-dis/a.txt", HttpMethod.PUT,
                new HttpEntity<>("x"), String.class);
        assertEquals(503, r.getStatusCode().value());
        assertTrue(r.getBody().contains("ServiceUnavailable"));
    }

    // ==================== misc ====================

    @Test
    void healthEndpoint_works() {
        ResponseEntity<Map> r = rest.getForEntity("/localoss-health", Map.class);
        assertEquals(200, r.getStatusCode().value());
        assertEquals("UP", r.getBody().get("status"));
    }

    @Test
    void requestIdHeader_present() {
        rest.exchange("/it-req", HttpMethod.PUT, HttpEntity.EMPTY, Void.class);
        ResponseEntity<Void> r = rest.exchange("/it-req", HttpMethod.HEAD,
                HttpEntity.EMPTY, Void.class);
        assertNotNull(r.getHeaders().getFirst("x-amz-request-id"));
        assertNotNull(r.getHeaders().getFirst("Server"));
    }

    @Test
    void specialCharactersInKey_roundtrip() {
        rest.exchange("/it-key", HttpMethod.PUT, HttpEntity.EMPTY, Void.class);
        String payload = "sp";
        ResponseEntity<Void> put = rest.exchange("/it-key/sp%20ace%2Bplus.txt",
                HttpMethod.PUT, new HttpEntity<>(payload), Void.class);
        assertEquals(200, put.getStatusCode().value());
        ResponseEntity<byte[]> get = rest.getForEntity("/it-key/sp%20ace%2Bplus.txt", byte[].class);
        assertEquals(payload, new String(get.getBody(), StandardCharsets.UTF_8));
    }
}
