package io.github.letsopen.localoss.storage;

import io.github.letsopen.localoss.config.SystemProperties;
import io.github.letsopen.localoss.error.S3ErrorCode;
import io.github.letsopen.localoss.error.S3Exception;
import io.github.letsopen.localoss.util.KeyHash;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 基于目录创建的租约锁（计划 3.6 节）。
 *
 * <p>不使用 POSIX FileLock（NFSv3/v4 行为不一致）。锁目录内写 owner.json（instanceId、createdAt、expiresAt）；
 * 超过租约时间视为死锁，其他实例可接管（rename 到 .stale-* 后删除）。
 */
@Slf4j
@Component
public class LockManager {

    private static final String LOCKS_DIR = "locks";
    private static final String OWNER_FILE = "owner.json";

    private final SystemProperties props;

    public LockManager(SystemProperties props) {
        this.props = props;
    }

    private Path locksRoot(Path bucketDir) {
        return bucketDir.resolve(".localoss").resolve(LOCKS_DIR);
    }

    private Path lockPath(Path bucketDir, String resource) {
        return locksRoot(bucketDir).resolve(KeyHash.sha256Hex(resource) + ".lock");
    }

    /**
     * 获取锁（阻塞重试直到成功）。返回 AutoCloseable 解锁句柄。
     */
    public AutoCloseable acquire(Path bucketDir, String resource) {
        long leaseMs = props.getLockLeaseSeconds() * 1000L;
        long deadline = System.currentTimeMillis() + leaseMs * 10L; // 总等待上限 10x 租约
        Path lock = lockPath(bucketDir, resource);
        while (System.currentTimeMillis() < deadline) {
            try {
                Files.createDirectories(lock.getParent());
                Files.createDirectory(lock); // atomic on NFS
                writeOwner(lock);
                return () -> release(lock);
            } catch (IOException e) {
                // 目录已存在：尝试接管过期锁
                if (tryTakeoverExpired(lock, leaseMs)) {
                    try {
                        writeOwner(lock);
                        return () -> release(lock);
                    } catch (IOException ignored) {
                        // 被别的实例抢先，继续重试
                    }
                }
                sleepQuietly(50);
            }
        }
        throw new S3Exception(S3ErrorCode.SERVICE_UNAVAILABLE,
                "Timeout acquiring lock for resource in bucket");
    }

    /**
     * 尝试获取锁，不阻塞。成功返回解锁句柄，失败返回 null。
     */
    public AutoCloseable tryAcquire(Path bucketDir, String resource) {
        long leaseMs = props.getLockLeaseSeconds() * 1000L;
        Path lock = lockPath(bucketDir, resource);
        try {
            Files.createDirectories(lock.getParent());
            Files.createDirectory(lock);
            writeOwner(lock);
            return () -> release(lock);
        } catch (IOException e) {
            if (tryTakeoverExpired(lock, leaseMs)) {
                try {
                    writeOwner(lock);
                    return () -> release(lock);
                } catch (IOException ignored) {
                    // fall through
                }
            }
            return null;
        }
    }

    private boolean tryTakeoverExpired(Path lock, long leaseMs) {
        try {
            Path owner = lock.resolve(OWNER_FILE);
            if (!Files.isRegularFile(owner, LinkOption.NOFOLLOW_LINKS)) {
                // owner 未写完即崩溃：仅当锁目录足够老才接管
                Instant created = Instant.ofEpochMilli(
                        Files.getLastModifiedTime(lock, LinkOption.NOFOLLOW_LINKS).toMillis());
                if (Duration.between(created, Instant.now()).toMillis() > leaseMs) {
                    return staleRename(lock);
                }
                return false;
            }
            String json = Files.readString(owner, StandardCharsets.UTF_8);
            String expiresAt = extract(json, "expiresAt");
            if (expiresAt != null && Instant.parse(expiresAt).isBefore(Instant.now())) {
                return staleRename(lock);
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean staleRename(Path lock) {
        try {
            Path stale = lock.getParent().resolve(".stale-" + UUID.randomUUID());
            Files.move(lock, stale);
            deleteRecursively(stale);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void writeOwner(Path lock) throws IOException {
        Instant now = Instant.now();
        Instant expires = now.plusSeconds(props.getLockLeaseSeconds());
        String json = String.format(
                "{\"instanceId\":\"%s\",\"createdAt\":\"%s\",\"expiresAt\":\"%s\"}%n",
                props.getInstanceId(), now, expires);
        Files.writeString(lock.resolve(OWNER_FILE), json, StandardCharsets.UTF_8);
    }

    private String extract(String json, String field) {
        int i = json.indexOf("\"" + field + "\"");
        if (i < 0) {
            return null;
        }
        int q1 = json.indexOf('"', json.indexOf(':', i) + 1);
        int q2 = json.indexOf('"', q1 + 1);
        return (q1 >= 0 && q2 > q1) ? json.substring(q1 + 1, q2) : null;
    }

    private void release(Path lock) {
        // 仅当 owner.json 是本实例时删除（防止租约过期后误删他人）
        try {
            Path owner = lock.resolve(OWNER_FILE);
            if (Files.isRegularFile(owner, LinkOption.NOFOLLOW_LINKS)) {
                String json = Files.readString(owner, StandardCharsets.UTF_8);
                if (json.contains("\"" + props.getInstanceId() + "\"")) {
                    deleteRecursively(lock);
                    return;
                }
            }
            // owner 缺失或非本实例：不删（等 GC 或接管）
        } catch (IOException e) {
            log.debug("release lock failed: {}", e.getMessage());
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

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR, "Interrupted while waiting for lock");
        }
    }
}
