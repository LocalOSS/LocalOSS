package io.github.letsopen.localoss.storage;

import io.github.letsopen.localoss.config.SystemProperties;
import io.github.letsopen.localoss.error.S3ErrorCode;
import io.github.letsopen.localoss.error.S3Exception;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 桶磁盘配额（按桶字节上限）。
 *
 * <p>配置：{@code system.quotas.<bucket>=字节数}；未配置的桶不限。
 *
 * <p>实现：增量计数（本地 ConcurrentHashMap）+ 启动时按目录实际用量初始化。
 * 多实例下为近似值（各实例独立计数，通过周期性重算收敛）——GC 时重算。
 */
@Slf4j
@Component
public class QuotaManager {

    private final SystemProperties props;
    private final KeyValidator keyValidator;
    private final Map<String, AtomicLong> usage = new ConcurrentHashMap<>();

    public QuotaManager(SystemProperties props, KeyValidator keyValidator) {
        this.props = props;
        this.keyValidator = keyValidator;
    }

    /** 配置的桶上限字节数；未配置返回 -1 */
    public long quotaOf(String bucket) {
        Map<String, String> quotas = props.getQuotas();
        if (quotas == null) {
            return -1;
        }
        String v = quotas.get(bucket);
        if (v == null || v.isBlank()) {
            return -1;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            // 支持 "10GB" 等后缀
            return parseHuman(v.trim());
        }
    }

    private long parseHuman(String s) {
        String lower = s.toLowerCase();
        long mult = 1;
        if (lower.endsWith("kb")) {
            mult = 1024L;
        } else if (lower.endsWith("mb")) {
            mult = 1024L * 1024;
        } else if (lower.endsWith("gb")) {
            mult = 1024L * 1024 * 1024;
        } else if (lower.endsWith("tb")) {
            mult = 1024L * 1024 * 1024 * 1024;
        } else {
            return -1;
        }
        try {
            return (long) (Double.parseDouble(
                    lower.substring(0, lower.length() - (lower.endsWith("b") && !lower.endsWith("kb")
                            && !lower.endsWith("mb") && !lower.endsWith("gb") && !lower.endsWith("tb")
                            ? 1 : 2))) * mult);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 当前用量（本地计数；未初始化时扫描目录） */
    public long usageOf(String bucket) {
        AtomicLong counter = usage.get(bucket);
        if (counter == null) {
            counter = new AtomicLong(scanUsage(bucket));
            usage.put(bucket, counter);
        }
        return counter.get();
    }

    /** 启动/重算：扫描目录真实用量 */
    private long scanUsage(String bucket) {
        try {
            Path dir = keyValidator.bucketDir(bucket);
            return sizeOf(dir.resolve(".localoss").getParent()) - metaSize(dir);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 目录树总大小（含 .localoss） */
    private long sizeOf(Path dir) {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        long total = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    total += sizeOf(p);
                } else {
                    try {
                        total += Files.size(p);
                    } catch (IOException ignored) {
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return total;
    }

    /** .localoss 元数据大小（不计入配额） */
    private long metaSize(Path bucketDir) {
        return sizeOf(bucketDir.resolve(".localoss"));
    }

    /**
     * 原子配额申请：尝试预留 addBytes；成功返回 true，超限返回 false。
     * check-then-act 竞态由 AtomicLong 原子性消除。
     */
    public boolean tryReserve(String bucket, long addBytes) {
        long quota = quotaOf(bucket);
        if (quota < 0 || addBytes <= 0) {
            return true;
        }
        AtomicLong counter = ensureCounter(bucket);
        // CAS 循环：原子检查 + 预留
        long current;
        long next;
        do {
            current = counter.get();
            next = current + addBytes;
            if (next > quota) {
                return false;
            }
        } while (!counter.compareAndSet(current, next));
        return true;
    }

    /** 写入失败/删除后回滚预留 */
    public void release(String bucket, long bytes) {
        if (bytes <= 0) {
            return;
        }
        AtomicLong counter = usage.get(bucket);
        if (counter != null) {
            long now = counter.addAndGet(-bytes);
            if (now < 0) {
                counter.set(0);
            }
        }
    }

    private AtomicLong ensureCounter(String bucket) {
        AtomicLong counter = usage.get(bucket);
        if (counter == null) {
            long scanned = scanUsage(bucket);
            counter = new AtomicLong(scanned);
            AtomicLong existing = usage.putIfAbsent(bucket, counter);
            if (existing != null) {
                counter = existing;
            }
        }
        return counter;
    }

    /** 删除后扣减 */
    public void onDeleted(String bucket, long bytes) {
        if (bytes <= 0) {
            return;
        }
        AtomicLong counter = usage.get(bucket);
        if (counter != null) {
            long now = counter.addAndGet(-bytes);
            if (now < 0) {
                counter.set(0);
            }
        }
    }

    /** 重算某桶（GC 时调用） */
    public void recalc(String bucket) {
        usage.put(bucket, new AtomicLong(scanUsage(bucket)));
    }
}
