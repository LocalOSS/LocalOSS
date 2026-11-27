package io.github.letsopen.localoss.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.letsopen.localoss.config.SystemProperties;
import io.github.letsopen.localoss.error.S3ErrorCode;
import io.github.letsopen.localoss.error.S3Exception;
import io.github.letsopen.localoss.model.ObjectMetadata;
import io.github.letsopen.localoss.util.KeyHash;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 对象/桶元数据持久化（计划 3.3 节）。
 *
 * <p>位置：每个 bucket 下 {@code .localoss/meta/<sha256(key)>.json}；桶配置 {@code .localoss/bucket.json}。
 * 写入均为 tmp 文件 + 原子 rename。
 */
@Slf4j
@Component
public class MetadataStore {

    private static final String META_DIR = ".localoss";
    private static final String META_SUBDIR = "meta";
    private static final String BUCKET_FILE = "bucket.json";

    private final SystemProperties props;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);

    public MetadataStore(SystemProperties props) {
        this.props = props;
    }

    public Path metaDir(Path bucketDir) {
        return bucketDir.resolve(META_DIR).resolve(META_SUBDIR);
    }

    public Path bucketConfigFile(Path bucketDir) {
        return bucketDir.resolve(META_DIR).resolve(BUCKET_FILE);
    }

    public Path metaFile(Path bucketDir, String key) {
        return metaDir(bucketDir).resolve(KeyHash.sha256Hex(key) + ".json");
    }

    /**
     * 读取对象元数据；不存在返回 null。
     */
    public ObjectMetadata readMeta(Path bucketDir, String key) {
        Path f = metaFile(bucketDir, key);
        if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            return mapper.readValue(Files.readString(f, StandardCharsets.UTF_8), ObjectMetadata.class);
        } catch (IOException e) {
            log.warn("Failed to read meta for key {} in {}: {}", key, bucketDir, e.getMessage());
            return null;
        }
    }

    /**
     * 原子写对象元数据。
     */
    public void writeMeta(Path bucketDir, String key, ObjectMetadata meta) {
        try {
            Path dir = metaDir(bucketDir);
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "meta-", ".tmp");
            Files.writeString(tmp, mapper.writeValueAsString(meta), StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            Path target = metaFile(bucketDir, key);
            atomicMove(tmp, target);
            fsyncFile(target);
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR, "Failed to write object metadata", e);
        }
    }

    /**
     * 删除对象元数据（存在则删）。
     */
    public void deleteMeta(Path bucketDir, String key) {
        try {
            Files.deleteIfExists(metaFile(bucketDir, key));
        } catch (IOException e) {
            log.warn("Failed to delete meta for key {}: {}", key, e.getMessage());
        }
    }

    /**
     * 读取桶配置；不存在返回 null。
     */
    public Map<String, Object> readBucketConfig(Path bucketDir) {
        Path f = bucketConfigFile(bucketDir);
        if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            return mapper.readValue(Files.readString(f, StandardCharsets.UTF_8),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (IOException e) {
            log.warn("Failed to read bucket config {}: {}", f, e.getMessage());
            return null;
        }
    }

    /**
     * 原子写桶配置。
     */
    public void writeBucketConfig(Path bucketDir, Map<String, Object> config) {
        try {
            Path dir = bucketDir.resolve(META_DIR);
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "bucket-", ".tmp");
            Files.writeString(tmp, mapper.writeValueAsString(config), StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            atomicMove(tmp, bucketConfigFile(bucketDir));
            fsyncFile(bucketConfigFile(bucketDir));
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR, "Failed to write bucket config", e);
        }
    }

    /**
     * 首次访问桶时生成默认配置（创建时间取目录 ctime）。
     */
    public Map<String, Object> ensureBucketConfig(Path bucketDir, String bucketName) {
        Map<String, Object> cfg = readBucketConfig(bucketDir);
        if (cfg != null) {
            return cfg;
        }
        cfg = new HashMap<>();
        cfg.put("name", bucketName);
        try {
            cfg.put("createdAt", Instant.ofEpochMilli(
                    Files.readAttributes(bucketDir,
                            java.nio.file.attribute.BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS).creationTime().toInstant().toEpochMilli()
            ).toString());
        } catch (IOException e) {
            cfg.put("createdAt", Instant.now().toString());
        }
        cfg.put("region", props.getRegion());
        cfg.put("owner", Map.of("id", "local", "displayName", "local"));
        writeBucketConfig(bucketDir, cfg);
        return cfg;
    }

    private void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            log.debug("ATOMIC_MOVE unsupported for {}; falling back to plain move", target);
            Files.move(source, target);
        }
    }

    private void fsyncFile(Path file) {
        if (!props.isFsync()) {
            return;
        }
        try (var ch = java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.force(true);
        } catch (IOException e) {
            log.debug("fsync failed for {}: {}", file, e.getMessage());
        }
    }
}
