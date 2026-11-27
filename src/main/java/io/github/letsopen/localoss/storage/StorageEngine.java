package io.github.letsopen.localoss.storage;

import io.github.letsopen.localoss.config.SystemProperties;
import io.github.letsopen.localoss.error.S3ErrorCode;
import io.github.letsopen.localoss.error.S3Exception;
import io.github.letsopen.localoss.model.ObjectMetadata;
import io.github.letsopen.localoss.util.KeyHash;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 存储引擎：直接 bucket/key 布局（已确认 Q1）。
 *
 * <p>核心职责：
 * <ul>
 *   <li>原子写（tmp + rename + fsync，计划 3.4 节）</li>
 *   <li>对象元数据维护（.localoss/meta/&lt;sha256(key)&gt;.json）</li>
 *   <li>流式上传（内存恒定）与流式下载（Range 支持）</li>
 *   <li>同名冲突检测（R1）与元数据回填（R4）</li>
 * </ul>
 */
@Slf4j
@Component
public class StorageEngine {

    private final SystemProperties props;
    private final KeyValidator keyValidator;
    private final MetadataStore metadataStore;
    private final LockManager lockManager;
    private final QuotaManager quotaManager;

    public StorageEngine(SystemProperties props, KeyValidator keyValidator,
                         MetadataStore metadataStore, LockManager lockManager,
                         QuotaManager quotaManager) {
        this.props = props;
        this.keyValidator = keyValidator;
        this.metadataStore = metadataStore;
        this.lockManager = lockManager;
        this.quotaManager = quotaManager;
    }

    // ==================== Bucket ====================

