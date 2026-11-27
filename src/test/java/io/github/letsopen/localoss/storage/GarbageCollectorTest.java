package io.github.letsopen.localoss.storage;

import io.github.letsopen.localoss.config.SystemProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6：GC 与多实例安全测试。
 */
class GarbageCollectorTest {

    @TempDir
    Path tmp;

    private SystemProperties props;
    private GarbageCollector gc;
    private StorageEngine storage;

    @BeforeEach
    void setUp() {
        props = new SystemProperties();
        props.setDataPath(tmp.toString() + "/");
        KeyValidator kv = new KeyValidator(props);
        MetadataStore ms = new MetadataStore(props);
        LockManager lm = new LockManager(props);
        storage = new StorageEngine(props, kv, ms, lm, new QuotaManager(props, kv));
        MultipartStore mpu = new MultipartStore(props, kv, storage, ms, lm);
        gc = new GarbageCollector(props, kv, mpu, lm);
    }

    @Test
    void gc_cleanStaleTmp() throws Exception {
        storage.createBucket("gc-bkt");
        Path tmpDir = tmp.resolve("gc-bkt/.localoss/tmp");
        Files.createDirectories(tmpDir);
        // 一个 30 小时前的旧文件（超过 24h 保留期）
        Path old = tmpDir.resolve("old-1.tmp");
        Files.writeString(old, "old");
        Files.setLastModifiedTime(old,
                java.nio.file.attribute.FileTime.from(
                        java.time.Instant.now().minusSeconds(30 * 3600)));
        // 一个新文件
        Path fresh = tmpDir.resolve("fresh.tmp");
        Files.writeString(fresh, "fresh");

        props.setTmpMaxAgeHours(24);
        int removed = gc.runGc();
        assertTrue(removed >= 1);
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(fresh));
    }

    @Test
    void gc_disabled_noop() throws Exception {
        storage.createBucket("gc-off");
        Path tmpDir = tmp.resolve("gc-off/.localoss/tmp");
        Files.createDirectories(tmpDir);
        Path old = tmpDir.resolve("old.tmp");
        Files.writeString(old, "old");
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.from(
                java.time.Instant.now().minusSeconds(100 * 3600)));
        props.setGcEnabled(false);
        int removed = gc.runGc();
        assertEquals(0, removed);
        assertTrue(Files.exists(old));
    }

    @Test
    void gc_cleanStaleLocks() throws Exception {
        storage.createBucket("gc-lock");
        Path locks = tmp.resolve("gc-lock/.localoss/locks");
        Files.createDirectories(locks.resolve("deadlock.lock"));
        // 超过租约 + 余量的过期锁
        Path owner = locks.resolve("deadlock.lock/owner.json");
        Files.writeString(owner, "{\"instanceId\":\"dead\",\"expiresAt\":\""
                + java.time.Instant.now().minusSeconds(3600) + "\"}");
        Files.setLastModifiedTime(locks.resolve("deadlock.lock"),
                java.nio.file.attribute.FileTime.from(
                        java.time.Instant.now().minusSeconds(3600)));
        props.setLockLeaseSeconds(30);
        int removed = gc.runGc();
        assertTrue(removed >= 1);
        assertFalse(Files.exists(locks.resolve("deadlock.lock")));
    }

    @Test
    void concurrentPut_8threads_sameKey_objectIntact() throws Exception {
        storage.createBucket("cc-bkt");
        int threads = 8;
        Thread[] ts = new Thread[threads];
        java.util.concurrent.atomic.AtomicReference<Throwable> err =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            final int id = i;
            ts[i] = new Thread(() -> {
                try {
                    start.await();
                    byte[] data = ("payload-" + id).getBytes(StandardCharsets.UTF_8);
                    storage.putObject("cc-bkt", "shared.bin",
                            new ByteArrayInputStream(data), null, null, null, null);
                } catch (Throwable t) {
                    err.compareAndSet(null, t);
                }
            });
            ts[i].start();
        }
        start.countDown();
        for (Thread t : ts) {
            t.join();
        }
        assertNull(err.get());
        Path file = Path.of(props.getDataPath(), "cc-bkt", "shared.bin");
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(content.matches("payload-\\d"),
                "final object must be one complete payload: " + content);
    }

    @Test
    void concurrentPut_differentKeys_noLostWrites() throws Exception {
        storage.createBucket("ck-bkt");
        int threads = 16;
        Thread[] ts = new Thread[threads];
        java.util.concurrent.atomic.AtomicReference<Throwable> err =
                new java.util.concurrent.atomic.AtomicReference<>();
        for (int i = 0; i < threads; i++) {
            final int id = i;
            ts[i] = new Thread(() -> {
                try {
                    storage.putObject("ck-bkt", "k" + id,
                            new ByteArrayInputStream(("v" + id).getBytes(StandardCharsets.UTF_8)),
                            null, null, null, null);
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
        for (int i = 0; i < threads; i++) {
            Path f = Path.of(props.getDataPath(), "ck-bkt", "k" + i);
            assertEquals("v" + i, Files.readString(f, StandardCharsets.UTF_8), "k" + i);
        }
    }

    @Test
    void concurrentDeleteAndPut_noCorruption() throws Exception {
        storage.createBucket("dp-bkt");
        storage.putObject("dp-bkt", "x.bin",
                new ByteArrayInputStream("initial".getBytes(StandardCharsets.UTF_8)),
                null, null, null, null);
        Thread putter = new Thread(() -> {
            for (int i = 0; i < 20; i++) {
                try {
                    storage.putObject("dp-bkt", "x.bin",
                            new ByteArrayInputStream(("put-" + i).getBytes(StandardCharsets.UTF_8)),
                            null, null, null, null);
                } catch (Throwable ignored) {
                }
            }
        });
        Thread deleter = new Thread(() -> {
            for (int i = 0; i < 20; i++) {
                try {
                    storage.deleteObject("dp-bkt", "x.bin");
                } catch (Throwable ignored) {
                    // object may not exist
                }
            }
        });
        putter.start();
        deleter.start();
        putter.join();
        deleter.join();
        // 最终状态只能是"存在且完整"或"不存在"，不能是损坏
        Path f = Path.of(props.getDataPath(), "dp-bkt", "x.bin");
        if (Files.exists(f)) {
            String content = Files.readString(f, StandardCharsets.UTF_8);
            assertTrue(content.matches("put-\\d+") || content.equals("initial"),
                    "final content must be a complete value: " + content);
        }
    }
}
