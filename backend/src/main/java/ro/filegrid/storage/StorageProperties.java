package ro.filegrid.storage;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Connection settings of the three storages ({@code filegrid.storage.*}). */
@ConfigurationProperties("filegrid.storage")
public record StorageProperties(Duration healthInterval, S3 s3, Ftp ftp, Database database) {

    /**
     * @param bucketPrefix  bucket name = prefix + first key segment (for example {@code filegrid-faults})
     * @param healthBucket  bucket probed by the health check
     * @param partSizeBytes multipart part size; also the largest buffer the adapter allocates
     */
    public record S3(URI endpoint, String region, String accessKey, String secretKey,
                     String bucketPrefix, String healthBucket, int partSizeBytes) {
    }

    /**
     * @param poolSize          maximum number of simultaneous FTP connections
     * @param maxResumeAttempts how many times an interrupted upload is resumed before giving up
     */
    public record Ftp(String host, int port, String username, String password, int poolSize,
                      Duration connectTimeout, Duration dataTimeout, int maxResumeAttempts) {
    }

    /**
     * @param maxObjectBytes largest object the Database storage accepts
     * @param readChunkBytes slice size used when reading an object back
     */
    public record Database(long maxObjectBytes, int readChunkBytes) {
    }
}
