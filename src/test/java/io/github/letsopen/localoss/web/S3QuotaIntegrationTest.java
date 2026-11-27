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
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 桶磁盘配额集成测试。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = LocalOssApplication.class)
class S3QuotaIntegrationTest {

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
        props.setQuotas(Map.of());
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

    private ResponseEntity<String> put(String bucket, String key, String payload) {
        return rest.exchange("/" + bucket + "/" + key, HttpMethod.PUT,
                new HttpEntity<>(payload), String.class);
    }

    @Test
    void quotaEnforced_thenDeleteFreesSpace() {
        props.setQuotas(Map.of("q-bkt", "10KB"));
        assertEquals(200, rest.exchange("/q-bkt", HttpMethod.PUT, HttpEntity.EMPTY,
                Void.class).getStatusCode().value());

        // 5KB 可以
        assertEquals(200, put("q-bkt", "a.bin", "x".repeat(5 * 1024)).getStatusCode().value());
        // 再写 6KB → 超限（5KB 已占）
        ResponseEntity<String> over = put("q-bkt", "b.bin", "y".repeat(6 * 1024));
        assertEquals(400, over.getStatusCode().value());
        assertTrue(over.getBody().contains("EntityTooLarge"), over.getBody());

        // 删除后腾出空间
        assertEquals(204, rest.exchange("/q-bkt/a.bin", HttpMethod.DELETE,
                HttpEntity.EMPTY, Void.class).getStatusCode().value());
        // 现在可以写 6KB
        assertEquals(200, put("q-bkt", "b.bin", "y".repeat(6 * 1024)).getStatusCode().value());
    }

    @Test
    void overwriteCountsNet() {
        props.setQuotas(Map.of("q-bkt2", "8KB"));
        assertEquals(200, rest.exchange("/q-bkt2", HttpMethod.PUT, HttpEntity.EMPTY,
                Void.class).getStatusCode().value());
        assertEquals(200, put("q-bkt2", "a.bin", "x".repeat(6 * 1024)).getStatusCode().value());
        // 覆盖写 7KB：净增 1KB，不应超限
        assertEquals(200, put("q-bkt2", "a.bin", "y".repeat(7 * 1024)).getStatusCode().value());
    }

    @Test
    void unconfiguredBucket_unlimited() {
        // 不设置配额
        assertEquals(200, rest.exchange("/q-free", HttpMethod.PUT, HttpEntity.EMPTY,
                Void.class).getStatusCode().value());
        assertEquals(200, put("q-free", "big.bin", "z".repeat(512 * 1024)).getStatusCode().value());
    }

    @Test
    void humanReadableQuota() {
        props.setQuotas(Map.of("q-bkt3", "1KB"));
        assertEquals(200, rest.exchange("/q-bkt3", HttpMethod.PUT, HttpEntity.EMPTY,
                Void.class).getStatusCode().value());
        assertEquals(200, put("q-bkt3", "ok.txt", "x".repeat(512)).getStatusCode().value());
        assertEquals(400, put("q-bkt3", "over.txt", "x".repeat(2048)).getStatusCode().value());
    }
}
