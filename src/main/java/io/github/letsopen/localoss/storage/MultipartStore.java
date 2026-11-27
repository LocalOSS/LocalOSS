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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Multipart 存储层（计划 3.5 节）。
 *
 * <p>布局：{@code .localoss/uploads/<uploadId>/upload.json} + {@code parts/00001/{.data,.meta}}。
 * uploadId 含 instanceId 前缀（多实例唯一）。
 */
@Slf4j
@Component
public class MultipartStore {

    private static final String UPLOADS_DIR = "uploads";

    private final SystemProperties props;
    private final KeyValidator keyValidator;
    private final StorageEngine storage;
    private final MetadataStore metadataStore;
    private final LockManager lockManager;

    public MultipartStore(SystemProperties props, KeyValidator keyValidator,
                          StorageEngine storage, MetadataStore metadataStore,
                          LockManager lockManager) {
        this.props = props;
        this.keyValidator = keyValidator;
        this.storage = storage;
        this.metadataStore = metadataStore;
        this.lockManager = lockManager;
    }

    public record PartInfo(int number, long size, String etag, String lastModified) {
    }

    public record UploadInfo(String uploadId, String bucket, String key, Instant initiated) {
    }

    private Path uploadsRoot(Path bucketDir) {
        return bucketDir.resolve(".localoss").resolve(UPLOADS_DIR);
    }

    private Path uploadDir(Path bucketDir, String uploadId) {
        validateUploadId(uploadId);
        return uploadsRoot(bucketDir).resolve(uploadId);
    }

