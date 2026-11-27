package io.github.letsopen.localoss.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.UUID;
import lombok.Data;

/**
 * 全局系统配置。
 *
 * <p>读写级别 {@link AccessMode} 控制全局 read/write 行为（已确认 Q3）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "system")
public class SystemProperties {

    /** 数据根目录，一级目录 = Bucket */
    private String dataPath = "/opt/file/data/";

    /** 访问密钥（主凭证，向后兼容单组配置） */
    private String accessKey = "";

    /** 密钥 */
    private String secretKey = "";

    /** 多组凭证（单租户内多账号；每组可限定 level） */
    private java.util.List<io.github.letsopen.localoss.auth.AccessCredential> credentials =
            new java.util.ArrayList<>();

    /** S3 region（默认 us-east-1） */
    private String region = "us-east-1";

    /** 全局读写级别 */
    private AccessMode accessMode = AccessMode.READ_WRITE;

    /** 多实例唯一标识，用于临时文件/锁/uploadId 前缀 */
    private String instanceId = defaultInstanceId();

    /** 是否允许匿名只读（GET/HEAD/List） */
    private boolean allowAnonymousRead = false;

    /** 管理员 force 删除非空桶（已确认 R9） */
    private boolean forceDeleteBucket = false;

    /** 时钟偏差容忍秒数（SigV4） */
    private int clockSkewSeconds = 900;

    /** 预签名 URL 最大有效期秒数（S3 限 7 天） */
    private int presignMaxExpiresSeconds = 604800;

    /** 最大对象字节数（默认 5TiB） */
    private long maxObjectSize = 5L * 1024 * 1024 * 1024 * 1024;

    /** 最大单次 PUT 字节数（默认 5GiB） */
    private long maxSinglePutSize = 5L * 1024 * 1024 * 1024;

    /** 最小分片字节数（S3 要求除最后一片外 >= 5MiB） */
    private long minPartSize = 5L * 1024 * 1024;

    /** 最大分片字节数（S3 上限 5GiB） */
    private long maxPartSize = 5L * 1024 * 1024 * 1024;

    /** 最大分片数（S3 上限 10000） */
    private int maxParts = 10000;

    /** 是否 fsync（NFS 上可关闭换性能） */
    private boolean fsync = true;

    /** 临时文件最大保留时长（小时） */
    private int tmpMaxAgeHours = 24;

    /** GC 是否启用 */
    private boolean gcEnabled = true;

    /** 未完成 Multipart 最大保留天数 */
    private int incompleteUploadMaxAgeDays = 7;

    /** 锁租约秒数 */
    private int lockLeaseSeconds = 30;

    /** 桶磁盘配额（bucket -> 字节数或 10GB 形式）；未配置的桶不限 */
    private java.util.Map<String, String> quotas = new java.util.HashMap<>();

    private static String defaultInstanceId() {
        String host = System.getenv().getOrDefault("HOSTNAME", "local");
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 共享 ObjectMapper（桶配置 JSON 存取） */
    public com.fasterxml.jackson.databind.ObjectMapper objectMapperForConfig() {
        return new com.fasterxml.jackson.databind.ObjectMapper();
    }
}
