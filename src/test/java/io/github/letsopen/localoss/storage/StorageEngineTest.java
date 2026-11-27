package io.github.letsopen.localoss.storage;

import io.github.letsopen.localoss.config.SystemProperties;
import io.github.letsopen.localoss.error.S3Exception;
import io.github.letsopen.localoss.error.S3ErrorCode;
import io.github.letsopen.localoss.model.ObjectMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StorageEngine 单元测试（Phase 1 核心）。
 */
class StorageEngineTest {

    @TempDir
    Path tmp;

    private SystemProperties props;
    private StorageEngine storage;

    @BeforeEach
    void setUp() {
        props = new SystemProperties();
        props.setDataPath(tmp.toString() + "/");
        KeyValidator kv = new KeyValidator(props);
        MetadataStore ms = new MetadataStore(props);
        LockManager lm = new LockManager(props);
        storage = new StorageEngine(props, kv, ms, lm, new QuotaManager(props, kv));
    }

    // ==================== bucket ====================

    @Test
    void createAndListBuckets() {
        storage.createBucket("alpha");
        storage.createBucket("beta");
        var buckets = storage.listBuckets();
        assertTrue(buckets.contains("alpha"));
        assertTrue(buckets.contains("beta"));
    }

    @Test
    void bucketExists() {
        assertFalse(storage.bucketExists("nope"));
        storage.createBucket("yes");
        assertTrue(storage.bucketExists("yes"));
    }

    @Test
    void deleteBucket_empty_ok() {
        storage.createBucket("bkt1");
        storage.deleteBucket("bkt1");
        assertFalse(storage.bucketExists("bkt1"));
    }

    @Test
    void deleteBucket_nonEmpty_rejected() {
        storage.createBucket("bkt2");
        storage.putObject("bkt2", "a.txt",
                new ByteArrayInputStream("hi".getBytes(StandardCharsets.UTF_8)),
                null, null, null, null);
        S3Exception e = assertThrows(S3Exception.class, () -> storage.deleteBucket("bkt2"));
        assertEquals(S3ErrorCode.BUCKET_NOT_EMPTY, e.getErrorCode());
    }

    @Test
    void deleteBucket_nonEmpty_forceOk() {
        props.setForceDeleteBucket(true);
        storage.createBucket("bkt3");
        storage.putObject("bkt3", "a.txt",
                new ByteArrayInputStream("hi".getBytes(StandardCharsets.UTF_8)),
                null, null, null, null);
        storage.deleteBucket("bkt3");
        assertFalse(storage.bucketExists("bkt3"));
    }

    @Test
    void deleteBucket_missing_404() {
        S3Exception e = assertThrows(S3Exception.class, () -> storage.deleteBucket("ghost"));
        assertEquals(S3ErrorCode.NO_SUCH_BUCKET, e.getErrorCode());
    }

    // ==================== put/get ====================

    @Test
    void putObject_writesFileAndMeta() throws IOException {
        storage.createBucket("bkt-pb");
        byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
        ObjectMetadata meta = storage.putObject("bkt-pb", "dir/a.txt",
                new ByteArrayInputStream(data), Map.of("Content-Type", "text/plain"),
                Map.of("author", "alice"), null, null);

        Path file = Path.of(props.getDataPath(), "bkt-pb", "dir", "a.txt");
        assertTrue(Files.exists(file));
        assertArrayEquals(data, Files.readAllBytes(file));
        assertEquals(data.length, meta.getSize());
        assertEquals("text/plain", meta.getContentType());
        assertEquals("alice", meta.getUserMetadata().get("author"));
        assertTrue(meta.getEtag().startsWith("\""));
        // MD5 of "hello world" = 5eb63bbbe01eeed093cb22bb8f5acdc3
        assertEquals("\"5eb63bbbe01eeed093cb22bb8f5acdc3\"", meta.getEtag());
    }