    /** uploadId 白名单校验：instanceId-UUID 格式（[A-Za-z0-9-]+，长度限制） */
    private void validateUploadId(String uploadId) {
        if (uploadId == null || uploadId.isEmpty()
                || uploadId.length() > 128
                || !uploadId.matches("[A-Za-z0-9-]+")
                || uploadId.contains("..")) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Invalid uploadId");
        }
    }

    private Path partsDir(Path bucketDir, String uploadId) {
        return uploadDir(bucketDir, uploadId).resolve("parts");
    }

    // ==================== Initiate ====================

    public String createUpload(String bucket, String key, Map<String, String> contentHeaders,
                               Map<String, String> userMetadata) {
        keyValidator.validateKey(key);
        Path bucketDir = keyValidator.bucketDir(bucket);
        if (!Files.isDirectory(bucketDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET);
        }
        String uploadId = props.getInstanceId() + "-" + UUID.randomUUID();
        try {
            Path dir = uploadDir(bucketDir, uploadId);
            Files.createDirectories(dir.resolve("parts"));
            String json = objectMapper().writeValueAsString(Map.of(
                    "bucket", bucket,
                    "key", key,
                    "initiated", Instant.now().toString(),
                    "instanceId", props.getInstanceId(),
                    "userMetadata", userMetadata == null ? Map.of() : userMetadata,
                    "contentType", contentHeaders == null ? "" :
                            contentHeaders.getOrDefault("Content-Type", "")
            ));
            Files.writeString(dir.resolve("upload.json"), json, StandardCharsets.UTF_8);
            return uploadId;
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR, "Failed to initiate multipart upload", e);
        }
    }

    private com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
        com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
        return m;
    }

    // ==================== UploadPart ====================

    public PartInfo uploadPart(String bucket, String key, String uploadId, int partNumber,
                               InputStream body) {
        validatePartNumber(partNumber);
        Path bucketDir = requireUpload(bucket, key, uploadId);
        try {
            Path tmpDir = bucketDir.resolve(".localoss").resolve("tmp");
            Files.createDirectories(tmpDir);
            Path tmp = Files.createTempFile(tmpDir,
                    props.getInstanceId() + "-part-", ".tmp");
            DigestResult digest = streamToDigest(body, tmp);
            if (digest.size() > props.getMaxPartSize()) {
                Files.deleteIfExists(tmp);
                throw new S3Exception(S3ErrorCode.ENTITY_TOO_LARGE, "Part exceeds max part size");
            }
            Path partDir = partsDir(bucketDir, uploadId)
                    .resolve(String.format("%05d", partNumber));
            Files.createDirectories(partDir);
            atomicMove(tmp, partDir.resolve(".data"));
            String etag = "\"" + StorageEngine.hexForTest(digest.md5()) + "\"";
            String metaJson = objectMapper().writeValueAsString(Map.of(
                    "size", digest.size(),
                    "etag", etag,
                    "lastModified", Instant.now().toString()
            ));
            Files.writeString(partDir.resolve(".meta"), metaJson, StandardCharsets.UTF_8);
            return new PartInfo(partNumber, digest.size(), etag, Instant.now().toString());
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR, "Failed to upload part", e);
        }
    }

    private record DigestResult(long size, byte[] md5) {
    }

    private DigestResult streamToDigest(InputStream in, Path tmp) throws IOException {
        MessageDigest md5;
        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        long size = 0;
        try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                md5.update(buf, 0, n);
                size += n;
            }
        }
        return new DigestResult(size, md5.digest());
    }

    // ==================== ListParts ====================

    public List<PartInfo> listParts(String bucket, String key, String uploadId) {
        Path bucketDir = requireUpload(bucket, key, uploadId);
        List<PartInfo> parts = new ArrayList<>();
        Path partsDirPath = partsDir(bucketDir, uploadId);
        if (!Files.isDirectory(partsDirPath, LinkOption.NOFOLLOW_LINKS)) {
            return parts;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(partsDirPath)) {
            for (Path d : stream) {
                int num = Integer.parseInt(d.getFileName().toString());
                Path data = d.resolve(".data");
                Path meta = d.resolve(".meta");
                if (!Files.isRegularFile(data, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                long size;
                String etag;
                String lastModified;
                if (Files.isRegularFile(meta, LinkOption.NOFOLLOW_LINKS)) {
                    var map = objectMapper().readValue(Files.readString(meta, StandardCharsets.UTF_8),
                            Map.class);
                    size = ((Number) map.get("size")).longValue();
                    etag = (String) map.get("etag");
                    lastModified = (String) map.get("lastModified");
                } else {
                    size = Files.size(data);
                    etag = "\"\"";
                    lastModified = Instant.now().toString();
                }
                parts.add(new PartInfo(num, size, etag, lastModified));
            }
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR, "Failed to list parts", e);
        }
        parts.sort(Comparator.comparingInt(PartInfo::number));
        return parts;
    }

    // ==================== ListMultipartUploads ====================

    public List<UploadInfo> listUploads(String bucket) {
        Path bucketDir = keyValidator.bucketDir(bucket);
        if (!Files.isDirectory(bucketDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET);
        }
        List<UploadInfo> uploads = new ArrayList<>();
        Path root = uploadsRoot(bucketDir);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return uploads;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path d : stream) {
                Path json = d.resolve("upload.json");
                if (!Files.isRegularFile(json, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                var map = objectMapper().readValue(Files.readString(json, StandardCharsets.UTF_8),
                        Map.class);
                uploads.add(new UploadInfo(d.getFileName().toString(),
                        (String) map.get("bucket"),
                        (String) map.get("key"),
                        Instant.parse((String) map.get("initiated"))));
            }
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR, "Failed to list uploads", e);
        }
        uploads.sort(Comparator.comparing(UploadInfo::key).thenComparing(UploadInfo::initiated));
        return uploads;
    }

    // ==================== Complete ====================

    public ObjectMetadata complete(String bucket, String key, String uploadId,
                                   List<Integer> partNumbers) {
        validatePartNumbers(partNumbers);
        Path bucketDir = requireUpload(bucket, key, uploadId);
        List<PartInfo> uploaded = listParts(bucket, key, uploadId);
        Map<Integer, PartInfo> byNumber = new HashMap<>();
        uploaded.forEach(p -> byNumber.put(p.number(), p));

        // 校验 part ETag 已上传；大小限制（除最后一片外 >= minPartSize）
        long minPart = props.getMinPartSize();
        for (int i = 0; i < partNumbers.size(); i++) {
            int num = partNumbers.get(i);
            PartInfo p = byNumber.get(num);
            if (p == null) {
                throw new S3Exception(S3ErrorCode.INVALID_PART,
                        "Part " + num + " not uploaded");
            }
            if (i < partNumbers.size() - 1 && p.size() < minPart) {
                throw new S3Exception(S3ErrorCode.ENTITY_TOO_SMALL,
                        "Part " + num + " smaller than minimum part size");
            }
        }

        // 拼接到临时文件，计算 multipart ETag
        Path bucketRoot = keyValidator.bucketDir(bucket);
        Path target = keyValidator.toFilePath(bucket, key);
        try {
            Files.createDirectories(bucketRoot.resolve(".localoss").resolve("tmp"));
            Path tmp = Files.createTempFile(bucketRoot.resolve(".localoss").resolve("tmp"),
                    props.getInstanceId() + "-mpu-", ".tmp");
            MessageDigest concatMd5;
            try {
                concatMd5 = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            long totalSize = 0;
            try (OutputStream out = Files.newOutputStream(tmp,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                for (int num : partNumbers) {
                    Path data = partsDir(bucketDir, uploadId)
                            .resolve(String.format("%05d", num)).resolve(".data");
                    byte[] partMd5 = md5Bytes(data);
                    concatMd5.update(partMd5);
                    try (InputStream in = Files.newInputStream(data, StandardOpenOption.READ)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            totalSize += n;
                        }
                    }
                }
            }

            String multipartEtag = StorageEngine.hexForTest(concatMd5.digest())
                    + "-" + partNumbers.size();
            try (var lock = lockManager.acquire(bucketDir, "key:" + key)) {
                lock.getClass(); // AutoCloseable
                keyValidator.checkConflict(bucketRoot, target);
                Path parent = target.getParent();
                if (parent != null && !parent.equals(bucketRoot)) {
                    Files.createDirectories(parent);
                }
                atomicMove(tmp, target);

                ObjectMetadata meta = new ObjectMetadata();
                meta.setKey(key);
                meta.setSize(totalSize);
                meta.setEtag("\"" + multipartEtag + "\"");
                meta.setParts(partNumbers.size());
                var uploadMap = objectMapper().readValue(
                        Files.readString(uploadDir(bucketDir, uploadId).resolve("upload.json"),
                                StandardCharsets.UTF_8), Map.class);
                String ct = (String) uploadMap.getOrDefault("contentType", "");
                meta.setContentType(ct.isEmpty() ? "application/octet-stream" : ct);
                meta.setChecksumType("FULL_OBJECT");
                meta.setChecksums(Map.of());
                meta.setLastModified(Instant.now().toString());
                meta.setOwner(Map.of("id", "local", "displayName", "local"));
                meta.setStorageClass("STANDARD");
                @SuppressWarnings("unchecked")
                Map<String, String> um = (Map<String, String>) uploadMap.get("userMetadata");
                if (um != null && !um.isEmpty()) {
                    meta.setUserMetadata(um);
                }
                metadataStore.writeMeta(bucketDir, key, meta);

                // 清理 upload 目录
                deleteRecursively(uploadDir(bucketDir, uploadId));
                return meta;
            }
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR, "Failed to complete multipart upload", e);
        } catch (Exception e) {
            if (e instanceof S3Exception se) {
                throw se;
            }
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR, "Failed to complete multipart upload", e);
        }
    }

    private void validatePartNumbers(List<Integer> numbers) {
        if (numbers == null || numbers.isEmpty()) {
            throw new S3Exception(S3ErrorCode.MALFORMED_XML, "No parts in CompleteMultipartUpload");
        }
        if (numbers.size() > props.getMaxParts()) {
            throw new S3Exception(S3ErrorCode.TOO_MANY_PARTS);
        }
        int prev = 0;
        for (int n : numbers) {
            if (n <= prev) {
                throw new S3Exception(S3ErrorCode.INVALID_PART_ORDER,
                        "Parts must be in ascending order");
            }
            validatePartNumber(n);
            prev = n;
        }
    }

    private void validatePartNumber(int n) {
        if (n < 1 || n > props.getMaxParts()) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                    "Part number must be between 1 and " + props.getMaxParts());
        }
    }

    // ==================== Abort ====================

    public void abort(String bucket, String key, String uploadId) {
        Path bucketDir = requireUpload(bucket, key, uploadId);
        deleteRecursively(uploadDir(bucketDir, uploadId));
    }

    // ==================== GC ====================

    /**
     * 清理超过 maxAgeDays 的未完成 upload。
     */
    public int cleanupExpiredUploads(String bucket, int maxAgeDays) {
        Path bucketDir = keyValidator.bucketDir(bucket);
        Path root = uploadsRoot(bucketDir);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(maxAgeDays));
        int removed = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path d : stream) {
                Path json = d.resolve("upload.json");
                if (!Files.isRegularFile(json, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                var map = objectMapper().readValue(Files.readString(json, StandardCharsets.UTF_8),
                        Map.class);
                Instant initiated = Instant.parse((String) map.get("initiated"));
                if (initiated.isBefore(cutoff)) {
                    deleteRecursively(d);
                    removed++;
                }
            }
        } catch (IOException e) {
            log.warn("GC uploads failed for bucket {}: {}", bucket, e.getMessage());
        }
        return removed;
    }

    // ==================== helpers ====================

    private Path requireUpload(String bucket, String key, String uploadId) {
        validateUploadId(uploadId);
        Path bucketDir = keyValidator.bucketDir(bucket);
        if (!Files.isDirectory(bucketDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET);
        }
        Path dir = uploadDir(bucketDir, uploadId);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_UPLOAD);
        }
        return bucketDir;
    }

    private byte[] md5Bytes(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file, StandardOpenOption.READ)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
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
            log.debug("delete recursively failed: {}", dir, e);
        }
    }
}
