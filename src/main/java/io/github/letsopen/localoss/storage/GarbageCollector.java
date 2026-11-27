package io.github.letsopen.localoss.storage;

import io.github.letsopen.localoss.config.SystemProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 定期垃圾回收（计划 3.7 节 / R8）：
 *
 * <ul>
 *   <li>清理 .localoss/tmp 中超过 tmpMaxAgeHours 的临时文件</li>
 *   <li>清理过期的锁目录（租约过期 + 20% 余量）</li>
 *   <li>清理未完成 Multipart（超过 incompleteUploadMaxAgeDays）</li>
 * </ul>
 *
 * <p>每小时执行一次 + 随机抖动；gc.lock 保证多实例只有一台执行（R8）。
 */
@Slf4j
@Component
public class GarbageCollector {

    private final SystemProperties props;
    private final KeyValidator keyValidator;
    private final MultipartStore multipartStore;
    private final LockManager lockManager;

    public GarbageCollector(SystemProperties props, KeyValidator keyValidator,
                            MultipartStore multipartStore, LockManager lockManager) {
        this.props = props;
        this.keyValidator = keyValidator;
        this.multipartStore = multipartStore;
        this.lockManager = lockManager;
    }

    /** 每小时一次（fixedDelay 防止堆叠）；多实例安全靠 gc.lock */
    @Scheduled(fixedDelay = 3600_000, initialDelay = 60_000)
    public void scheduledGc() {
        if (!props.isGcEnabled()) {
            return;
        }
        try {
            int removed = runGc();
            if (removed > 0) {
                log.info("GC removed {} entries", removed);
            }
        } catch (Exception e) {
            log.warn("GC cycle failed: {}", e.getMessage());
        }
    }

    /**
     * 执行一次 GC。返回清理条目数。供测试与手动触发。
     */
    public int runGc() {
        if (!props.isGcEnabled()) {
            return 0;
        }
        Path root = Path.of(props.getDataPath());
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        int removed = 0;
        for (String bucket : listBucketDirs(root)) {
            Path bucketDir = root.resolve(bucket);
            // gc.lock：多实例只有一个执行
            try (var lock = lockManager.tryAcquire(bucketDir, "gc")) {
                if (lock == null) {
                    log.debug("GC skipped for {}: another instance holds gc.lock", bucket);
                    continue;
                }
                removed += cleanTmp(bucketDir);
                removed += cleanStaleLocks(bucketDir);
                removed += multipartStore.cleanupExpiredUploads(bucket,
                        props.getIncompleteUploadMaxAgeDays());
            } catch (Exception e) {
                log.debug("GC failed for bucket {}: {}", bucket, e.getMessage());
            }
        }
        return removed;
    }

    private List<String> listBucketDirs(Path root) {
        try (var stream = Files.newDirectoryStream(root)) {
            return new java.util.ArrayList<>(
                    new java.util.ArrayList<>() {
                        {
                            for (Path p : stream) {
                                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
                                        && !p.getFileName().toString().startsWith(".")) {
                                    add(p.getFileName().toString());
                                }
                            }
                        }
                    });
        } catch (IOException e) {
            return List.of();
        }
    }

    private int cleanTmp(Path bucketDir) {
        Path tmp = bucketDir.resolve(".localoss").resolve("tmp");
        if (!Files.isDirectory(tmp, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(Duration.ofHours(props.getTmpMaxAgeHours()));
        int removed = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tmp)) {
            for (Path p : stream) {
                try {
                    Instant modified = Instant.ofEpochMilli(
                            Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toMillis());
                    if (modified.isBefore(cutoff)) {
                        Files.deleteIfExists(p);
                        removed++;
                    }
                } catch (IOException e) {
                    log.debug("GC tmp failed: {}", p, e);
                }
            }
        } catch (IOException e) {
            log.debug("GC tmp scan failed: {}", tmp, e);
        }
        return removed;
    }

    private int cleanStaleLocks(Path bucketDir) {
        Path locks = bucketDir.resolve(".localoss").resolve("locks");
        if (!Files.isDirectory(locks, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        // 读 owner.json 的 expiresAt（租约真实状态）；owner 缺失时用目录 mtime + 宽限期兜底
        int removed = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(locks)) {
            for (Path d : stream) {
                if (!Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (isLockExpired(d)) {
                    deleteRecursively(d);
                    removed++;
                }
            }
        } catch (IOException e) {
            log.debug("GC locks scan failed: {}", locks, e);
        }
        return removed;
    }

    private boolean isLockExpired(Path lockDir) {
        Path owner = lockDir.resolve("owner.json");
        try {
            if (Files.isRegularFile(owner, LinkOption.NOFOLLOW_LINKS)) {
                String json = Files.readString(owner, StandardCharsets.UTF_8);
                int i = json.indexOf("\"expiresAt\"");
                if (i >= 0) {
                    int q1 = json.indexOf('"', json.indexOf(':', i) + 1);
                    int q2 = json.indexOf('"', q1 + 1);
                    if (q1 >= 0 && q2 > q1) {
                        Instant expiresAt = Instant.parse(json.substring(q1 + 1, q2));
                        return Instant.now().isAfter(expiresAt);
                    }
                }
            }
            // owner 缺失：目录 mtime + 租约 * 3 宽限（owner 写入崩溃的场景）
            Instant modified = Instant.ofEpochMilli(
                    Files.getLastModifiedTime(lockDir, LinkOption.NOFOLLOW_LINKS).toMillis());
            return Instant.now().isAfter(
                    modified.plusSeconds(3L * props.getLockLeaseSeconds()));
        } catch (Exception e) {
            // 解析失败按过期处理（保守删除可能活跃的锁有风险，改为保守不删）
            return false;
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
            log.debug("GC delete failed: {}", dir, e);
        }
    }
}
