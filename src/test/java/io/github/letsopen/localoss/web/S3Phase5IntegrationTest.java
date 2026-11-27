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
 * Phase 5 集成测试：BucketLocation / CORS / Policy / PublicAccessBlock / OwnershipControls。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = LocalOssApplication.class)
class S3Phase5IntegrationTest {

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

    // ==================== GetBucketLocation ====================

    @Test
    void getBucketLocation_usEast1_returnsEmptyConstraint() {
        props.setRegion("us-east-1");
        createBucket("p5loc");
        ResponseEntity<String> r = rest.getForEntity("/p5loc?location", String.class);
        assertEquals(200, r.getStatusCode().value());
        assertTrue(r.getBody().contains("<LocationConstraint"));
        assertTrue(r.getBody().endsWith("/>\n") || r.getBody().contains("/>"));
    }

    @Test
    void getBucketLocation_otherRegion_returnsConstraint() {
        props.setRegion("us-west-2");
        createBucket("p5loc2");
        ResponseEntity<String> r = rest.getForEntity("/p5loc2?location", String.class);
        assertEquals(200, r.getStatusCode().value());
        assertTrue(r.getBody().contains("us-west-2"));
        props.setRegion("us-east-1");
    }

    @Test
    void getBucketLocation_missingBucket_404() {
        ResponseEntity<String> r = rest.getForEntity("/p5-no-bucket?location", String.class);
        assertEquals(404, r.getStatusCode().value());
    }

    // ==================== CORS ====================

    @Test
    void bucketCors_putGetDelete() {
        createBucket("p5cors");
        String corsXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <CORSConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <CORSRule>
                    <AllowedOrigin>*</AllowedOrigin>
                    <AllowedMethod>GET</AllowedMethod>
                  </CORSRule>
                </CORSConfiguration>""";
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_XML);
        ResponseEntity<Void> put = rest.exchange("/p5cors?cors", HttpMethod.PUT,
                new HttpEntity<>(corsXml, h), Void.class);
        assertEquals(200, put.getStatusCode().value());

        ResponseEntity<String> get = rest.getForEntity(
                java.net.URI.create("http://localhost:" + findPort() + "/p5cors?cors"),
                String.class);
        assertEquals(200, get.getStatusCode().value());
        // 原样往返：GET 内容包含 PUT 时的具体规则
        assertTrue(get.getBody().contains("<AllowedOrigin>*</AllowedOrigin>"), get.getBody());
        assertTrue(get.getBody().contains("<AllowedMethod>GET</AllowedMethod>"));

        ResponseEntity<Void> del = rest.exchange("/p5cors?cors", HttpMethod.DELETE,
                HttpEntity.EMPTY, Void.class);
        assertEquals(204, del.getStatusCode().value());
        ResponseEntity<String> after = rest.getForEntity("/p5cors?cors", String.class);
        // 删除后配置不存在 → S3 语义 NoSuchCORSConfiguration（本地用 404 包装）
        assertEquals(404, after.getStatusCode().value());
    }

    // ==================== Policy ====================

    @Test
    void bucketPolicy_putGetDelete() {
        createBucket("p5pol");
        String policy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Principal":"*","Action":"s3:GetObject","Resource":"arn:aws:s3:::p5pol/*"}]}""";
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Void> put = rest.exchange("/p5pol?policy", HttpMethod.PUT,
                new HttpEntity<>(policy, h), Void.class);
        assertEquals(200, put.getStatusCode().value());

        ResponseEntity<String> get = rest.getForEntity("/p5pol?policy", String.class);
        assertEquals(200, get.getStatusCode().value());
        assertTrue(get.getBody().contains("s3:GetObject"));

        ResponseEntity<Void> del = rest.exchange("/p5pol?policy", HttpMethod.DELETE,
                HttpEntity.EMPTY, Void.class);
        assertEquals(204, del.getStatusCode().value());
    }

    @Test
    void bucketPolicy_invalidJson_400() {
        createBucket("p5pol2");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> r = rest.exchange("/p5pol2?policy", HttpMethod.PUT,
                new HttpEntity<>("not-json", h), String.class);
        assertEquals(400, r.getStatusCode().value());
        assertTrue(r.getBody().contains("MalformedPolicy"));
    }

    // ==================== PublicAccessBlock / OwnershipControls ====================

    private int findPort() {
        String root = rest.getRootUri();
        return Integer.parseInt(root.substring(root.lastIndexOf(':') + 1)
                .replaceAll("[^0-9]", ""));
    }

    @Test
    void publicAccessBlock_putGetDelete() {
        createBucket("p5pab");
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <PublicAccessBlockConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <BlockPublicAcls>true</BlockPublicAcls>
                </PublicAccessBlockConfiguration>""";
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_XML);
        assertEquals(200, rest.exchange("/p5pab?publicAccessBlock", HttpMethod.PUT,
                new HttpEntity<>(xml, h), Void.class).getStatusCode().value());
        ResponseEntity<String> pabGet = rest.getForEntity(
                java.net.URI.create("http://localhost:" + findPort()
                        + "/p5pab?publicAccessBlock"), String.class);
        assertEquals(200, pabGet.getStatusCode().value());
        assertTrue(pabGet.getBody().contains("<BlockPublicAcls>true</BlockPublicAcls>"),
                pabGet.getBody());
        assertEquals(204, rest.exchange("/p5pab?publicAccessBlock", HttpMethod.DELETE,
                HttpEntity.EMPTY, Void.class).getStatusCode().value());
    }

    @Test
    void requestPayment_returnsBucketOwner() {
        createBucket("p5rp");
        ResponseEntity<String> r = rest.getForEntity("/p5rp?requestPayment", String.class);
        assertEquals(200, r.getStatusCode().value());
        assertTrue(r.getBody().contains("<Payer>BucketOwner</Payer>"));
    }
}
