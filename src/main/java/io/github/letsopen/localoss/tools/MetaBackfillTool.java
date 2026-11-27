package io.github.letsopen.localoss.tools;

import io.github.letsopen.localoss.config.SystemProperties;
import io.github.letsopen.localoss.model.ObjectMetadata;
import io.github.letsopen.localoss.storage.KeyValidator;
import io.github.letsopen.localoss.storage.MetadataStore;
import io.github.letsopen.localoss.storage.StorageEngine;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 元数据回填工具（计划 9.1 节，R4）。
 *
 * <p>为存量文件（没有 .localoss/meta 的对象）批量生成元数据。支持 --dry-run / --bucket / 并发度。
 *
 * <p>用法（ApplicationRunner 模式）：
 * <pre>java -jar local-oss.jar --localoss.backfill-meta --localoss.dry-run=true</pre>
 */
@Slf4j
public final class MetaBackfillTool {

    private MetaBackfillTool() {
    }

    public static Result run(SystemProperties props, String onlyBucket,
                             boolean dryRun, int concurrency) throws InterruptedException {
        KeyValidator kv = new KeyValidator(props);
        MetadataStore ms = new MetadataStore(props);
        StorageEngine engine = new StorageEngine(props, kv, ms,
                new io.github.letsopen.localoss.storage.LockManager(props),
                new io.github.letsopen.localoss.storage.QuotaManager(props, kv));

        Path root = Path.of(props.getDataPath());
        List<Path> bucketDirs = new ArrayList<>();
        if (onlyBucket != null && !onlyBucket.isEmpty()) {
            Path b = root.resolve(onlyBucket);
            if (Files.isDirectory(b, LinkOption.NOFOLLOW_LINKS)) {
                bucketDirs.add(b);
            }
        } else {
            try (DirectoryStream<Path> s = Files.newDirectoryStream(root)) {
                for (Path p : s) {
                    if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
                            && !p.getFileName().toString().startsWith(".")) {
                        bucketDirs.add(p);
                    }
                }
            } catch (IOException e) {
                log.warn("Cannot list data path {}: {}", root, e.getMessage());
            }
        }

        AtomicInteger processed = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, concurrency));
        for (Path bucketDir : bucketDirs) {
            String bucket = bucketDir.getFileName().toString();
            ms.ensureBucketConfig(bucketDir, bucket);
            collectKeys(bucketDir, "", keys -> {
                processed.incrementAndGet();
                if (dryRun) {
                    return;
                }
                pool.submit(() -> {
                    try {
                        Path target = kv.toFilePath(bucket, keys);
                        if (ms.readMeta(bucketDir, keys) == null) {
                            engine.getMeta(bucket, keys, target); // 触发即时生成 + 回填
                        } else {
                            skipped.incrementAndGet();
                        }
                    } catch (Exception e) {
                        log.warn("Backfill failed for {}/{}: {}", bucket, keys, e.getMessage());
                    }
                });
            });
        }
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.MINUTES);
        return new Result(processed.get(), skipped.get(), dryRun);
    }

    private interface KeyVisitor {
        void accept(String key);
    }

    /** 递归收集桶内对象 key（与 StorageEngine.listAllKeys 相同规则） */
    private static void collectKeys(Path dir, String rel, KeyVisitor visitor) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                if (name.equals(".localoss") || name.startsWith(".")) {
                    continue;
                }
                String childRel = rel.isEmpty() ? name : rel + "/" + name;
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    collectKeys(p, childRel, visitor);
                } else {
                    visitor.accept(childRel);
                }
            }
        } catch (IOException e) {
            log.warn("Scan failed: {}", dir, e);
        }
    }

    public record Result(int processed, int skipped, boolean dryRun) {
    }
}
