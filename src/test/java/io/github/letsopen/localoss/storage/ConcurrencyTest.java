package io.github.letsopen.localoss.storage;

import io.github.letsopen.localoss.config.SystemProperties;
import io.github.letsopen.localoss.model.ObjectMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发专项测试（第三优先级）。
 *
 * <p>覆盖：
 * <ol>
 *   <li>多写者同 key：最终一个完整写入胜出、无半截、无丢失 meta</li>
 *   <li>读-写并发：读者永远读到完整对象（旧或新），不读半截</li>
 *   <li>PUT-DELETE 竞争：终态为"完整存在"或"不存在"，无损坏</li>
 *   <li>锁压力：同 key 串行化（互斥）、不同 key 并行</li>
 *   <li>配额并发：多写者同时写，最终记账不超配额（或写入被正确拒绝）</li>
 *   <li>大范围 multipart 并发 Complete</li>
 * </ol>
 */
class ConcurrencyTest {

    @TempDir
    Path tmp;

    private SystemProperties props;
    private StorageEngine storage;
    private KeyValidator keyValidator;
    private MultipartStore multipartStore;
    private QuotaManager quotaManager;

    @BeforeEach
    void setUp() {
        props = new SystemProperties();
        props.setDataPath(tmp.toString() + "/");
        keyValidator = new KeyValidator(props);
        MetadataStore ms = new MetadataStore(props);
        LockManager lm = new LockManager(props);
        quotaManager = new QuotaManager(props, keyValidator);
        storage = new StorageEngine(props, keyValidator, ms, lm, quotaManager);
        multipartStore = new MultipartStore(props, keyValidator, storage, ms, lm);
        storage.createBucket("conc-bkt");
    }

    private void put(String key, String content) {
        storage.putObject("conc-bkt", key,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                null, null, null, null);
    }

    // ==================== 1. 多写者同 key ====================

