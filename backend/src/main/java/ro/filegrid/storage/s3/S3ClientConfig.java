package ro.filegrid.storage.s3;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ro.filegrid.storage.StorageProperties;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class S3ClientConfig {

    @Bean(destroyMethod = "close")
    public S3Client s3Client(StorageProperties properties) {
        return create(properties.s3());
    }

    /**
     * Builds a client for an S3-compatible endpoint. Only standard S3 calls are
     * used, so the provider can be replaced through configuration alone.
     */
    public static S3Client create(StorageProperties.S3 s3) {
        return S3Client.builder()
                .endpointOverride(s3.endpoint())
                .region(Region.of(s3.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(s3.accessKey(), s3.secretKey())))
                // S3-compatible servers are addressed as http://host/bucket/key.
                .forcePathStyle(true)
                // FileGrid computes and verifies SHA-256 itself; the SDK's optional
                // extra checksums are not supported by every S3-compatible server.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
    }
}
