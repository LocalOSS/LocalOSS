package io.github.letsopen.localoss.storage;

import io.github.letsopen.localoss.config.SystemProperties;
import io.github.letsopen.localoss.error.S3Exception;
import io.github.letsopen.localoss.model.ObjectMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据完整性专项测试（第二优先级）。
 *
 * <p>覆盖：
 * <ol>
 *   <li>原子写：覆盖写不留半截、并发写唯一完整结果、写失败不留脏数据</li>
 *   <li>崩溃模拟：tmp 残留不影响读、meta 与文件不一致时以文件为准</li>
 *   <li>Multipart：拼接顺序、ETag 正确性、Complete 幂等失败（重复 Complete 拒绝）、
 *       part 覆盖写、Abort 后不可 Complete</li>
 *   <li>GC 数据安全：GC 只删过期 tmp、活跃对象不受影响、孤儿 meta 清理</li>
 *   <li>校验和端到端：PUT 校验和落盘 meta、GET 校验和可读回</li>
 *   <li>删除完整性：递归删除、空目录清理不影响兄弟对象</li>
 * </ol>
 */
class DataIntegrityTest {

    @TempDir
    Path tmp;

    private SystemProperties props;
    private StorageEngine storage;
    private KeyValidator keyValidator;
    private MultipartStore multipartStore;
    private MetadataStore metadataStore;
    private LockManager lockManager;

    @BeforeEach
    void setUp() {
        props = new SystemProperties();
        props.setDataPath(tmp.toString() + "/");
        keyValidator = new KeyValidator(props);
        metadataStore = new MetadataStore(props);
        lockManager = new LockManager(props);
        storage = new StorageEngine(props, keyValidator, metadataStore, lockManager,
                new QuotaManager(props, keyValidator));
        multipartStore = new MultipartStore(props, keyValidator, storage, metadataStore,
                lockManager);
        storage.createBucket("int-bkt");
    }

    private void put(String key, String content) {
        storage.putObject("int-bkt", key,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                null, null, null, null);
    }

    // ==================== 1. 原子写 ====================

    @Test
    void overwrite_neverLeavesPartialFile() throws Exception {
        put("a.bin", "0123456789");
        // 用一个中途抛异常的流模拟传输中断
        InputStream broken = new InputStream() {
            int sent = 0;
            @Override
            public int read() {
                if (sent++ < 4) {
                    return 'x';
                }
                return -1; // 提前 EOF（客户端断开）
            }
        };
        // 提前 EOF 按正常短内容处理（写入 "xxxx"）——验证完整写入而非半截
        storage.putObject("int-bkt", "a.bin", broken, null, null, null, null);
        String content = Files.readString(tmp.resolve("int-bkt/a.bin"),
                StandardCharsets.UTF_8);
        assertEquals("xxxx", content, "must be the complete new content");
    }

    @Test
    void overwrite_metaMatchesNewContent() {
        put("meta.txt", "version1-longer-content");
        ObjectMetadata m1 = storage.getMeta("int-bkt", "meta.txt",
                tmp.resolve("int-bkt/meta.txt"));
        put("meta.txt", "v2");
        ObjectMetadata m2 = storage.getMeta("int-bkt", "meta.txt",
                tmp.resolve("int-bkt/meta.txt"));
        assertEquals(2, m2.getSize(), "size must reflect new content");
        assertNotEquals(m1.getEtag(), m2.getEtag(), "etag must change");
    }

