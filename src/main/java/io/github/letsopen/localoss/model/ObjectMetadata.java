package io.github.letsopen.localoss.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

/**
 * 对象元数据（持久化到 .localoss/meta/&lt;sha256(key)&gt;.json）。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ObjectMetadata {

    /** S3 key（明文，用于回显与排序） */
    private String key;

    private long size;

    /** 带双引号的 ETag，如 "9b2cf535..." */
    private String etag;

    /** checksum Base64 值，键为算法名：CRC32/CRC32C/SHA1/SHA256 */
    private Map<String, String> checksums;

    /** FULL_OBJECT（Phase 1 固定） */
    private String checksumType = "FULL_OBJECT";

    private String contentType;

    private String contentEncoding;

    private String contentLanguage;

    private String contentDisposition;

    private String cacheControl;

    private String expires;

    /** x-amz-meta-* 用户元数据 */
    private Map<String, String> userMetadata;

    /** 对象标签（Phase 4 使用） */
    private Map<String, String> tags;

    /** 默认 STANDARD */
    private String storageClass = "STANDARD";

    /** ISO-8601 UTC */
    private String lastModified;

    private Map<String, String> owner;

    /** multipart 分片数（仅 multipart 对象） */
    private Integer parts;

    /** canned ACL（private/public-read；null = private） */
    private String acl;
}
