package io.github.letsopen.localoss.storage;

import io.github.letsopen.localoss.config.SystemProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lifecycle 执行器测试（Expiration + AbortIncompleteMultipartUpload）。
 */
class LifecycleExecutorTest {

    @TempDir
    Path tmp;

    private SystemProperties props;
    private StorageEngine storage;
    private LifecycleExecutor lifecycle;
    private MultipartStore multipartStore;

    @BeforeEach
    void setUp() {
        props = new SystemProperties();
        props.setDataPath(tmp.toString() + "/");
        KeyValidator kv = new KeyValidator(props);
        MetadataStore ms = new MetadataStore(props);
        LockManager lm = new LockManager(props);
        storage = new StorageEngine(props, kv, ms, lm, new QuotaManager(props, kv));
        multipartStore = new MultipartStore(props, kv, storage, ms, lm);
        lifecycle = new LifecycleExecutor(props, kv, storage, ms, multipartStore, lm);
    }

    private void put(String bucket, String key, String content) {
        storage.putObject(bucket, key,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                null, null, null, null);
    }

    private void setRules(String bucket, List<Map<String, Object>> rules) {
        Path bucketDir = Path.of(props.getDataPath(), bucket);
        Map<String, Object> cfg = storage.getBucketConfig(bucketDir);
        cfg.put("lifecycle", rules);
        storage.writeBucketConfig(bucketDir, cfg);
    }

    private void ageFile(String bucket, String key, int daysOld) throws Exception {
        Path f = Path.of(props.getDataPath(), bucket, key);
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.from(
                java.time.Instant.now().minusSeconds((long) daysOld * 86400)));
    }

    @Test
    void expiration_deletesOldObjectsMatchingPrefix() throws Exception {
        storage.createBucket("lc-b1");
        put("lc-b1", "logs/old.txt", "old");
        put("lc-b1", "logs/new.txt", "new");
        put("lc-b1", "data/old.txt", "data-old");

        ageFile("lc-b1", "logs/old.txt", 40);
        ageFile("lc-b1", "logs/new.txt", 2);
        ageFile("lc-b1", "data/old.txt", 40);

        setRules("lc-b1", List.of(Map.of(
                "id", "r1", "prefix", "logs/", "expirationDays", 30)));

        int deleted = lifecycle.run();
        assertEquals(1, deleted);
        assertFalse(Files.exists(Path.of(props.getDataPath(), "lc-b1", "logs", "old.txt")));
        assertTrue(Files.exists(Path.of(props.getDataPath(), "lc-b1", "logs", "new.txt")));
        assertTrue(Files.exists(Path.of(props.getDataPath(), "lc-b1", "data", "old.txt")),
                "prefix 外的对象不受影响");
    }

    @Test
    void expiration_byDate_future_notDeleted() throws Exception {
        storage.createBucket("lc-b2");
        put("lc-b2", "a.txt", "x");
        ageFile("lc-b2", "a.txt", 1);
        setRules("lc-b2", List.of(Map.of(
                "id", "r", "prefix", "",
                "expirationDate", java.time.Instant.now().plusSeconds(86400).toString())));
        int deleted = lifecycle.run();
        assertEquals(0, deleted);
        assertTrue(Files.exists(Path.of(props.getDataPath(), "lc-b2", "a.txt")));
    }

    @Test
    void expiration_noRules_noop() throws Exception {
        storage.createBucket("lc-b3");
        put("lc-b3", "a.txt", "x");
        assertEquals(0, lifecycle.run());
        assertTrue(Files.exists(Path.of(props.getDataPath(), "lc-b3", "a.txt")));
    }

    @Test
    void abortMpu_deletesExpiredUploads() throws Exception {
        storage.createBucket("lc-b4");
        String uploadId = multipartStore.createUpload("lc-b4", "big.bin", null, null);
        multipartStore.uploadPart("lc-b4", "big.bin", uploadId, 1,
                new ByteArrayInputStream("part".getBytes(StandardCharsets.UTF_8)));

        // 把 upload.json 的 initiated 改到 10 天前
        Path uploadJson = Path.of(props.getDataPath(), "lc-b4", ".localoss", "uploads",
                uploadId, "upload.json");
        String json = Files.readString(uploadJson, StandardCharsets.UTF_8);
        String newJson = json.replace(java.time.Instant.now().toString().substring(0, 20),
                java.time.Instant.now().minusSeconds(10L * 86400).toString().substring(0, 20));
        Files.writeString(uploadJson, newJson, StandardCharsets.UTF_8);

        setRules("lc-b4", List.of(Map.of(
                "id", "r", "prefix", "", "abortIncompleteMpuDays", 7)));

        int aborted = lifecycle.run();
        assertEquals(1, aborted);
        assertFalse(Files.exists(uploadJson.getParent()));
    }

    @Test
    void abortMpu_prefixScoped() throws Exception {
        storage.createBucket("lc-b5");
        String upA = multipartStore.createUpload("lc-b5", "logs/a.bin", null, null);
        String upB = multipartStore.createUpload("lc-b5", "data/b.bin", null, null);
        // 都改成 10 天前
        for (String up : new String[]{upA, upB}) {
            Path json = Path.of(props.getDataPath(), "lc-b5", ".localoss", "uploads", up, "upload.json");
            String content = Files.readString(json, StandardCharsets.UTF_8);
            content = content.replace(java.time.Instant.now().toString().substring(0, 20),
                    java.time.Instant.now().minusSeconds(10L * 86400).toString().substring(0, 20));
            Files.writeString(json, content, StandardCharsets.UTF_8);
        }
        setRules("lc-b5", List.of(Map.of(
                "id", "r", "prefix", "logs/", "abortIncompleteMpuDays", 7)));
        int aborted = lifecycle.run();
        assertEquals(1, aborted);
        assertFalse(Files.exists(Path.of(props.getDataPath(), "lc-b5", ".localoss", "uploads", upA)));
        assertTrue(Files.exists(Path.of(props.getDataPath(), "lc-b5", ".localoss", "uploads", upB)));
    }

    @Test
    void directoryObjects_neverExpired() throws Exception {
        storage.createBucket("lc-b6");
        storage.putObject("lc-b6", "dir/",
                new ByteArrayInputStream(new byte[0]), null, null, null, null);
        setRules("lc-b6", List.of(Map.of("id", "r", "prefix", "", "expirationDays", 1)));
        assertEquals(0, lifecycle.run());
        assertTrue(Files.isDirectory(Path.of(props.getDataPath(), "lc-b6", "dir")));
    }
}
