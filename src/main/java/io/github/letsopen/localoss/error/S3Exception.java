package io.github.letsopen.localoss.error;

/**
 * S3 协议异常。由 {@link GlobalExceptionHandler} 转换为标准 Error XML。
 */
public class S3Exception extends RuntimeException {

    private final S3ErrorCode errorCode;
    private final String detailMessage;

    public S3Exception(S3ErrorCode errorCode) {
        super(errorCode.getCode());
        this.errorCode = errorCode;
        this.detailMessage = errorCode.getMessage();
    }

    public S3Exception(S3ErrorCode errorCode, String detailMessage) {
        super(errorCode.getCode());
        this.errorCode = errorCode;
        this.detailMessage = detailMessage;
    }

    public S3Exception(S3ErrorCode errorCode, String detailMessage, Throwable cause) {
        super(errorCode.getCode(), cause);
        this.errorCode = errorCode;
        this.detailMessage = detailMessage;
    }

    public S3ErrorCode getErrorCode() {
        return errorCode;
    }

    public String getDetailMessage() {
        return detailMessage;
    }
}
