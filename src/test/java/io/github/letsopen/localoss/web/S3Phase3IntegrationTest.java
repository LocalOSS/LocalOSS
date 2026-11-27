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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 HTTP 集成测试：Multipart upload 全生命周期。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = LocalOssApplication.class)
class S3Phase3IntegrationTest {

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

    private String initiate(String bucket, String key) {
        ResponseEntity<String> r = rest.exchange("/" + bucket + "/" + key + "?uploads",
                HttpMethod.POST, HttpEntity.EMPTY, String.class);
        assertEquals(200, r.getStatusCode().value());
        String body = r.getBody();
        assertTrue(body.contains("InitiateMultipartUploadResult"), body);
        assertTrue(body.contains("<UploadId>"), body);
        return extractTag(body, "UploadId");
    }

    private void uploadPart(String bucket, String key, String uploadId, int part, String payload) {
        ResponseEntity<Void> r = rest.exchange(
                "/" + bucket + "/" + key + "?partNumber=" + part + "&uploadId=" + uploadId,
                HttpMethod.PUT, new HttpEntity<>(payload), Void.class);
        assertEquals(200, r.getStatusCode().value(), "part " + part);
        assertNotNull(r.getHeaders().getFirst("ETag"));
    }

    private ResponseEntity<String> complete(String bucket, String key, String uploadId,
                                            int... parts) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<CompleteMultipartUpload xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">\n");
        for (int p : parts) {
            xml.append("  <Part><PartNumber>").append(p).append("</PartNumber>")
                    .append("<ETag>ignored</ETag></Part>\n");
        }
        xml.append("</CompleteMultipartUpload>");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_XML);
        return rest.exchange("/" + bucket + "/" + key + "?uploadId=" + uploadId,
                HttpMethod.POST, new HttpEntity<>(xml.toString(), h), String.class);
    }

    // ==================== lifecycle ====================

    @Test
    void multipartLifecycle_3Parts() {
        createBucket("p3mpu");
        String uploadId = initiate("p3mpu", "big.bin");

        String p1 = "a".repeat(5 * 1024 * 1024);   // 5MiB min part
        String p2 = "b".repeat(5 * 1024 * 1024);
        String p3 = "tail-data";
        uploadPart("p3mpu", "big.bin", uploadId, 1, p1);
        uploadPart("p3mpu", "big.bin", uploadId, 2, p2);
        uploadPart("p3mpu", "big.bin", uploadId, 3, p3);

        ResponseEntity<String> r = complete("p3mpu", "big.bin", uploadId, 1, 2, 3);
        assertEquals(200, r.getStatusCode().value());
        String body = r.getBody();
        assertTrue(body.contains("CompleteMultipartUploadResult"), body);
        String etag = extractTag(body, "ETag");
        assertNotNull(etag);
        assertTrue(etag.contains("-3"), "multipart ETag should have part count suffix: " + etag);

        // 校验拼接内容
        ResponseEntity<String> head = rest.exchange("/p3mpu/big.bin", HttpMethod.HEAD,
                HttpEntity.EMPTY, String.class);
        assertEquals(String.valueOf(p1.length() + p2.length() + p3.length()),
                head.getHeaders().getFirst("Content-Length"));
        assertEquals(etag.replace("&quot;", "\""), head.getHeaders().getFirst("ETag"));

        // range 校验分段
        HttpHeaders h = new HttpHeaders();
        h.set("Range", "bytes=" + p1.length() + "-" + (p1.length() + 3));
        ResponseEntity<byte[]> range = rest.exchange(URI.create(
                "http://localhost:" + findPort() + "/p3mpu/big.bin"), HttpMethod.GET,
                new HttpEntity<>(h), byte[].class);
        assertEquals(206, range.getStatusCode().value());
        assertEquals("bbbb", new String(range.getBody(), StandardCharsets.UTF_8));

        // upload 目录已清理
        assertFalse(Files.exists(dataDir.resolve("p3mpu/.localoss/uploads/" + uploadId)));
    }

    @Test
    void multipartAbort() {
        createBucket("p3abort");
        String uploadId = initiate("p3abort", "x.bin");
        uploadPart("p3abort", "x.bin", uploadId, 1, "part1");
        ResponseEntity<Void> r = rest.exchange(
                "/p3abort/x.bin?uploadId=" + uploadId, HttpMethod.DELETE,
                HttpEntity.EMPTY, Void.class);
        assertEquals(204, r.getStatusCode().value());
        assertFalse(Files.exists(dataDir.resolve("p3abort/.localoss/uploads/" + uploadId)));
        // Complete 失败
        ResponseEntity<String> c = complete("p3abort", "x.bin", uploadId, 1);
        assertEquals(404, c.getStatusCode().value());
        assertTrue(c.getBody().contains("NoSuchUpload"));
    }

    @Test
    void multipartListPartsAndUploads() {
        createBucket("p3list");
        String uploadId = initiate("p3list", "y.bin");
        uploadPart("p3list", "y.bin", uploadId, 1, "one");
        uploadPart("p3list", "y.bin", uploadId, 2, "two");

        ResponseEntity<String> lp = rest.getForEntity(
                "/p3list/y.bin?uploadId=" + uploadId, String.class);
        assertEquals(200, lp.getStatusCode().value());
        assertTrue(lp.getBody().contains("ListPartsResult"));
        assertTrue(lp.getBody().contains("<PartNumber>1</PartNumber>"));
        assertTrue(lp.getBody().contains("<PartNumber>2</PartNumber>"));

        ResponseEntity<String> lu = rest.getForEntity("/p3list?uploads", String.class);
        assertEquals(200, lu.getStatusCode().value());
        assertTrue(lu.getBody().contains("ListMultipartUploadsResult"));
        assertTrue(lu.getBody().contains("<UploadId>" + uploadId + "</UploadId>"));
    }

    @Test
    void multipartOutOfOrder_partsAccepted_completeRejectsSmallParts() {
        createBucket("p3ooo");
        String uploadId = initiate("p3ooo", "z.bin");
        // 分片可乱序上传
        uploadPart("p3ooo", "z.bin", uploadId, 3, "C");
        uploadPart("p3ooo", "z.bin", uploadId, 1, "A");
        uploadPart("p3ooo", "z.bin", uploadId, 2, "B");
        // 前两片小于 5MiB 最小分片 → EntityTooSmall（S3 规范）
        ResponseEntity<String> r = complete("p3ooo", "z.bin", uploadId, 1, 2, 3);
        assertEquals(400, r.getStatusCode().value());
        assertTrue(r.getBody().contains("EntityTooSmall"));
    }

    @Test
    void multipartPartTooSmall_rejected() {
        createBucket("p3small");
        String uploadId = initiate("p3small", "s.bin");
        uploadPart("p3small", "s.bin", uploadId, 1, "tiny");
        uploadPart("p3small", "s.bin", uploadId, 2, "tiny2");
        ResponseEntity<String> r = complete("p3small", "s.bin", uploadId, 1, 2);
        assertEquals(400, r.getStatusCode().value());
        assertTrue(r.getBody().contains("EntityTooSmall"));
    }

    @Test
    void multipartInvalidPartNumber_rejected() {
        createBucket("p3inv");
        String uploadId = initiate("p3inv", "i.bin");
        ResponseEntity<String> r = rest.exchange(
                "/p3inv/i.bin?partNumber=0&uploadId=" + uploadId,
                HttpMethod.PUT, new HttpEntity<>("x"), String.class);
        assertEquals(400, r.getStatusCode().value());
        assertTrue(r.getBody().contains("InvalidArgument"));
    }

    @Test
    void multipartMissingUpload_404() {
        createBucket("p3no");
        ResponseEntity<String> lp = rest.getForEntity(
                "/p3no/k.bin?uploadId=no-such-upload", String.class);
        assertEquals(404, lp.getStatusCode().value());
        assertTrue(lp.getBody().contains("NoSuchUpload"));
    }

    @Test
    void multipartMetadataCarriedThrough() {
        createBucket("p3meta");
        HttpHeaders init = new HttpHeaders();
        init.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        init.set("x-amz-meta-owner", "carol");
        ResponseEntity<String> r = rest.exchange("/p3meta/m.bin?uploads", HttpMethod.POST,
                new HttpEntity<>(init), String.class);
        String uploadId = extractTag(r.getBody(), "UploadId");
        String p1 = "x".repeat(5 * 1024 * 1024);
        uploadPart("p3meta", "m.bin", uploadId, 1, p1);
        ResponseEntity<String> cr = complete("p3meta", "m.bin", uploadId, 1);
        assertEquals(200, cr.getStatusCode().value());
        ResponseEntity<Void> head = rest.exchange("/p3meta/m.bin", HttpMethod.HEAD,
                HttpEntity.EMPTY, Void.class);
        assertEquals("carol", head.getHeaders().getFirst("x-amz-meta-owner"));
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

    private int findPort() {
        String root = rest.getRootUri();
        int idx = root.lastIndexOf(':');
        return Integer.parseInt(root.substring(idx + 1).replaceAll("[^0-9]", ""));
    }
}