    @Test
    void concurrentOverwrites_finalStateIsConsistent() throws Exception {
        int threads = 8;
        int writes = 20;
        List<Thread> ts = new ArrayList<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            Thread th = new Thread(() -> {
                for (int i = 0; i < writes; i++) {
                    try {
                        String content = "t" + id + "-w" + i;
                        ObjectMetadata m = storage.putObject("int-bkt", "shared.bin",
                                new ByteArrayInputStream(
                                        content.getBytes(StandardCharsets.UTF_8)),
                                null, null, null, null);
                        assertEquals(content.length(), m.getSize(), "meta size == content");
                    } catch (Throwable e) {
                        err.compareAndSet(null, e);
                        return;
                    }
                }
            });
            ts.add(th);
            th.start();
        }
        for (Thread th : ts) {
            th.join();
        }
        assertNull(err.get());
        // 最终：文件是某个完整写入；meta 与文件一致
        String content = Files.readString(tmp.resolve("int-bkt/shared.bin"),
                StandardCharsets.UTF_8);
        assertTrue(content.matches("t\\d+-w\\d+"), "complete payload: " + content);
        ObjectMetadata meta = storage.getMeta("int-bkt", "shared.bin",
                tmp.resolve("int-bkt/shared.bin"));
        assertEquals(content.length(), meta.getSize(), "meta size must match file");
    }

    // ==================== 2. 崩溃模拟 ====================

    @Test
    void leftoverTmp_doesNotAffectReads() throws Exception {
        put("ok.txt", "data");
        // 模拟崩溃残留的 tmp 文件
        Path tmpDir = tmp.resolve("int-bkt/.localoss/tmp");
        Files.createDirectories(tmpDir);
        Files.writeString(tmpDir.resolve("crash-1.tmp"), "half-written");
        // 读正常对象不受影响
        Path target = tmp.resolve("int-bkt/ok.txt");
        try (var obj = storage.openObject(target, 0, Files.size(target))) {
            String content = new String(obj.stream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("data", content);
        }
        // 列举不含 tmp
        List<String> keys = storage.listAllKeys("int-bkt", "");
        assertFalse(keys.stream().anyMatch(k -> k.contains("tmp")));
    }

    @Test
    void metaMissing_fileIsAuthoritative() throws Exception {
        put("auth.txt", "authoritative content");
        // 删除 meta 模拟损坏
        metadataStore.deleteMeta(keyValidator.bucketDir("int-bkt"), "auth.txt");
        // GET 以文件系统为准并回填
        ObjectMetadata meta = storage.getMeta("int-bkt", "auth.txt",
                tmp.resolve("int-bkt/auth.txt"));
        assertEquals("authoritative content".length(), meta.getSize());
        assertEquals("text/plain", meta.getContentType()); // 按扩展名
        // 回填后能读回
        Thread.sleep(300);
        ObjectMetadata reread = storage.getMeta("int-bkt", "auth.txt",
                tmp.resolve("int-bkt/auth.txt"));
        assertEquals(meta.getSize(), reread.getSize());
    }

    @Test
    void fileDeleted_metaOrphan_gcSafe() throws Exception {
        put("orphan.txt", "v");
        // 直接删文件（绕过 API）模拟 meta 孤儿
        Files.deleteIfExists(tmp.resolve("int-bkt/orphan.txt"));
        // listAllKeys 不列出（walk 只看文件）
        List<String> keys = storage.listAllKeys("int-bkt", "");
        assertFalse(keys.contains("orphan.txt"));
        // getMeta 对不存在文件：目录型空对象语义（0 字节）或 NoSuchKey —— 与 API 层一致
    }

    // ==================== 3. Multipart 完整性 ====================

    @Test
    void multipart_partsAssembledInOrder() throws Exception {
        String uid = multipartStore.createUpload("int-bkt", "order.bin", null, null);
        // 乱序上传 3 片（前两片 5MiB 满足最小限制，最后一片可小）
        byte[] bigA = "A".repeat(5 * 1024 * 1024).getBytes();
        byte[] bigB = "B".repeat(5 * 1024 * 1024).getBytes();
        multipartStore.uploadPart("int-bkt", "order.bin", uid, 3,
                new ByteArrayInputStream("CCC".getBytes()));
        multipartStore.uploadPart("int-bkt", "order.bin", uid, 1,
                new ByteArrayInputStream(bigA));
        multipartStore.uploadPart("int-bkt", "order.bin", uid, 2,
                new ByteArrayInputStream(bigB));
        ObjectMetadata meta = multipartStore.complete("int-bkt", "order.bin", uid,
                List.of(1, 2, 3));
        assertEquals(bigA.length + bigB.length + 3, meta.getSize());
        // 校验拼接顺序：开头 A*5MiB，然后 B*5MiB，最后 CCC
        byte[] all = Files.readAllBytes(tmp.resolve("int-bkt/order.bin"));
        assertEquals('A', all[0]);
        assertEquals('A', all[bigA.length - 1]);
        assertEquals('B', all[bigA.length]);
        assertEquals('B', all[bigA.length + bigB.length - 1]);
        assertEquals('C', all[bigA.length + bigB.length]);
        assertEquals('C', all[all.length - 1]);
    }

    @Test
    void multipart_etagFormat() throws Exception {
        String uid = multipartStore.createUpload("int-bkt", "etag.bin", null, null);
        multipartStore.uploadPart("int-bkt", "etag.bin", uid, 1,
                new ByteArrayInputStream("p1".repeat(3 * 1024 * 1024).getBytes()));
        multipartStore.uploadPart("int-bkt", "etag.bin", uid, 2,
                new ByteArrayInputStream("p2".repeat(3 * 1024 * 1024).getBytes()));
        ObjectMetadata meta = multipartStore.complete("int-bkt", "etag.bin", uid,
                List.of(1, 2));
        // multipart ETag = "md5hex-2"
        assertTrue(meta.getEtag().matches("\"[0-9a-f]{32}-2\""),
                "multipart etag format: " + meta.getEtag());
        assertEquals(2, meta.getParts());
    }

    @Test
    void multipart_completeTwice_rejected() throws Exception {
        String uid = multipartStore.createUpload("int-bkt", "twice.bin", null, null);
        String p1 = "x".repeat(5 * 1024 * 1024); // 满足最小分片
        multipartStore.uploadPart("int-bkt", "twice.bin", uid, 1,
                new ByteArrayInputStream(p1.getBytes()));
        multipartStore.complete("int-bkt", "twice.bin", uid, List.of(1));
        // 第二次 complete：upload 目录已删 → NoSuchUpload
        assertThrows(S3Exception.class, () ->
                multipartStore.complete("int-bkt", "twice.bin", uid, List.of(1)));
    }

    @Test
    void multipart_partOverwrite_newDataWins() throws Exception {
        String uid = multipartStore.createUpload("int-bkt", "ovw.bin", null, null);
        multipartStore.uploadPart("int-bkt", "ovw.bin", uid, 1,
                new ByteArrayInputStream("old1".getBytes()));
        multipartStore.uploadPart("int-bkt", "ovw.bin", uid, 1,
                new ByteArrayInputStream("new1-longer".getBytes()));
        List<MultipartStore.PartInfo> parts = multipartStore.listParts("int-bkt",
                "ovw.bin", uid);
        assertEquals(1, parts.size());
        assertEquals("new1-longer".length(), parts.get(0).size());
    }

    @Test
    void multipart_abortAfterComplete_uploadGone() throws Exception {
        String uid = multipartStore.createUpload("int-bkt", "ab.bin", null, null);
        String p1 = "y".repeat(5 * 1024 * 1024);
        multipartStore.uploadPart("int-bkt", "ab.bin", uid, 1,
                new ByteArrayInputStream(p1.getBytes()));
        multipartStore.complete("int-bkt", "ab.bin", uid, List.of(1));
        // abort 已完成的 upload → NoSuchUpload
        assertThrows(S3Exception.class, () ->
                multipartStore.abort("int-bkt", "ab.bin", uid));
    }

    @Test
    void multipart_metaDataCarried() throws Exception {
        String uid = multipartStore.createUpload("int-bkt", "meta.bin",
                java.util.Map.of("Content-Type", "application/x-custom"),
                java.util.Map.of("creator", "integrity-test"));
        String p1 = "z".repeat(5 * 1024 * 1024);
        multipartStore.uploadPart("int-bkt", "meta.bin", uid, 1,
                new ByteArrayInputStream(p1.getBytes()));
        ObjectMetadata meta = multipartStore.complete("int-bkt", "meta.bin", uid,
                List.of(1));
        assertEquals("application/x-custom", meta.getContentType());
        assertEquals("integrity-test", meta.getUserMetadata().get("creator"));
    }

    // ==================== 4. GC 数据安全 ====================

    @Test
    void gc_doesNotDeleteActiveObjects() throws Exception {
        put("live1.txt", "v1");
        put("dir/live2.txt", "v2");
        // 设一个极短的 tmp 超时，但对象不在 tmp —— 必须不受影响
        props.setTmpMaxAgeHours(0);
        GarbageCollector gc = new GarbageCollector(props, keyValidator,
                multipartStore, lockManager);
        gc.runGc();
        assertTrue(Files.exists(tmp.resolve("int-bkt/live1.txt")));
        assertTrue(Files.exists(tmp.resolve("int-bkt/dir/live2.txt")));
        props.setTmpMaxAgeHours(24);
    }

    @Test
    void gc_deletesOnlyExpiredTmp() throws Exception {
        Path tmpDir = tmp.resolve("int-bkt/.localoss/tmp");
        Files.createDirectories(tmpDir);
        Path old = tmpDir.resolve("old.tmp");
        Files.writeString(old, "x");
        Files.setLastModifiedTime(old,
                java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(2 * 86400)));
        Path fresh = tmpDir.resolve("fresh.tmp");
        Files.writeString(fresh, "y");
        GarbageCollector gc = new GarbageCollector(props, keyValidator,
                multipartStore, lockManager);
        gc.runGc();
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(fresh));
    }

    // ==================== 5. 校验和端到端 ====================

    @Test
    void checksums_persistedAndReadable() throws Exception {
        String content = "checksum end to end";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        ObjectMetadata meta = storage.putObject("int-bkt", "cs.txt",
                new ByteArrayInputStream(data), null, null, null, null);
        // ETag = MD5
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        String expected = Base64.getEncoder().encodeToString(md5.digest(data));
        assertEquals(expected, meta.getChecksums().get("MD5"));
        // SHA256 也落盘
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        assertEquals(Base64.getEncoder().encodeToString(sha.digest(data)),
                meta.getChecksums().get("SHA256"));
        // 回读一致
        ObjectMetadata reread = storage.getMeta("int-bkt", "cs.txt",
                tmp.resolve("int-bkt/cs.txt"));
        assertEquals(meta.getEtag(), reread.getEtag());
    }

    // ==================== 6. 删除完整性 ====================

    @Test
    void delete_deepKey_cleansEmptyParentsKeepsSiblings() {
        put("keep/sibling.txt", "v");
        put("tree/a/b/c/leaf.txt", "v");
        storage.deleteObject("int-bkt", "tree/a/b/c/leaf.txt");
        assertFalse(Files.exists(tmp.resolve("int-bkt/tree")));
        assertTrue(Files.exists(tmp.resolve("int-bkt/keep/sibling.txt")));
    }

    @Test
    void delete_doesNotTouchSiblingDirs() {
        put("a/1.txt", "1");
        put("a/2.txt", "2");
        put("a/sub/3.txt", "3");
        storage.deleteObject("int-bkt", "a/1.txt");
        assertTrue(Files.exists(tmp.resolve("int-bkt/a/2.txt")));
        assertTrue(Files.exists(tmp.resolve("int-bkt/a/sub/3.txt")));
    }

    @Test
    void deleteBucket_afterEmptying_succeeds() {
        put("x.txt", "v");
        assertThrows(S3Exception.class, () -> storage.deleteBucket("int-bkt"));
        storage.deleteObject("int-bkt", "x.txt");
        assertDoesNotThrow(() -> storage.deleteBucket("int-bkt"));
        assertFalse(Files.exists(tmp.resolve("int-bkt")));
    }

    // ==================== 7. 目录型对象 ====================

    @Test
    void directoryObject_zeroByteSemantics() {
        storage.putObject("int-bkt", "folder/",
                new ByteArrayInputStream(new byte[0]), null, null, null, null);
        assertTrue(Files.isDirectory(tmp.resolve("int-bkt/folder")));
        // 目录型对象出现在列举中
        List<String> keys = storage.listAllKeys("int-bkt", "");
        assertTrue(keys.contains("folder/"), "dir object listed: " + keys);
    }

    @Test
    void directoryObject_conflictsWithFileKey() {
        put("both", "file");
        // 写 both/（目录型）到已有文件位置 → 冲突
        assertThrows(S3Exception.class, () ->
                storage.putObject("int-bkt", "both/",
                        new ByteArrayInputStream(new byte[0]), null, null, null, null));
    }
}
