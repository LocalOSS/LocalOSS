package io.github.letsopen.localoss.util;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;

/**
 * 客户端工具：本地 S3 客户端封装。Phase 1 起改由集成测试直接构造 S3Client；
 * 保留该类便于手工验证与后续脚本复用。
 */
public class S3Util {

    private final String accessKey;
    private final String secretKey;
    private final String endpoint;

    public S3Util(String accessKey, String secretKey, String endpoint) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.endpoint = endpoint;
    }

    private S3Client getClient() {
        return S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .endpointOverride(URI.create(endpoint))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .chunkedEncodingEnabled(false)
                        .build())
                .region(Region.US_EAST_1)
                .build();
    }

    private S3Presigner getPreSigner() {
        return S3Presigner.builder()
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .endpointOverride(URI.create(endpoint))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .chunkedEncodingEnabled(false)
                        .build())
                .region(Region.US_EAST_1)
                .build();
    }

    public void createBucket(String bucketName) {
        try (S3Client s3Client = getClient()) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
        }
    }

    public List<Bucket> getBucketList() {
        try (S3Client s3Client = getClient()) {
            return s3Client.listBuckets(ListBucketsRequest.builder().build()).buckets();
        }
    }

    public boolean headBucket(String bucketName) {
        try (S3Client s3Client = getClient()) {
            try {
                s3Client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
                return true;
            } catch (NoSuchBucketException e) {
                return false;
            }
        }
    }

    public void deleteBucket(String bucketName) {
        try (S3Client s3Client = getClient()) {
            s3Client.deleteBucket(DeleteBucketRequest.builder().bucket(bucketName).build());
        }
    }

    public List<S3Object> getObjectList(String bucketName, String prefix) {
        try (S3Client s3Client = getClient()) {
            ListObjectsRequest request = ListObjectsRequest.builder()
                    .bucket(bucketName).prefix(prefix).delimiter("/").build();
            return s3Client.listObjects(request).contents();
        }
    }

    public HashMap<String, String> headObject(String bucketName, String key) {
        HashMap<String, String> headInfo = new HashMap<>();
        try (S3Client s3Client = getClient()) {
            try {
                HeadObjectResponse objectHead = s3Client.headObject(HeadObjectRequest.builder()
                        .key(key).bucket(bucketName).build());
                headInfo.put("contentType", objectHead.contentType());
                headInfo.put("contentLength", objectHead.contentLength() + "");
                headInfo.put("contentDisposition", objectHead.contentDisposition());
                headInfo.put("lastModified", objectHead.lastModified().toString());
            } catch (NoSuchKeyException e) {
                headInfo.put("noExist", "1");
            }
        }
        return headInfo;
    }

    public void upload(String bucketName, String key, InputStream inputStream) throws Exception {
        try (S3Client s3Client = getClient();
             InputStream in = inputStream) {
            RequestBody requestBody = RequestBody.fromInputStream(in, in.available());
            s3Client.putObject(PutObjectRequest.builder().bucket(bucketName).key(key).build(),
                    requestBody);
        }
    }

    public byte[] getFileByte(String bucketName, String key) {
        try (S3Client s3Client = getClient()) {
            ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucketName).key(key).build());
            return objectBytes.asByteArray();
        }
    }

    public String getDownLoadUrl(String bucketName, String key) {
        try (S3Presigner s3Presigner = getPreSigner()) {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName).key(key).build();
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(5))
                    .getObjectRequest(getObjectRequest)
                    .build();
            PresignedGetObjectRequest signed = s3Presigner.presignGetObject(presignRequest);
            return signed.url().toString();
        }
    }

    public void delete(String bucketName, String key) {
        try (S3Client s3Client = getClient()) {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucketName).key(key).build());
        }
    }

    public void copyObject(String sourceBucketName, String sourceKey,
                           String targetBucketName, String targetKey) {
        try (S3Client s3Client = getClient()) {
            s3Client.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(sourceBucketName)
                    .sourceKey(sourceKey)
                    .destinationBucket(targetBucketName)
                    .destinationKey(targetKey)
                    .build());
        }
    }
}
