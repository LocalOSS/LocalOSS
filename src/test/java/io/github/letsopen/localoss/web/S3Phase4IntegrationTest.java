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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 集成测试：Bucket / Object Tagging。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = LocalOssApplication.class)
class S3Phase4IntegrationTest {

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
                new HttpEntity<>(payload), Void.class).getStatusCode().value());
    }

    private String taggingXml(String... kv) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<Tagging xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">\n");
        sb.append("  <TagSet>\n");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            sb.append("    <Tag>\n");
            sb.append("      <Key>").append(kv[i]).append("</Key>\n");
            sb.append("      <Value>").append(kv[i + 1]).append("</Value>\n");
            sb.append("    </Tag>\n");
        }
        sb.append("  </TagSet>\n");
        sb.append("</Tagging>");
        return sb.toString();
    }

    private ResponseEntity<String> putTagging(String path, String xml) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_XML);
        return rest.exchange(path + "?tagging", HttpMethod.PUT,
                new HttpEntity<>(xml, h), String.class);
    }

    // ==================== Bucket Tagging ====================

    @Test
    void bucketTagging_putGetDelete() {
        createBucket("p4b");
        ResponseEntity<String> empty = rest.getForEntity("/p4b?tagging", String.class);
        assertEquals(200, empty.getStatusCode().value());
        assertTrue(empty.getBody().contains("<TagSet>"));

        ResponseEntity<String> put = putTagging("/p4b", taggingXml("env", "prod", "team", "core"));
        assertEquals(200, put.getStatusCode().value());

        ResponseEntity<String> get = rest.getForEntity("/p4b?tagging", String.class);
        assertTrue(get.getBody().contains("<Key>env</Key>"));
        assertTrue(get.getBody().contains("<Value>prod</Value>"));
        assertTrue(get.getBody().contains("<Key>team</Key>"));
        assertTrue(get.getBody().contains("<Value>core</Value>"));

        ResponseEntity<Void> del = rest.exchange("/p4b?tagging", HttpMethod.DELETE,
                HttpEntity.EMPTY, Void.class);
        assertEquals(204, del.getStatusCode().value());
        ResponseEntity<String> after = rest.getForEntity("/p4b?tagging", String.class);
        assertFalse(after.getBody().contains("<Key>env</Key>"));
    }

    @Test
    void bucketTagging_missingBucket_404() {
        ResponseEntity<String> r = rest.getForEntity("/p4-ghost-bucket?tagging", String.class);
        assertEquals(404, r.getStatusCode().value());
        assertTrue(r.getBody().contains("NoSuchBucket"));
    }

    // ==================== Object Tagging ====================

    @Test
    void objectTagging_putGetDelete() {
        createBucket("p4o");
        put("p4o", "a.txt", "data");
        ResponseEntity<String> put = putTagging("/p4o/a.txt",
                taggingXml("version", "v1", "state", "draft"));
        assertEquals(200, put.getStatusCode().value());

        ResponseEntity<String> get = rest.getForEntity("/p4o/a.txt?tagging", String.class);
        assertTrue(get.getBody().contains("<Key>version</Key>"));
        assertTrue(get.getBody().contains("<Value>v1</Value>"));

        ResponseEntity<Void> del = rest.exchange("/p4o/a.txt?tagging", HttpMethod.DELETE,
                HttpEntity.EMPTY, Void.class);
        assertEquals(204, del.getStatusCode().value());
        ResponseEntity<String> after = rest.getForEntity("/p4o/a.txt?tagging", String.class);
        assertFalse(after.getBody().contains("<Key>version</Key>"));
    }

    @Test
    void objectTagging_missingKey_404() {
        createBucket("p4o2");
        ResponseEntity<String> r = rest.getForEntity("/p4o2/no-such.txt?tagging", String.class);
        assertEquals(404, r.getStatusCode().value());
        assertTrue(r.getBody().contains("NoSuchKey"));
    }

    // ==================== x-amz-tagging on PutObject ====================

    @Test
    void putObject_withTaggingHeader() {
        createBucket("p4h");
        HttpHeaders h = new HttpHeaders();
        h.set("x-amz-tagging", "env%3Dprod%26team%3Dcore");
        ResponseEntity<Void> r = rest.exchange("/p4h/a.txt", HttpMethod.PUT,
                new HttpEntity<>("data", h), Void.class);
        assertEquals(200, r.getStatusCode().value());
        ResponseEntity<String> get = rest.getForEntity("/p4h/a.txt?tagging", String.class);
        assertTrue(get.getBody().contains("<Key>env</Key>"));
        assertTrue(get.getBody().contains("<Value>prod</Value>"));
        assertTrue(get.getBody().contains("<Key>team</Key>"));
        assertTrue(get.getBody().contains("<Value>core</Value>"));
    }

    // ==================== 标签校验 ====================

    @Test
    void tagging_tooManyTags_rejected() {
        createBucket("p4t");
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?>\n<Tagging><TagSet>\n");
        for (int i = 0; i < 51; i++) {
            xml.append("<Tag><Key>k").append(i).append("</Key><Value>v</Value></Tag>\n");
        }
        xml.append("</TagSet></Tagging>");
        ResponseEntity<String> r = putTagging("/p4t", xml.toString());
        assertEquals(400, r.getStatusCode().value());
        assertTrue(r.getBody().contains("InvalidTag"));
    }

    @Test
    void tagging_tagValueTooLong_rejected() {
        createBucket("p4t2");
        String longValue = "x".repeat(257);
        ResponseEntity<String> r = putTagging("/p4t2", taggingXml("k", longValue));
        assertEquals(400, r.getStatusCode().value());
        assertTrue(r.getBody().contains("InvalidTag"));
    }

    @Test
    void putObject_taggingCountHeader() {
        // HeadObject 返回 x-amz-tagging-count
        createBucket("p4c");
        put("p4c", "a.txt", "data");
        putTagging("/p4c/a.txt", taggingXml("a", "1", "b", "2"));
        ResponseEntity<Void> head = rest.exchange("/p4c/a.txt", HttpMethod.HEAD,
                HttpEntity.EMPTY, Void.class);
        assertEquals("2", head.getHeaders().getFirst("x-amz-tagging-count"));
    }
}
