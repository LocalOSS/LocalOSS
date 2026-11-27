package io.github.letsopen.localoss.error;

/**
 * S3 标准错误码。
 *
 * <p>来源：AWS S3 API Reference ErrorResponses。HTTP 状态码与 S3 官方一致。
 */
public enum S3ErrorCode {

    ACCESS_DENIED("AccessDenied", 403, "Access Denied"),
    SIGNATURE_DOES_NOT_MATCH("SignatureDoesNotMatch", 403,
            "The request signature we calculated does not match the signature you provided. "
                    + "Check your key and signing method."),
    INVALID_ACCESS_KEY_ID("InvalidAccessKeyId", 403,
            "The AWS Access Key Id you provided does not exist in our records."),
    REQUEST_TIME_TOO_SKEWED("RequestTimeTooSkewed", 403,
            "The difference between the request time and the server's time is too large."),
    NO_SUCH_BUCKET("NoSuchBucket", 404, "The specified bucket does not exist"),
    NO_SUCH_KEY("NoSuchKey", 404, "The specified key does not exist."),
    NO_SUCH_UPLOAD("NoSuchUpload", 404,
            "The specified multipart upload does not exist. The upload ID might be invalid, "
                    + "or the multipart upload might have been aborted or completed."),
    BUCKET_NOT_EMPTY("BucketNotEmpty", 409, "The bucket you tried to delete is not empty"),
    BUCKET_ALREADY_OWNED_BY_YOU("BucketAlreadyOwnedByYou", 409,
            "Your previous request to create the named bucket succeeded and you already own it."),
    KEY_CONFLICT("KeyConflict", 409,
            "The requested key conflicts with an existing object path in this bucket."),
    PRECONDITION_FAILED("PreconditionFailed", 412,
            "At least one of the pre-conditions you specified did not hold"),
    CONDITIONAL_REQUEST_CONFLICT("ConditionalRequestConflict", 409,
            "The conditional request failed because of a conflicting concurrent operation. Retry the request."),
    INVALID_RANGE("InvalidRange", 416, "The requested range is not satisfiable"),
    INVALID_ARGUMENT("InvalidArgument", 400, "Invalid Argument"),
    INVALID_BUCKET_NAME("InvalidBucketName", 400, "The specified bucket is not valid."),
    INVALID_DIGEST("BadDigest", 400,
            "The Content-MD5 or checksum value that you specified did not match what the server received."),
    MISSING_CONTENT_LENGTH("MissingContentLength", 411,
            "You must provide the Content-Length HTTP header."),
    MALFORMED_XML("MalformedXML", 400,
            "The XML you provided was not well-formed or did not validate against our published schema"),
    INVALID_PART("InvalidPart", 400,
            "One or more of the specified parts could not be found. The part might not have been uploaded, "
                    + "or the specified entity tag might not match the part's entity tag."),
    INVALID_PART_ORDER("InvalidPartOrder", 400,
            "The list of parts was not in ascending order. Parts must be ordered by part number."),
    ENTITY_TOO_LARGE("EntityTooLarge", 400, "Your proposed upload exceeds the maximum allowed size"),
    ENTITY_TOO_SMALL("EntityTooSmall", 400,
            "Your proposed upload is smaller than the minimum allowed size"),
    TOO_MANY_PARTS("TooManyParts", 400,
            "You have attempted to add more parts than the maximum of 10000 that are allowed for this object."),
    NOT_IMPLEMENTED("NotImplemented", 501,
            "A header or query you provided implies functionality that is not implemented."),
    INVALID_TAG("InvalidTag", 400, "The tag provided was not a valid tag."),
    INVALID_REQUEST("InvalidRequest", 400,
            "The request is not valid."),
    INCOMPLETE_BODY("IncompleteBody", 400,
            "You did not provide the number of bytes specified by the Content-Length HTTP header."),
    METHOD_NOT_ALLOWED("MethodNotAllowed", 405,
            "The specified method is not allowed against this resource."),
    UNSUPPORTED_MEDIA_TYPE("UnsupportedContentType", 415,
            "The specified media type is not supported."),
    NO_SUCH_KEY_EXISTS("NoSuchKey", 404, "The specified key does not exist."),
    MALFORMED_POLICY("MalformedPolicy", 400,
            "Policies must be valid JSON and the first byte must be '{' or ' '."),
    INTERNAL_ERROR("InternalError", 500,
            "We encountered an internal error. Please try again."),
    SERVICE_UNAVAILABLE("ServiceUnavailable", 503, "Service is unavailable. Please retry.");

    private final String code;
    private final int httpStatus;
    private final String message;

    S3ErrorCode(String code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getMessage() {
        return message;
    }
}
