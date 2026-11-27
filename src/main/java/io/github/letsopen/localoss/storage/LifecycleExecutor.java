package io.github.letsopen.localoss.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.letsopen.localoss.config.SystemProperties;
import io.github.letsopen.localoss.error.S3ErrorCode;
import io.github.letsopen.localoss.error.S3Exception;
import io.github.letsopen.localoss.util.KeyHash;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lifecycle 执行器（有限子集，计划 Phase 5 确认）：
 *
 * <ul>
 *   <li>Expiration：按天数（Days）或指定日期（Date）删除对象——**不支持**按版本/tag 过滤</li>
 *   <li>AbortIncompleteMultipartUpload：DaysAfterInitiation 后清理未完成分片</li>
 * </ul>
 *
 * <p>规则来自 {@code bucket.json} 的 {@code lifecycle} 字段：
 * <pre>[{"id":"rule1","prefix":"logs/","expirationDays":30}, ...]</pre>
 *
 * <p>调度：每小时（复用 GC 节奏），gc.lock 单实例执行（R8）。
 */
@Slf4j
@Component
public class LifecycleExecutor {

    private final SystemProperties props;
    private final KeyValidator keyValidator;
    private final StorageEngine storage;
    private final MetadataStore metadataStore;
    private final MultipartStore multipartStore;
    private final LockManager lockManager;
    private final ObjectMapper mapper = new ObjectMapper();

    public LifecycleExecutor(SystemProperties props, KeyValidator keyValidator,
                             StorageEngine storage, MetadataStore metadataStore,
                             MultipartStore multipartStore, LockManager lockManager) {
        this.props = props;
        this.keyValidator = keyValidator;
        this.storage = storage;
        this.metadataStore = metadataStore;
        this.multipartStore = multipartStore;
        this.lockManager = lockManager;
    }

    /** 每小时执行（initialDelay 与 GC 错开） */
    @Scheduled(fixedDelay = 3600_000, initialDelay = 300_000)
    public void scheduledRun() {
        if (!props.isGcEnabled() || props.getAccessMode() == io.github.letsopen.localoss.config.AccessMode.READ_ONLY) {
            return;
        }
        try {
            int deleted = run();
            if (deleted > 0) {
                log.info("Lifecycle deleted {} objects/uploads", deleted);
            }
        } catch (Exception e) {
            log.warn("Lifecycle cycle failed: {}", e.getMessage());
        }
    }

    /** 执行一轮；返回删除数。供测试与手动触发。 */
    public int run() {
        Path root = Path.of(props.getDataPath());
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        int deleted = 0;
        for (String bucket : storage.listBuckets()) {
            Path bucketDir = keyValidator.bucketDir(bucket);
            try (var lock = lockManager.tryAcquire(bucketDir, "lifecycle")) {
                if (lock == null) {
                    continue; // 其他实例在跑
                }
                List<Map<String, Object>> rules = readRules(bucketDir);
                if (rules.isEmpty()) {
                    continue;
                }
                deleted += applyExpiration(bucket, bucketDir, rules);
                deleted += applyMpuAbort(bucket, bucketDir, rules);
            } catch (Exception e) {
                log.debug("Lifecycle for {}: {}", bucket, e.getMessage());
            }
        }
        return deleted;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readRules(Path bucketDir) {
        Map<String, Object> cfg = metadataStore.ensureBucketConfig(bucketDir,
                bucketDir.getFileName().toString());
        Object rules = cfg.get("lifecycle");
        if (!(rules instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    private int applyExpiration(String bucket, Path bucketDir,
                                List<Map<String, Object>> rules) {
        int deleted = 0;
        for (Map<String, Object> rule : rules) {
            Integer days = intOf(rule.get("expirationDays"));
            String date = strOf(rule.get("expirationDate"));
            if (days == null && date == null) {
                continue;
            }
            Instant cutoff;
            if (days != null) {
                cutoff = Instant.now().minus(Duration.ofDays(days));
            } else {
                try {
                    cutoff = Instant.parse(date);
                    // Date 形式：到期时刻未到则不删除
                    if (Instant.now().isBefore(cutoff)) {
                        continue;
                    }
                } catch (Exception e) {
                    log.warn("Invalid lifecycle expirationDate: {}", date);
                    continue;
                }
            }
            String prefix = strOf(rule.get("prefix"));
            if (prefix == null) {
                prefix = "";
            }
            for (String key : storage.listAllKeys(bucket, prefix)) {
                if (key.endsWith("/")) {
                    continue; // 目录型对象不过期
                }
                Path target = keyValidator.toFilePath(bucket, key);
                try {
                    Instant lastModified = Instant.ofEpochMilli(
                            Files.getLastModifiedTime(target, LinkOption.NOFOLLOW_LINKS).toMillis());
                    if (lastModified.isBefore(cutoff)) {
                        storage.deleteObject(bucket, key);
                        deleted++;
                    }
                } catch (IOException e) {
                    log.debug("Lifecycle stat failed: {}", key);
                }
            }
        }
        return deleted;
    }

    private int applyMpuAbort(String bucket, Path bucketDir,
                              List<Map<String, Object>> rules) {
        int aborted = 0;
        for (Map<String, Object> rule : rules) {
            Integer days = intOf(rule.get("abortIncompleteMpuDays"));
            if (days == null) {
                continue;
            }
            String prefix = strOf(rule.get("prefix"));
            for (var upload : multipartStore.listUploads(bucket)) {
                if (prefix != null && !upload.key().startsWith(prefix)) {
                    continue;
                }
                Duration age = Duration.between(upload.initiated(), Instant.now());
                if (age.toDays() >= days) {
                    multipartStore.abort(bucket, upload.key(), upload.uploadId());
                    aborted++;
                }
            }
        }
        return aborted;
    }

    private static Integer intOf(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String strOf(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
