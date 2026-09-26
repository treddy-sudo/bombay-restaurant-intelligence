package com.bombay.restaurantintelligence.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "app.storage.mode", havingValue = "s3")
public class S3CompatibleDocumentStorageService implements DocumentStorageService {
    private final S3Client client;
    private final String bucket;

    public S3CompatibleDocumentStorageService(@Value("${app.storage.s3.endpoint:}") String endpoint,
                                              @Value("${app.storage.s3.region:ap-south-1}") String region,
                                              @Value("${app.storage.s3.bucket}") String bucket,
                                              @Value("${app.storage.s3.access-key}") String accessKey,
                                              @Value("${app.storage.s3.secret-key}") String secretKey) {
        var builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(true);
        if (endpoint != null && !endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        this.client = builder.build();
        this.bucket = bucket;
    }

    @Override
    public String store(String filename, String contentType, byte[] content) {
        String safe = (filename == null ? "document" : filename).replaceAll("[^a-zA-Z0-9._-]", "_");
        String key = "documents/" + UUID.randomUUID() + "-" + safe;
        String type = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType.trim();
        client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).contentType(type).build(),
                RequestBody.fromBytes(content));
        return "s3://" + bucket + "/" + key;
    }

    @Override
    public byte[] read(String location) {
        String prefix = "s3://" + bucket + "/";
        if (!location.startsWith(prefix)) throw new IllegalArgumentException("Storage location is not in configured bucket");
        String key = location.substring(prefix.length());
        return client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
    }

    @Override
    public void verifyAvailable() {
        client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
    }
}