    public void createBucket(String bucket) {
        keyValidator.validateBucketName(bucket);
        Path dir = keyValidator.bucketDir(bucket);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR, "Failed to create bucket directory", e);
        }
        metadataStore.ensureBucketConfig(dir, bucket);
    }

    public boolean bucketExists(String bucket) {
        Path dir = keyValidator.bucketDir(bucket);
        return Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS);
    }

    public void deleteBucket(String bucket) {
        keyValidator.validateBucketName(bucket);
        Path dir = keyValidator.bucketDir(bucket);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET);
        }
        if (!props.isForceDeleteBucket() && !isEmptyForDelete(dir)) {
            throw new S3Exception(S3ErrorCode.BUCKET_NOT_EMPTY);
        }
        try (var lock = lockManager.acquire(dir, "bucket:" + bucket)) {
            deleteRecursively(dir);
        } catch (Exception e) {
            if (e instanceof S3Exception se) {
                throw se;
            }
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR, "Failed to delete bucket", e);
        }
    }

    /** 桶是否"空"（忽略 .localoss 元数据目录本身） */
    private boolean isEmptyForDelete(Path bucketDir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(bucketDir)) {
            for (Path p : stream) {
                if (p.getFileName().toString().equals(".localoss")) {
                    continue;
                }
                return false;
            }
            return true;
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR, "Failed to inspect bucket", e);
        }
    }

    public List<String> listBuckets() {
        Path root = Path.of(props.getDataPath());
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (name.equals(".localoss") || name.startsWith(".")) {
                    continue;
                }
                try {
                    keyValidator.validateBucketName(name);
                    out.add(name);
                } catch (S3Exception e) {
                    log.warn("Ignoring non-S3 bucket name directory: {}", name);
                }
            }
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR, "Failed to list buckets", e);
        }
        out.sort(String::compareTo);
        return out;
    }

    // ==================== Object ====================

    /**
     * 流式上传对象（原子写协议，计划 3.4 节）。
     *
     * <p>返回写入的元数据（含 ETag/checksums）。
     */
    public ObjectMetadata putObject(String bucket, String key, InputStream body,
                                    Map<String, String> contentHeaders,
                                    Map<String, String> userMetadata,
                                    Map<String, String> checksumsProvided,
                                    String cannedAcl) {
        Path bucketDir = keyValidator.bucketDir(bucket);
        if (!Files.isDirectory(bucketDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET);
        }
        Path target = keyValidator.toFilePath(bucket, key);
        keyValidator.checkSymlinkEscape(bucketDir, target);

        Path metaDir = bucketDir.resolve(".localoss");
        Path tmpDir = metaDir.resolve("tmp");
        Path dataTmp;
        DigestResult digest;

        // 1. 流式写临时文件（无锁），同时计算 MD5/SHA256/CRC32 与 size
        try {
            Files.createDirectories(tmpDir);
            dataTmp = Files.createTempFile(tmpDir, props.getInstanceId() + "-", ".data");
            digest = streamToTemp(body, dataTmp);
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR, "Failed to write upload temp file", e);
        }

        try {
            // 大小与校验校验
            validateUpload(digest, checksumsProvided);
            // 2. 拿 key 锁，检查冲突 + 条件，原子 rename（配额检查也在锁内防并发超限）
            try (var lockHandle = lockManager.acquire(bucketDir, "key:" + key)) {
                keyValidator.checkConflict(bucketDir, target);
                // 配额原子预留（锁内，减去被覆盖对象的旧大小）
                long oldSize = 0;
                if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        oldSize = Files.size(target);
                    } catch (IOException quotaStatEx) {
                        // ignore
                    }
                }
                long netAdd = digest.size - oldSize;
                if (!quotaManager.tryReserve(bucket, netAdd)) {
                    throw new S3Exception(S3ErrorCode.ENTITY_TOO_LARGE,
                            "Bucket quota exceeded: limit " + quotaManager.quotaOf(bucket));
                }
                try {
                    // 确保父目录
                    Path parent = target.getParent();
                    if (parent != null && !parent.equals(bucketDir)) {
                        Files.createDirectories(parent);
                    }
                    // 父目录 symlink 防护：resolve 后必须仍在 bucket 内
                    keyValidator.checkParentSymlinkEscape(bucketDir, target);
                    if (key.endsWith("/")) {
                        // 目录型零字节对象
                        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                            throw new S3Exception(S3ErrorCode.KEY_CONFLICT,
                                    "Existing file blocks directory object");
                        }
                        Files.createDirectories(target);
                        Files.deleteIfExists(dataTmp);
                    } else {
                        atomicMove(dataTmp, target);
                        fsyncFile(target);
                    }

                    ObjectMetadata meta = buildMeta(bucket, key, digest, contentHeaders,
                            userMetadata);
                    if (cannedAcl != null) {
                        meta.setAcl(cannedAcl);
                    }
                    metadataStore.writeMeta(bucketDir, key, meta);
                    return meta;
                } catch (Exception commitEx) {
                    // 提交失败：回滚配额预留
                    quotaManager.release(bucket, netAdd);
                    if (commitEx instanceof S3Exception se) {
                        throw se;
                    }
                    throw new S3Exception(S3ErrorCode.INTERNAL_ERROR,
                            "Failed to commit object", commitEx);
                }
            } catch (Exception lockCloseEx) {
                // lock close 或 acquire 异常
                if (lockCloseEx instanceof S3Exception se) {
                    throw se;
                }
                throw new S3Exception(S3ErrorCode.INTERNAL_ERROR,
                        "Failed to commit object", lockCloseEx);
            }
        } finally {
            try {
                Files.deleteIfExists(dataTmp);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private void validateUpload(DigestResult digest, Map<String, String> checksumsProvided) {
        if (digest.size > props.getMaxSinglePutSize()) {
            throw new S3Exception(S3ErrorCode.ENTITY_TOO_LARGE,
                    "Object exceeds maximum single PUT size");
        }
        if (props.getMaxObjectSize() > 0 && digest.size > props.getMaxObjectSize()) {
            throw new S3Exception(S3ErrorCode.ENTITY_TOO_LARGE);
        }
        if (checksumsProvided != null) {
            checksumsProvided.forEach((algo, expected) -> {
                String actual = digest.base64(algo);
                if (actual == null) {
                    throw new S3Exception(S3ErrorCode.INTERNAL_ERROR,
                            "Unsupported checksum algorithm " + algo);
                }
                if (!actual.equals(expected)) {
                    throw new S3Exception(S3ErrorCode.INVALID_DIGEST,
                            "Checksum " + algo + " mismatch");
                }
            });
        }
    }

    private ObjectMetadata buildMeta(String bucket, String key, DigestResult digest,
                                     Map<String, String> contentHeaders,
                                     Map<String, String> userMetadata) {
        ObjectMetadata meta = new ObjectMetadata();
        meta.setKey(key);
        meta.setSize(digest.size);
        meta.setEtag("\"" + digest.md5Hex() + "\"");
        meta.setChecksums(Map.of(
                "MD5", Base64.getEncoder().encodeToString(digest.md5()),
                "SHA256", Base64.getEncoder().encodeToString(digest.sha256()),
                "CRC32", Base64.getEncoder().encodeToString(digest.crc32Bytes())
        ));
        meta.setChecksumType("FULL_OBJECT");
        meta.setContentType(detectContentType(key, contentHeaders));
        meta.setLastModified(java.time.Instant.now().toString());
        meta.setOwner(Map.of("id", "local", "displayName", "local"));
        meta.setStorageClass("STANDARD");
        if (contentHeaders != null) {
            meta.setContentEncoding(contentHeaders.get("Content-Encoding"));
            meta.setContentLanguage(contentHeaders.get("Content-Language"));
            meta.setContentDisposition(contentHeaders.get("Content-Disposition"));
            meta.setCacheControl(contentHeaders.get("Cache-Control"));
            meta.setExpires(contentHeaders.get("Expires"));
        }
        if (userMetadata != null && !userMetadata.isEmpty()) {
            long total = userMetadata.entrySet().stream()
                    .mapToLong(e -> e.getKey().length() + e.getValue().length())
                    .sum();
            if (total > 2048) {
                throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                        "User metadata exceeds 2KB limit");
            }
            meta.setUserMetadata(new TreeMap<>(userMetadata));
        }
        return meta;
    }

    private String detectContentType(String key, Map<String, String> contentHeaders) {
        if (contentHeaders != null && contentHeaders.get("Content-Type") != null
                && !contentHeaders.get("Content-Type").isEmpty()) {
            return contentHeaders.get("Content-Type");
        }
        String name = key.endsWith("/") ? "" : key.substring(key.lastIndexOf('/') + 1);
        String ct = java.net.URLConnection.guessContentTypeFromName(name);
        return ct == null ? "application/octet-stream" : ct;
    }

    // ==================== Read ====================

    public boolean objectExists(Path target, boolean directoryObject) {
        if (directoryObject) {
            return Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS);
        }
        return Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * 获取元数据；若无持久化 meta 则按文件系统属性即时生成并异步回填（R4）。
     */
    public ObjectMetadata getMeta(String bucket, String key, Path target) {
        Path bucketDir = keyValidator.bucketDir(bucket);
        ObjectMetadata meta = metadataStore.readMeta(bucketDir, key);
        if (meta != null) {
            return meta;
        }
        // 从文件系统即时生成
        meta = new ObjectMetadata();
        meta.setKey(key);
        try {
            if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                meta.setSize(Files.size(target));
                byte[] md5 = md5OfFile(target);
                meta.setEtag("\"" + hex(md5) + "\"");
                meta.setChecksums(Map.of("MD5", Base64.getEncoder().encodeToString(md5)));
                meta.setLastModified(java.time.Instant.ofEpochMilli(
                        Files.getLastModifiedTime(target, LinkOption.NOFOLLOW_LINKS).toMillis()).toString());
            } else {
                meta.setSize(0);
                meta.setEtag("\"d41d8cd98f00b204e9800998ecf8427e\"");
                meta.setLastModified(java.time.Instant.now().toString());
            }
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR, "Failed to stat object", e);
        }
        meta.setContentType(detectContentType(key, null));
        meta.setOwner(Map.of("id", "local", "displayName", "local"));
        meta.setStorageClass("STANDARD");
        meta.setChecksumType("FULL_OBJECT");

        // 异步回填（R4）
        ObjectMetadata finalMeta = meta;
        Thread t = new Thread(() -> {
            try (var lock = lockManager.tryAcquire(bucketDir, "key:" + key)) {
                if (lock != null) {
                    metadataStore.writeMeta(bucketDir, key, finalMeta);
                }
            } catch (Exception e) {
                log.debug("async meta backfill failed for {}: {}", key, e.getMessage());
            }
        }, "meta-backfill-" + KeyHash.sha256Hex(key).substring(0, 8));
        t.setDaemon(true);
        t.start();
        return meta;
    }

    /**
     * 打开对象数据流（支持 Range）；调用方负责关闭。
     */
    public ObjectStream openObject(Path target, long offset, long length) throws IOException {
        FileChannel ch = FileChannel.open(target, StandardOpenOption.READ);
        long size = ch.size();
        if (offset > size) {
            ch.close();
            throw new S3Exception(S3ErrorCode.INVALID_RANGE,
                    "Range start " + offset + " exceeds object size " + size);
        }
        long len = Math.min(length, size - offset);
        var stream = java.nio.channels.Channels.newInputStream(ch);
        // skip to offset
        long skipped = 0;
        while (skipped < offset) {
            long s = stream.skip(offset - skipped);
            if (s <= 0) {
                break;
            }
            skipped += s;
        }
        // cap at requested range length
        InputStream bounded = new BoundedInputStream(stream, len);
        return new ObjectStream(bounded, size, len, ch);
    }

    public static final class ObjectStream implements AutoCloseable {
        private final InputStream stream;
        private final long totalSize;
        private final long rangeLength;
        private final FileChannel channel;

        ObjectStream(InputStream stream, long totalSize, long rangeLength, FileChannel channel) {
            this.stream = stream;
            this.totalSize = totalSize;
            this.rangeLength = rangeLength;
            this.channel = channel;
        }

        public InputStream stream() {
            return stream;
        }

        public long totalSize() {
            return totalSize;
        }

        public long rangeLength() {
            return rangeLength;
        }

        @Override
        public void close() throws IOException {
            try (FileChannel c = channel) {
                stream.close();
            }
        }
    }

    /** 简单限长输入流 */
    static final class BoundedInputStream extends java.io.FilterInputStream {
        private long remaining;

        BoundedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int r = super.read();
            if (r >= 0) {
                remaining--;
            }
            return r;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int r = super.read(b, off, (int) Math.min(len, remaining));
            if (r > 0) {
                remaining -= r;
            }
            return r;
        }

        @Override
        public long skip(long n) throws IOException {
            long s = super.skip(Math.min(n, remaining));
            if (s > 0) {
                remaining -= s;
            }
            return s;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(super.available(), remaining);
        }
    }

    // ==================== Delete ====================

    public void deleteObject(String bucket, String key) {
        Path bucketDir = keyValidator.bucketDir(bucket);
        if (!Files.isDirectory(bucketDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET);
        }
        Path target = keyValidator.toFilePath(bucket, key);
        keyValidator.checkSymlinkEscape(bucketDir, target);
        try (var ignored = lockManager.acquire(bucketDir, "key:" + key)) {
            long freed = 0;
            if (key.endsWith("/")) {
                deleteEmptyDirWithParents(bucketDir, target);
            } else {
                if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                    // S3 key 语义下 DELETE 文件型 key 打在目录上：尝试递归删除（简单模型）
                    deleteRecursively(target);
                } else {
                    try {
                        freed = Files.size(target);
                    } catch (IOException sizeEx) {
                        // ignore
                    }
                    Files.deleteIfExists(target);
                }
            }
            metadataStore.deleteMeta(bucketDir, key);
            quotaManager.release(bucket, freed);
            // 向上清理空父目录（R3）
            cleanupEmptyParents(bucketDir, target.getParent());
        } catch (Exception e) {
            if (e instanceof S3Exception se) {
                throw se;
            }
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR, "Failed to delete object", e);
        }
    }

    /** 删除目录型对象（a/）与其空父目录链 */
    private void deleteEmptyDirWithParents(Path bucketRoot, Path dir) {
        try {
            if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
                    if (s.iterator().hasNext()) {
                        throw new S3Exception(S3ErrorCode.KEY_CONFLICT,
                                "Directory object has children");
                    }
                }
                Files.delete(dir);
            }
            cleanupEmptyParents(bucketRoot, dir.getParent());
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR, "Failed to delete directory object", e);
        }
    }

    /** 向上删除本服务创建的空目录，遇非空或 bucket root 即停（R3） */
    private void cleanupEmptyParents(Path bucketRoot, Path dir) {
        while (dir != null && dir.startsWith(bucketRoot) && !dir.equals(bucketRoot)) {
            try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
                if (s.iterator().hasNext()) {
                    return;
                }
            } catch (IOException e) {
                return;
            }
            try {
                Files.deleteIfExists(dir);
            } catch (IOException e) {
                return;
            }
            dir = dir.getParent();
        }
    }

    // ==================== Copy ====================

    /**
     * 复制对象（Phase 2）。source 也使用「bucket/key」形式。
     */
    public ObjectMetadata copyObject(String sourceBucket, String sourceKey,
                                     String targetBucket, String targetKey,
                                     Map<String, String> newContentHeaders,
                                     Map<String, String> newUserMetadata,
                                     String metadataDirective,
                                     String cannedAcl) {
        Path sourceDir = keyValidator.bucketDir(sourceBucket);
        if (!Files.isDirectory(sourceDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET);
        }
        Path sourceTarget = keyValidator.toFilePath(sourceBucket, sourceKey);
        if (!objectExists(sourceTarget, sourceKey.endsWith("/"))) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_KEY);
        }
        ObjectMetadata sourceMeta = getMeta(sourceBucket, sourceKey, sourceTarget);

        // 目标写入（目录型对象时直接 mkdir）
        try (InputStream in = sourceKey.endsWith("/")
                ? InputStream.nullInputStream()
                : Files.newInputStream(sourceTarget, StandardOpenOption.READ)) {
            Map<String, String> contentHeaders =
                    "REPLACE".equals(metadataDirective) && newContentHeaders != null
                            ? newContentHeaders
                            : sourceHeaderMap(sourceMeta);
            Map<String, String> userMeta = "REPLACE".equals(metadataDirective)
                    ? newUserMetadata
                    : sourceMeta.getUserMetadata();
            String acl = cannedAcl != null ? cannedAcl : sourceMeta.getAcl();
            return putObject(targetBucket, targetKey, in, contentHeaders, userMeta, null, acl);
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR, "Failed to copy object", e);
        }
    }

    private Map<String, String> sourceHeaderMap(ObjectMetadata meta) {
        Map<String, String> m = new java.util.HashMap<>();
        if (meta.getContentType() != null) {
            m.put("Content-Type", meta.getContentType());
        }
        if (meta.getContentDisposition() != null) {
            m.put("Content-Disposition", meta.getContentDisposition());
        }
        if (meta.getContentEncoding() != null) {
            m.put("Content-Encoding", meta.getContentEncoding());
        }
        if (meta.getContentLanguage() != null) {
            m.put("Content-Language", meta.getContentLanguage());
        }
        if (meta.getCacheControl() != null) {
            m.put("Cache-Control", meta.getCacheControl());
        }
        if (meta.getExpires() != null) {
            m.put("Expires", meta.getExpires());
        }
        return m;
    }

    /**
     * 批量删除（Phase 2）。返回每个 key 是否删除成功（总是 true，缺失也算成功——S3 语义）。
     */
    public Map<String, Boolean> deleteObjects(String bucket, List<String> keys) {
        Path bucketDir = keyValidator.bucketDir(bucket);
        if (!Files.isDirectory(bucketDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET);
        }
        Map<String, Boolean> results = new java.util.LinkedHashMap<>();
        for (String key : keys) {
            try {
                deleteObject(bucket, key);
                results.put(key, true);
            } catch (S3Exception e) {
                results.put(key, false);
            }
        }
        return results;
    }

    // ==================== List ====================

    /**
     * 递归列举 bucket 内全部对象 key（跳过 .localoss），UTF-8 字节序排序。
     */
    public List<String> listAllKeys(String bucket, String prefix) {
        Path bucketDir = keyValidator.bucketDir(bucket);
        if (!Files.isDirectory(bucketDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET);
        }
        List<String> keys = new ArrayList<>();
        walkKeys(bucketDir, "", keys);
        // UTF-8 字节序排序（S3 规范）
        keys.sort((a, b) -> java.util.Arrays.compare(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)));
        if (prefix != null && !prefix.isEmpty()) {
            keys.removeIf(k -> !k.startsWith(prefix));
        }
        return keys;
    }

    private void walkKeys(Path dir, String rel, List<String> keys) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                if (name.equals(".localoss") || name.startsWith(".")) {
                    continue;
                }
                String childRel = rel.isEmpty() ? name : rel + "/" + name;
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    // 空目录视为"目录型零字节对象"
                    if (isEmptyDir(p)) {
                        keys.add(childRel + "/");
                    } else {
                        walkKeys(p, childRel, keys);
                        // 目录型对象同时也要列出（a/ 与 a/b 共存模型下，目录本身也可能代表 a/）
                    }
                } else {
                    keys.add(childRel);
                }
            }
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR, "Failed to walk objects", e);
        }
    }

    private boolean isEmptyDir(Path dir) {
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
            return !s.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }

    // ==================== Bucket tags ====================

    @SuppressWarnings("unchecked")
    public Map<String, String> getBucketTags(Path bucketDir) {
        Map<String, Object> cfg = metadataStore.ensureBucketConfig(bucketDir,
                bucketDir.getFileName().toString());
        Object tags = cfg.get("tagging");
        if (tags instanceof Map<?, ?> m) {
            Map<String, String> out = new java.util.LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
            return out;
        }
        return Map.of();
    }

    public void setBucketTags(Path bucketDir, Map<String, String> tags) {
        try (var lock = lockManager.acquire(bucketDir, "bucket-config")) {
            Map<String, Object> cfg = metadataStore.ensureBucketConfig(bucketDir,
                    bucketDir.getFileName().toString());
            cfg.put("tagging", new TreeMap<>(tags));
            metadataStore.writeBucketConfig(bucketDir, cfg);
        } catch (Exception e) {
            if (e instanceof S3Exception se) {
                throw se;
            }
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR, "Failed to set bucket tags", e);
        }
    }

    public String getBucketAcl(Path bucketDir) {
        Map<String, Object> cfg = metadataStore.ensureBucketConfig(bucketDir,
                bucketDir.getFileName().toString());
        Object acl = cfg.get("acl");
        return acl instanceof Map<?, ?> m ? (String) m.get("canned") : null;
    }

    public void setBucketAcl(Path bucketDir, String cannedAcl) {
        try (var lock = lockManager.acquire(bucketDir, "bucket-config")) {
            Map<String, Object> cfg = metadataStore.ensureBucketConfig(bucketDir,
                    bucketDir.getFileName().toString());
            cfg.put("acl", Map.of("canned", cannedAcl));
            metadataStore.writeBucketConfig(bucketDir, cfg);
        } catch (Exception e) {
            if (e instanceof S3Exception se) {
                throw se;
            }
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR, "Failed to set bucket ACL", e);
        }
    }

    public Map<String, Object> getBucketConfig(Path bucketDir) {
        return metadataStore.ensureBucketConfig(bucketDir, bucketDir.getFileName().toString());
    }

    public void writeBucketConfig(Path bucketDir, Map<String, Object> cfg) {
        metadataStore.writeBucketConfig(bucketDir, cfg);
    }

    /** 更新对象元数据（供 Tagging 等使用） */
    public void updateMeta(String bucket, String key, ObjectMetadata meta) {
        Path bucketDir = keyValidator.bucketDir(bucket);
        metadataStore.writeMeta(bucketDir, key, meta);
    }

    // ==================== helpers ====================

    private DigestResult streamToTemp(InputStream in, Path tmp) throws IOException {
        MessageDigest md5;
        MessageDigest sha256;
        java.util.zip.CRC32 crc32;
        try {
            md5 = MessageDigest.getInstance("MD5");
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        crc32 = new java.util.zip.CRC32();
        long size = 0;
        try (OutputStream out = Files.newOutputStream(tmp,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                md5.update(buf, 0, n);
                sha256.update(buf, 0, n);
                crc32.update(buf, 0, n);
                size += n;
            }
            out.flush();
        }
        return new DigestResult(size, md5.digest(), sha256.digest(), crc32.getValue());
    }

    public record DigestResult(long size, byte[] md5, byte[] sha256, long crc32) {
        public String md5Hex() {
            return hex(md5);
        }

        public String base64(String algo) {
            return switch (algo) {
                case "MD5" -> Base64.getEncoder().encodeToString(md5);
                case "SHA256" -> Base64.getEncoder().encodeToString(sha256);
                case "CRC32" -> Base64.getEncoder().encodeToString(crc32Bytes());
                default -> null;
            };
        }

        public byte[] crc32Bytes() {
            long v = crc32;
            return new byte[]{
                    (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v
            };
        }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) {
            sb.append(Character.forDigit((v >> 4) & 0xF, 16));
            sb.append(Character.forDigit(v & 0xF, 16));
        }
        return sb.toString();
    }

    /** 供 MultipartStore 使用 */
    public static String hexForTest(byte[] b) {
        return hex(b);
    }

    private byte[] md5OfFile(Path file) {
        try (InputStream in = Files.newInputStream(file, StandardOpenOption.READ)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return md.digest();
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR, "Failed to compute MD5", e);
        }
    }

    private void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            log.debug("ATOMIC_MOVE unsupported for {}; fallback", target);
            Files.move(source, target);
        }
    }

    private void fsyncFile(Path file) {
        if (!props.isFsync()) {
            return;
        }
        try (var ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.force(true);
        } catch (IOException e) {
            log.debug("fsync failed for {}: {}", file, e.getMessage());
        }
    }

    private void deleteRecursively(Path dir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    deleteRecursively(p);
                } else {
                    Files.deleteIfExists(p);
                }
            }
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR, "Failed to delete", e);
        }
    }
}
