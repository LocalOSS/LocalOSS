package io.github.letsopen.localoss.config;

/**
 * 全局读写级别（已确认 Q3）。
 *
 * <ul>
 *   <li>{@link #READ_WRITE} — 默认，全部允许</li>
 *   <li>{@link #READ_ONLY} — 只允许 GET/HEAD/List；写/删返回 403 AccessDenied；GC 跳过</li>
 *   <li>{@link #WRITE_ONLY} — 允许写/删/HEAD；拒绝 GET/List</li>
 *   <li>{@link #DISABLED} — 维护模式，全部返回 503 ServiceUnavailable</li>
 * </ul>
 */
public enum AccessMode {
    READ_WRITE,
    READ_ONLY,
    WRITE_ONLY,
    DISABLED;

    /** 是否允许读操作（GET/HEAD/List） */
    public boolean allowRead() {
        return this == READ_WRITE || this == READ_ONLY;
    }

    /** 是否允许写操作（PUT/POST/COPY/Multipart） */
    public boolean allowWrite() {
        return this == READ_WRITE || this == WRITE_ONLY;
    }

    /** 是否允许删除操作（DELETE） */
    public boolean allowDelete() {
        return this == READ_WRITE || this == WRITE_ONLY;
    }

    /** HEAD 单对象/桶：write-only 下也允许，便于客户端确认上传（已确认 Q3） */
    public boolean allowHead() {
        return this != DISABLED;
    }
}