    @Test
    void putObject_withoutBucket_404() {
        assertThrows(S3Exception.class, () ->
                storage.putObject("nope", "a.txt",
                        new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)),
                        null, null, null, null));
    }

    @Test
    void putObject_badChecksum_rejected() {
        storage.createBucket("bkt-pc");
        String badMd5 = java.util.Base64.getEncoder().encodeToString(new byte[16]);
        S3Exception e = assertThrows(S3Exception.class, () ->
                storage.putObject("bkt-pc", "a.txt",
                        new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)),
                        null, null, Map.of("MD5", badMd5), null));
        assertEquals(S3ErrorCode.INVALID_DIGEST, e.getErrorCode());
    }

    @Test
    void putObject_conflictPrefixFileBlocksChild() {
        storage.createBucket("bkt-pf");
        storage.putObject("bkt-pf", "a",
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)),
                null, null, null, null);
        S3Exception e = assertThrows(S3Exception.class, () ->
                storage.putObject("bkt-pf", "a/b",
                        new ByteArrayInputStream("y".getBytes(StandardCharsets.UTF_8)),
                        null, null, null, null));
        assertEquals(S3ErrorCode.KEY_CONFLICT, e.getErrorCode());
    }

    @Test
    void putObject_directoryObject() throws IOException {
        storage.createBucket("bkt-pd");
        storage.putObject("bkt-pd", "folder/",
                new ByteArrayInputStream(new byte[0]), null, null, null, null);
        Path dir = Path.of(props.getDataPath(), "bkt-pd", "folder");
        assertTrue(Files.isDirectory(dir));
    }

    @Test
    void putObject_userMetadataTooLarge_rejected() {
        storage.createBucket("bkt-pm");
        Map<String, String> bigMeta = Map.of("k", "v".repeat(3000));
        assertThrows(S3Exception.class, () ->
                storage.putObject("bkt-pm", "a.txt",
                        new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)),
                        null, bigMeta, null, null));
    }

    // ==================== getMeta ====================

    @Test
    void getMeta_backfillsForLegacyFile() throws IOException {
        // 模拟存量文件（无 meta）
        storage.createBucket("bkt-lb");
        Path file = Path.of(props.getDataPath(), "bkt-lb", "legacy.txt");
        Files.writeString(file, "legacy data");
        ObjectMetadata meta = storage.getMeta("bkt-lb", "legacy.txt", file);
        assertEquals(11, meta.getSize());
        assertEquals("\"" + io.github.letsopen.localoss.util.KeyHash.sha256Hex("x").substring(0, 0)
                + md5Hex("legacy data") + "\"", meta.getEtag());
        // 异步回填后可读
        assertEventually(() -> {
            Path metaFile = Path.of(props.getDataPath(), "bkt-lb", ".localoss", "meta",
                    io.github.letsopen.localoss.util.KeyHash.sha256Hex("legacy.txt") + ".json");
            return Files.exists(metaFile);
        }, 5000);
    }

    private static String md5Hex(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("MD5")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertEventually(java.util.function.BooleanSupplier cond, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
        }
        fail("condition not met within " + timeoutMs + "ms");
    }

    // ==================== read stream ====================

    @Test
    void openObject_full() throws IOException {
        storage.createBucket("bkt-ro");
        storage.putObject("bkt-ro", "a.bin",
                new ByteArrayInputStream("0123456789".getBytes(StandardCharsets.UTF_8)),
                null, null, null, null);
        Path target = Path.of(props.getDataPath(), "bkt-ro", "a.bin");
        try (var obj = storage.openObject(target, 0, 10)) {
            byte[] all = ((InputStream) obj.stream()).readAllBytes();
            assertEquals(10, all.length);
            assertArrayEquals("0123456789".getBytes(StandardCharsets.UTF_8), all);
        }
    }

    @Test
    void openObject_range() throws IOException {
        storage.createBucket("bkt-rr");
        storage.putObject("bkt-rr", "a.bin",
                new ByteArrayInputStream("0123456789".getBytes(StandardCharsets.UTF_8)),
                null, null, null, null);
        Path target = Path.of(props.getDataPath(), "bkt-rr", "a.bin");
        try (var obj = storage.openObject(target, 2, 4)) {
            byte[] all = ((InputStream) obj.stream()).readAllBytes();
            assertEquals(4, all.length);
            assertArrayEquals("2345".getBytes(StandardCharsets.UTF_8), all);
        }
    }

    @Test
    void openObject_rangeBeyondSize_throws() throws IOException {
        storage.createBucket("bkt-rx");
        storage.putObject("bkt-rx", "a.bin",
                new ByteArrayInputStream("0123456789".getBytes(StandardCharsets.UTF_8)),
                null, null, null, null);
        Path target = Path.of(props.getDataPath(), "bkt-rx", "a.bin");
        assertThrows(S3Exception.class, () -> storage.openObject(target, 100, 10).close());
    }

    // ==================== delete ====================

    @Test
    void deleteObject_removesFileAndMetaAndEmptyParents() throws IOException {
        storage.createBucket("bkt-do");
        storage.putObject("bkt-do", "x/y/z.txt",
                new ByteArrayInputStream("v".getBytes(StandardCharsets.UTF_8)),
                null, null, null, null);
        Path file = Path.of(props.getDataPath(), "bkt-do", "x", "y", "z.txt");
        assertTrue(Files.exists(file));
        storage.deleteObject("bkt-do", "x/y/z.txt");
        assertFalse(Files.exists(file));
        assertFalse(Files.exists(file.getParent())); // y emptied
        assertFalse(Files.exists(file.getParent().getParent())); // x emptied
        // meta also removed
        Path metaFile = Path.of(props.getDataPath(), "bkt-do", ".localoss", "meta",
                io.github.letsopen.localoss.util.KeyHash.sha256Hex("x/y/z.txt") + ".json");
        assertFalse(Files.exists(metaFile));
    }

    @Test
    void deleteObject_stopsAtNonEmptyParent() throws IOException {
        storage.createBucket("bkt-dn");
        storage.putObject("bkt-dn", "x/y/z.txt",
                new ByteArrayInputStream("v".getBytes(StandardCharsets.UTF_8)),
                null, null, null, null);
        storage.putObject("bkt-dn", "x/y/w.txt",
                new ByteArrayInputStream("v".getBytes(StandardCharsets.UTF_8)),
                null, null, null, null);
        storage.deleteObject("bkt-dn", "x/y/z.txt");
        Path y = Path.of(props.getDataPath(), "bkt-dn", "x", "y");
        assertTrue(Files.isDirectory(y));
        assertTrue(Files.exists(y.resolve("w.txt")));
    }

    @Test
    void deleteObject_missingBucket_404() {
        S3Exception e = assertThrows(S3Exception.class,
                () -> storage.deleteObject("ghost", "a.txt"));
        assertEquals(S3ErrorCode.NO_SUCH_BUCKET, e.getErrorCode());
    }

    @Test
    void updateMeta_persistsTags() throws Exception {
        storage.createBucket("bkt-tags");
        storage.putObject("bkt-tags", "a.txt",
                new ByteArrayInputStream("d".getBytes(StandardCharsets.UTF_8)),
                null, null, null, null);
        Path target = Path.of(props.getDataPath(), "bkt-tags", "a.txt");
        ObjectMetadata meta = storage.getMeta("bkt-tags", "a.txt", target);
        meta.setTags(java.util.Map.of("env", "prod"));
        storage.updateMeta("bkt-tags", "a.txt", meta);
        Thread.sleep(500);
        ObjectMetadata reread = storage.getMeta("bkt-tags", "a.txt", target);
        assertNotNull(reread.getTags(), "tags must persist");
        assertEquals("prod", reread.getTags().get("env"));
    }

    // ==================== concurrency ====================

    @Test
    void concurrentPut_sameKey_finalObjectIntact() throws Exception {
        storage.createBucket("bkt-cc");
        int threads = 8;
        Thread[] ts = new Thread[threads];
        AtomicReference<Throwable> err = new AtomicReference<>();
        for (int i = 0; i < threads; i++) {
            final int id = i;
            ts[i] = new Thread(() -> {
                try {
                    byte[] data = ("payload-" + id).getBytes(StandardCharsets.UTF_8);
                    ObjectMetadata m = storage.putObject("bkt-cc", "shared.bin",
                            new ByteArrayInputStream(data), null, null, null, null);
                    assertEquals(data.length, m.getSize());
                } catch (Throwable t) {
                    err.compareAndSet(null, t);
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) {
            t.join();
        }
        assertNull(err.get());
        Path file = Path.of(props.getDataPath(), "bkt-cc", "shared.bin");
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(content.matches("payload-\\d"),
                "final object must be one complete payload, got: " + content);
    }
}