    @Test
    void concurrentWriters_sameKey_oneCompleteWinner() throws Exception {
        int writers = 10;
        int rounds = 5;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        AtomicInteger writes = new AtomicInteger();
        try {
            for (int w = 0; w < writers; w++) {
                final int id = w;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int r = 0; r < rounds; r++) {
                            String content = "writer-" + id + "-round-" + r + "-"
                                    + "x".repeat(1000);
                            ObjectMetadata m = storage.putObject("conc-bkt", "shared.bin",
                                    new ByteArrayInputStream(
                                            content.getBytes(StandardCharsets.UTF_8)),
                                    null, null, null, null);
                            writes.incrementAndGet();
                        }
                    } catch (Throwable t) {
                        err.compareAndSet(null, t);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS));
            assertNull(err.get());
            assertEquals(writers * rounds, writes.get());
            // 最终：完整内容
            String finalContent = Files.readString(tmp.resolve("conc-bkt/shared.bin"),
                    StandardCharsets.UTF_8);
            assertTrue(finalContent.startsWith("writer-") && finalContent.contains("-round-"),
                    "complete payload: " + finalContent.substring(0, 30));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentWriters_differentKeys_allPersist() throws Exception {
        int writers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        try {
            for (int w = 0; w < writers; w++) {
                final int id = w;
                pool.submit(() -> {
                    try {
                        start.await();
                        String content = "k" + id + "-" + "y".repeat(500);
                        storage.putObject("conc-bkt", "k-" + id,
                                new ByteArrayInputStream(
                                        content.getBytes(StandardCharsets.UTF_8)),
                                null, null, null, null);
                    } catch (Throwable t) {
                        err.compareAndSet(null, t);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
            assertNull(err.get());
            // 全部 16 个 key 均存在且完整
            for (int i = 0; i < writers; i++) {
                Path f = tmp.resolve("conc-bkt/k-" + i);
                assertTrue(Files.exists(f), "k-" + i + " must exist");
                String content = Files.readString(f, StandardCharsets.UTF_8);
                assertEquals("k" + i + "-" + "y".repeat(500), content);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== 2. 读写并发 ====================

    @Test
    void readersNeverSeePartialObject() throws Exception {
        put("rw.bin", "initial");
        int readers = 6;
        int writers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(readers + writers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        AtomicInteger reads = new AtomicInteger();
        try {
            for (int r = 0; r < readers; r++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 30; i++) {
                            Path target = tmp.resolve("conc-bkt/rw.bin");
                            if (Files.exists(target)) {
                                byte[] all = Files.readAllBytes(target);
                                String content = new String(all, StandardCharsets.UTF_8);
                                // 每次读到完整内容（initial 或某个 writer-N 完整负载）
                                assertTrue(content.equals("initial")
                                                || content.matches("writer-\\d+-\\d+x*"),
                                        "partial read detected: len=" + content.length()
                                                + " head=" + content.substring(0,
                                                        Math.min(20, content.length())));
                                reads.incrementAndGet();
                            }
                        }
                    } catch (Throwable t) {
                        err.compareAndSet(null, t);
                    }
                });
            }
            for (int w = 0; w < writers; w++) {
                final int id = w;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 10; i++) {
                            storage.putObject("conc-bkt", "rw.bin",
                                    new ByteArrayInputStream(
                                            ("writer-" + id + "-" + i + "x".repeat(2000))
                                                    .getBytes(StandardCharsets.UTF_8)),
                                    null, null, null, null);
                        }
                    } catch (Throwable t) {
                        err.compareAndSet(null, t);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS));
            assertNull(err.get());
            assertTrue(reads.get() > 0, "some reads happened: " + reads.get());
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== 3. PUT-DELETE 竞争 ====================

    @Test
    void putDeleteRace_finalStateNeverCorrupt() throws Exception {
        put("race.bin", "initial");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < 15; j++) {
                            try {
                                storage.putObject("conc-bkt", "race.bin",
                                        new ByteArrayInputStream(
                                                ("put-" + j + "z".repeat(500))
                                                        .getBytes(StandardCharsets.UTF_8)),
                                        null, null, null, null);
                            } catch (Exception ignored) {
                                // 并发删除导致的失败可接受
                            }
                        }
                    } catch (Throwable t) {
                        err.compareAndSet(null, t);
                    }
                });
            }
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < 15; j++) {
                            try {
                                storage.deleteObject("conc-bkt", "race.bin");
                            } catch (Exception ignored) {
                            }
                        }
                    } catch (Throwable t) {
                        err.compareAndSet(null, t);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS));
            assertNull(err.get());
            // 终态：完整存在 或 不存在 —— 无损坏
            Path f = tmp.resolve("conc-bkt/race.bin");
            if (Files.exists(f)) {
                String content = Files.readString(f, StandardCharsets.UTF_8);
                assertTrue(content.equals("initial")
                                || content.matches("put-\\d+z*"),
                        "corrupt final state: " + content.substring(0,
                                Math.min(20, content.length())));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== 4. 锁行为 ====================

    @Test
    void lock_sameKeyMutualExclusion() throws Exception {
        // 用底层锁验证串行化：同 key 两个锁不可同时持有
        LockManager lm = new LockManager(props);
        Path bucketDir = keyValidator.bucketDir("conc-bkt");
        try (var l1 = lm.acquire(bucketDir, "key:mutex")) {
            try (var l2 = lm.tryAcquire(bucketDir, "key:mutex")) {
                assertNull(l2, "second lock on same key must fail while held");
            }
        }
        // 释放后可获取
        try (var l3 = lm.tryAcquire(bucketDir, "key:mutex")) {
            assertNotNull(l3);
        }
    }

    @Test
    void lock_differentKeysIndependent() throws Exception {
        LockManager lm = new LockManager(props);
        Path bucketDir = keyValidator.bucketDir("conc-bkt");
        try (var la = lm.acquire(bucketDir, "key:a");
             var lb = lm.acquire(bucketDir, "key:b")) {
            assertNotNull(la);
            assertNotNull(lb, "different keys must not block each other");
        }
    }

    // ==================== 5. 配额并发 ====================

    @Test
    void quota_concurrentWrites_noOvershootBeyondTolerance() throws Exception {
        java.util.Map<String, String> quotas = new java.util.HashMap<>();
        quotas.put("conc-bkt", "500KB");
        props.setQuotas(quotas);
        // 8 个并发写 100KB 各一次 = 800KB 总量 vs 500KB 配额 → 部分应被拒
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            for (int i = 0; i < 8; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        byte[] data = "q".repeat(100 * 1024).getBytes();
                        storage.putObject("conc-bkt", "qc-" + System.nanoTime(),
                                new ByteArrayInputStream(data), null, null, null, null);
                        succeeded.incrementAndGet();
                    } catch (io.github.letsopen.localoss.error.S3Exception e) {
                        rejected.incrementAndGet();
                    } catch (Throwable ignored) {
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
            // 至少部分成功 + 至少部分拒绝（500KB 限制下 5 个 100KB 成功是上限）
            assertTrue(succeeded.get() >= 1, "some writes succeed: " + succeeded.get());
            assertTrue(rejected.get() >= 1, "some writes rejected: " + rejected.get());
            // 实际落盘不超配额 + 1 个写入的容差（计数竞态）
            long onDisk = 0;
            try (var s = Files.newDirectoryStream(tmp.resolve("conc-bkt"))) {
                for (Path p : s) {
                    if (Files.isRegularFile(p)) {
                        onDisk += Files.size(p);
                    }
                }
            }
            assertTrue(onDisk <= 500 * 1024 + 100 * 1024,
                    "on-disk within quota + one-write tolerance: " + onDisk);
        } finally {
            props.setQuotas(java.util.Map.of());
            pool.shutdownNow();
        }
    }

    // ==================== 6. multipart 并发 ====================

    @Test
    void multipart_concurrentPartUploads() throws Exception {
        String uid = multipartStore.createUpload("conc-bkt", "mp.bin", null, null);
        int parts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(parts);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        try {
            for (int i = 1; i <= parts; i++) {
                final int num = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        // 前 7 片 5MiB+，最后一片小
                        int size = num < parts ? 5 * 1024 * 1024 + num : 1024;
                        byte[] data = new byte[size];
                        java.util.Arrays.fill(data, (byte) num);
                        multipartStore.uploadPart("conc-bkt", "mp.bin", uid, num,
                                new ByteArrayInputStream(data));
                    } catch (Throwable t) {
                        err.compareAndSet(null, t);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS));
            assertNull(err.get());
            // Complete
            List<Integer> nums = new ArrayList<>();
            for (int i = 1; i <= parts; i++) {
                nums.add(i);
            }
            ObjectMetadata meta = multipartStore.complete("conc-bkt", "mp.bin", uid, nums);
            long expected = 0;
            for (int i = 1; i <= parts; i++) {
                expected += i < parts ? 5L * 1024 * 1024 + i : 1024;
            }
            assertEquals(expected, meta.getSize());
            assertEquals(parts, meta.getParts());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void multipart_samePartConcurrentUploads_finalSizeStable() throws Exception {
        String uid = multipartStore.createUpload("conc-bkt", "cp.bin", null, null);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        try {
            for (int i = 0; i < 4; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        byte[] data = new byte[6 * 1024 * 1024];
                        java.util.Arrays.fill(data, (byte) 'c');
                        multipartStore.uploadPart("conc-bkt", "cp.bin", uid, 1,
                                new ByteArrayInputStream(data));
                    } catch (Throwable t) {
                        err.compareAndSet(null, t);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
            assertNull(err.get(), err.get() == null ? "" : err.get().getMessage());
            // 终态：part 1 大小 = 恰好 6MiB（最后写入者胜出）
            List<MultipartStore.PartInfo> parts = multipartStore.listParts("conc-bkt",
                    "cp.bin", uid);
            assertEquals(1, parts.size());
            assertEquals(6 * 1024 * 1024, parts.get(0).size());
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== 7. GC 与写入并发 ====================

    @Test
    void gc_concurrentWithWrites_noFalseDeletion() throws Exception {
        GarbageCollector gc = new GarbageCollector(props, keyValidator,
                multipartStore, new LockManager(props));
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        try {
            // 持续写入
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < 20; j++) {
                            storage.putObject("conc-bkt", "gcw-" + j,
                                    new ByteArrayInputStream(
                                            ("data-" + j + "g".repeat(2000))
                                                    .getBytes(StandardCharsets.UTF_8)),
                                    null, null, null, null);
                        }
                    } catch (Throwable t) {
                        err.compareAndSet(null, t);
                    }
                });
            }
            // 持续 GC
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 20; j++) {
                        gc.runGc();
                        Thread.sleep(5);
                    }
                } catch (Throwable t) {
                    err.compareAndSet(null, t);
                }
            });
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS));
            assertNull(err.get());
            // 所有对象完整存在
            for (int j = 0; j < 20; j++) {
                Path f = tmp.resolve("conc-bkt/gcw-" + j);
                assertTrue(Files.exists(f), "gcw-" + j + " must survive GC");
                String content = Files.readString(f, StandardCharsets.UTF_8);
                assertEquals("data-" + j + "g".repeat(2000), content);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
