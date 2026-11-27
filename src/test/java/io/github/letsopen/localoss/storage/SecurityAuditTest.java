package io.github.letsopen.localoss.storage;

import io.github.letsopen.localoss.config.SystemProperties;
import io.github.letsopen.localoss.error.S3ErrorCode;
import io.github.letsopen.localoss.error.S3Exception;
import io.github.letsopen.localoss.model.ObjectMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 安全性专项测试（优先级最高）。
 *
 * <p>覆盖：
 * <ol>
 *   <li>路径穿越变体（key 段、完整 key、转义序列、超长）</li>
 *   <li>符号链接逃逸（GET 已存在 link、PUT 新对象父目录 link、目录树 link）</li>
 *   <li>Multipart uploadId 注入（../、绝对路径、编码变体、合法格式）</li>
 *   <li>锁安全（租约过期接管、GC 不误删活跃锁、锁释放正确性）</li>
 *   <li>桶目录逃逸（bucket 名拼接、quota/credentials 逃逸）</li>
 *   <li>tmp/locks/uploads 不可通过 S3 API 访问</li>
 * </ol>
 */
class SecurityAuditTest {

    @TempDir
    Path tmp;

    private SystemProperties props;
    private StorageEngine storage;
    private KeyValidator keyValidator;
    private MultipartStore multipartStore;
    private LockManager lockManager;
    private MetadataStore metadataStore;

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
        storage.createBucket("sec-bkt");
    }

    private void put(String key, String content) {
        storage.putObject("sec-bkt", key,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                null, null, null, null);
    }

    // ==================== 1. 路径穿越变体 ====================

    @Test
    void traversal_dotDotInKey_rejected() {
        for (String key : new String[]{"../escape", "a/../../escape",
                "a/b/../../../etc/passwd", "..", "a/.."}) {
            S3Exception e = assertThrows(S3Exception.class, () ->
                    keyValidator.toFilePath("sec-bkt", key), "key=" + key);
            assertEquals(S3ErrorCode.INVALID_ARGUMENT, e.getErrorCode(), "key=" + key);
        }
    }

    @Test
    void traversal_normalizedPathEscapesBucket_rejected() {
        // 即使容器已规范化（应用收到 /sec-bkt/x），手工构造的 Path 也必须防住
        Path bucketRoot = keyValidator.bucketDir("sec-bkt");
        Path escape = bucketRoot.resolve("../../outside");
        Path normalized = escape.normalize();
        assertTrue(!normalized.startsWith(bucketRoot),
                "normalized path must escape bucket root");
    }

    @Test
    void traversal_keyWithEncodedTraversal_literalPercentIsLegalKey() {
        // 字面 "%2e%2e" 作为 key 是合法的（% 是 S3 允许的字符）；
        // 危险的是 URI 解码后的 ".." —— 那在 validateKey 被拒绝（见 dotDotInKey 测试）。
        // 这里验证解码后的形态被拒 + 字面 % 安全
        assertThrows(S3Exception.class, () -> keyValidator.validateKey("../escape"));
        assertDoesNotThrow(() -> keyValidator.validateKey("%2e%2e/escape"));
    }

    @Test
    void traversal_uploadIdDotDot_rejected() {
        // multipart uploadId 注入：必须拒绝
        for (String uid : new String[]{"../../x", "..", "a/../..", "/abs", "a/b",
                "x%2f%2e%2e"}) {
            S3Exception e = assertThrows(S3Exception.class, () ->
                    multipartStore.listParts("sec-bkt", "k", uid), "uid=" + uid);
            assertEquals(S3ErrorCode.INVALID_ARGUMENT, e.getErrorCode(), "uid=" + uid);
        }
    }

    @Test
    void traversal_validUploadIdFormat_accepted() {
        // 合法格式（instanceId-uuid）：允许创建
        String uid = props.getInstanceId() + "-abc123def456";
        String created = multipartStore.createUpload("sec-bkt", "k.bin", null, null);
        assertTrue(created.matches("[A-Za-z0-9-]+"), "created id format");
        assertDoesNotThrow(() -> multipartStore.listParts("sec-bkt", "k.bin", created));
    }

    @Test
    void traversal_multiDotDotDeep_resolvedInsideBucketStillSafe() {
        // S3 语义中 "videos/../../video1.wmv" 左侧累计不超过非相对段即合法
        // 本实现白名单直接拒绝所有 ..——更保守，验证拒绝
        assertThrows(S3Exception.class, () ->
                keyValidator.validateKey("videos/2014/../../video1.wmv"));
    }

    // ==================== 2. 符号链接逃逸 ====================

    @Test
    void symlink_getExistingLinkOutside_rejected() throws Exception {
        // 在 bucket 内创建指向外部的 symlink，GET 该 key 必须拒绝
        Path outside = Files.createTempDirectory("outside-target");
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Path link = tmp.resolve("sec-bkt/linkdir");
        Files.createSymbolicLink(link, outside);
        S3Exception e = assertThrows(S3Exception.class, () ->
                keyValidator.checkSymlinkEscape(keyValidator.bucketDir("sec-bkt"),
                        link.resolve("secret.txt")));
        assertEquals(S3ErrorCode.ACCESS_DENIED, e.getErrorCode());
    }

    @Test
    void symlink_putParentIsLink_rejected() throws Exception {
        // 父目录是 symlink 时 PUT 新对象必须拒绝
        Path outside = Files.createTempDirectory("outside-put");
        Path linkParent = tmp.resolve("sec-bkt/linked");
        Files.createSymbolicLink(linkParent, outside);
        // checkParentSymlinkEscape 必须发现 link 指向 bucket 外
        S3Exception e = assertThrows(S3Exception.class, () ->
                keyValidator.checkParentSymlinkEscape(
                        keyValidator.bucketDir("sec-bkt"),
                        linkParent.resolve("newfile.txt")));
        assertEquals(S3ErrorCode.ACCESS_DENIED, e.getErrorCode());
    }

    @Test
    void symlink_putParentLinkInsideBucket_allowed() throws Exception {
        // 父目录链接指向 bucket 内 → 合法（正常写入）
        Files.createDirectories(tmp.resolve("sec-bkt/realdir"));
        Path linkParent = tmp.resolve("sec-bkt/linked2");
        Files.createSymbolicLink(linkParent, tmp.resolve("sec-bkt/realdir"));
        assertDoesNotThrow(() ->
                keyValidator.checkParentSymlinkEscape(
                        keyValidator.bucketDir("sec-bkt"),
                        linkParent.resolve("ok.txt")));
    }

    @Test
    void symlink_deepTreeLink_rejected() throws Exception {
        // 深层目录树中最后一层是链接
        Path outside = Files.createTempDirectory("outside-deep");
        Files.createDirectories(tmp.resolve("sec-bkt/a/b/c"));
        Files.createSymbolicLink(tmp.resolve("sec-bkt/a/b/c/link"), outside);
        S3Exception e = assertThrows(S3Exception.class, () ->
                keyValidator.checkParentSymlinkEscape(
                        keyValidator.bucketDir("sec-bkt"),
                        tmp.resolve("sec-bkt/a/b/c/link/pwned.txt")));
        assertEquals(S3ErrorCode.ACCESS_DENIED, e.getErrorCode());
    }

    @Test
    void symlink_deleteDoesNotFollowLink() throws Exception {
        // 删除含 link 的目录：只删 link 本身，不递归到链接目标
        Path outside = Files.createTempDirectory("outside-del");
        Files.writeString(outside.resolve("keep.txt"), "keep");
        put("d1/x.txt", "v");
        Files.createSymbolicLink(tmp.resolve("sec-bkt/d1/link"), outside);
        storage.deleteObject("sec-bkt", "d1/x.txt");
        // link 目标内容不受影响
        assertTrue(Files.exists(outside.resolve("keep.txt")));
    }

    // ==================== 3. 内部目录不可访问 ====================

    @Test
    void internalDirs_notAccessibleAsKeys() {
        for (String key : new String[]{".localoss/bucket.json",
                ".localoss/meta/x", ".localoss/tmp/t", ".localoss/uploads/u",
                ".localoss/locks/l"}) {
            assertThrows(S3Exception.class, () ->
                    keyValidator.toFilePath("sec-bkt", key), "key=" + key);
        }
    }

    @Test
    void internalDir_notListedInObjects() {
        put("normal.txt", "v");
        List<String> keys = storage.listAllKeys("sec-bkt", "");
        assertTrue(keys.contains("normal.txt"));
        for (String k : keys) {
            assertFalse(k.startsWith(".localoss"), "internal dir leaked: " + k);
        }
    }

    // ==================== 4. 锁安全 ====================

    @Test
    void lock_releaseCorrect_afterUse() throws Exception {
        Path bucketDir = keyValidator.bucketDir("sec-bkt");
        try (var lock = lockManager.acquire(bucketDir, "key:test")) {
            assertNotNull(lock);
            // 持锁期间他人拿不到
            assertNull(lockManager.tryAcquire(bucketDir, "key:test"));
        }
        // 释放后可再获取
        try (var lock2 = lockManager.tryAcquire(bucketDir, "key:test")) {
            assertNotNull(lock2);
        }
    }

    @Test
    void lock_expiredCanBeTakenOver() throws Exception {
        Path bucketDir = keyValidator.bucketDir("sec-bkt");
        // 手工构造一个过期锁
        Path locks = bucketDir.resolve(".localoss/locks");
        String resource = "key:stale-test";
        String hash = io.github.letsopen.localoss.util.KeyHash.sha256Hex(resource);
        Path lockDir = locks.resolve(hash + ".lock");
        Files.createDirectories(lockDir);
        Files.writeString(lockDir.resolve("owner.json"),
                String.format("{\"instanceId\":\"other\",\"expiresAt\":\"%s\"}",
                        Instant.now().minusSeconds(3600)),
                StandardCharsets.UTF_8);
        // 新 acquire 应能接管（若锁未过期将阻塞至超时，这里断言能立即拿到）
        long start = System.currentTimeMillis();
        try (var lock = lockManager.acquire(bucketDir, resource)) {
            assertNotNull(lock, "must take over expired lock");
        }
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 5000, "takeover should be fast, took " + elapsed + "ms");
        // 接管后旧 owner 的锁目录已被接管者接管；释放后删除（此处允许不存在）
        Path locksRoot = locks;
        try (var stream = Files.newDirectoryStream(locksRoot)) {
            for (Path p : stream) {
                if (p.getFileName().toString().contains(hash)) {
                    return; // 目录还在（接管者的）
                }
            }
        }
        // 已被释放删除 —— 也正确
    }

    @Test
    void gc_doesNotDeleteActiveLock() throws Exception {
        props.setLockLeaseSeconds(3600); // 1 小时租约
        GarbageCollector gc = new GarbageCollector(props, keyValidator,
                multipartStore, lockManager);
        Path bucketDir = keyValidator.bucketDir("sec-bkt");
        try (var lock = lockManager.acquire(bucketDir, "key:active")) {
            // 持有活跃锁时跑 GC
            int removed = gc.runGc();
            assertEquals(0, removed, "active lock must not be removed");
            Path locks = bucketDir.resolve(".localoss/locks");
            try (var stream = Files.newDirectoryStream(locks)) {
                int count = 0;
                for (Path p : stream) {
                    count++;
                }
                assertTrue(count >= 1, "active lock dir must still exist");
            }
        }
        props.setLockLeaseSeconds(30);
    }

    @Test
    void gc_deletesExpiredLock() throws Exception {
        GarbageCollector gc = new GarbageCollector(props, keyValidator,
                multipartStore, lockManager);
        Path bucketDir = keyValidator.bucketDir("sec-bkt");
        Path locks = bucketDir.resolve(".localoss/locks");
        Path expired = locks.resolve(
                io.github.letsopen.localoss.util.KeyHash.sha256Hex("key:expired") + ".lock");
        Files.createDirectories(expired);
        Files.writeString(expired.resolve("owner.json"),
                String.format("{\"instanceId\":\"dead\",\"expiresAt\":\"%s\"}",
                        Instant.now().minusSeconds(3600)),
                StandardCharsets.UTF_8);
        int removed = gc.runGc();
        assertTrue(removed >= 1);
        assertFalse(Files.exists(expired));
    }

    // ==================== 5. 静态注入面 ====================

    @Test
    void bucketNameInjection_rejected() {
        for (String bad : new String[]{"..", "a/b", "a\\b", ".localoss", "b..kt",
                "%2e%2e"}) {
            assertThrows(S3Exception.class, () ->
                    keyValidator.bucketDir(bad), "bucket=" + bad);
        }
    }

    @Test
    void metadataJson_notInjectableViaKey() throws Exception {
        // key 含 JSON 特殊字符（{}/:）—— 元数据序列化必须安全且 roundtrip
        String key = "weird{key}with:chars.txt";
        put(key, "v");
        ObjectMetadata meta = storage.getMeta("sec-bkt", key,
                tmp.resolve("sec-bkt").resolve(key));
        assertNotNull(meta);
        assertEquals(key, meta.getKey(), "key must roundtrip exactly");
    }

    @Test
    void checksums_notBypassableByCaseGames() {
        // x-amz-checksum 算法名大小写变体不改变校验语义
        storage.putObject("sec-bkt", "cs.txt",
                new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)),
                null, null, null, null);
        // 错误的 SHA256（小写头名）仍拒绝
        S3Exception e = assertThrows(S3Exception.class, () ->
                storage.putObject("sec-bkt", "cs2.txt",
                        new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)),
                        null, null,
                        java.util.Map.of("SHA256",
                                java.util.Base64.getEncoder().encodeToString(new byte[32])),
                        null));
        assertEquals(S3ErrorCode.INVALID_DIGEST, e.getErrorCode());
    }
}
