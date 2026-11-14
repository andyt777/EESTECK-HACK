package ro.filegrid.storage.s3;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ro.filegrid.storage.Sha256;
import ro.filegrid.storage.StorageAdapter;
import ro.filegrid.storage.StorageException;
import ro.filegrid.storage.StorageHealthResult;
import ro.filegrid.storage.StorageKeys;
import ro.filegrid.storage.StorageObjectNotFoundException;
import ro.filegrid.storage.StorageProperties;
import ro.filegrid.storage.StorageType;
import ro.filegrid.storage.StoredObject;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * S3 storage. Objects up to one part are sent with a single PUT; larger ones
 * use multipart upload. Memory use is one part buffer (16 MB by default) per
 * transfer, whatever the object size.
 */
@Component
public class S3StorageAdapter implements StorageAdapter {

    private static final Logger log = LoggerFactory.getLogger(S3StorageAdapter.class);

    private final S3Client s3;
    private final String bucketPrefix;
    private final String healthBucket;
    private final int partSize;

    public S3StorageAdapter(S3Client s3, StorageProperties properties) {
        this.s3 = s3;
        this.bucketPrefix = properties.s3().bucketPrefix();
        this.healthBucket = properties.s3().healthBucket();
        this.partSize = properties.s3().partSizeBytes();
    }

    @Override
    public StorageType type() {
        return StorageType.S3;
    }

    @Override
    public StoredObject put(String key, InputStream content, long size, Map<String, String> metadata) {
        String bucket = bucketOf(key);
        String objectKey = StorageKeys.pathWithinSegment(key);
        Map<String, String> userMetadata = metadata == null ? Map.of() : metadata;
        MessageDigest digest = Sha256.newDigest();
        DigestInputStream in = new DigestInputStream(content, digest);
        byte[] buffer = new byte[partSize];

        try {
            int first = readFully(in, buffer);
            long written;
            if (first < partSize) {
                // End of content reached inside the first buffer: one plain PUT.
                s3.putObject(b -> b.bucket(bucket).key(objectKey).metadata(userMetadata),
                        body(buffer, first));
                written = first;
            } else {
                written = multipartUpload(bucket, objectKey, userMetadata, in, buffer, first);
            }
            if (size >= 0 && written != size) {
                deleteQuietly(bucket, objectKey);
                throw new StorageException(
                        "S3 wrote " + written + " bytes for " + key + " but " + size + " were expected");
            }
            return new StoredObject(key, written, Sha256.hex(digest));
        } catch (IOException e) {
            throw new StorageException("Cannot read the content for " + key, e);
        } catch (SdkException e) {
            throw new StorageException("S3 write failed for " + key + ": " + e.getMessage(), e);
        }
    }

    private long multipartUpload(String bucket, String objectKey, Map<String, String> metadata,
                                 InputStream in, byte[] buffer, int firstPartLength) throws IOException {
        String uploadId = s3.createMultipartUpload(b -> b.bucket(bucket).key(objectKey).metadata(metadata))
                .uploadId();
        try {
            List<CompletedPart> parts = new ArrayList<>();
            long written = 0;
            int length = firstPartLength;
            int partNumber = 1;
            while (length > 0) {
                int currentPart = partNumber;
                int currentLength = length;
                String eTag = s3.uploadPart(
                        b -> b.bucket(bucket).key(objectKey).uploadId(uploadId).partNumber(currentPart),
                        body(buffer, currentLength)).eTag();
                parts.add(CompletedPart.builder().partNumber(currentPart).eTag(eTag).build());
                written += currentLength;
                partNumber++;
                // A short part means the end of the content was reached.
                length = currentLength < buffer.length ? 0 : readFully(in, buffer);
            }
            s3.completeMultipartUpload(b -> b.bucket(bucket).key(objectKey).uploadId(uploadId)
                    .multipartUpload(m -> m.parts(parts)));
            return written;
        } catch (IOException | RuntimeException e) {
            abortQuietly(bucket, objectKey, uploadId);
            throw e;
        }
    }

    @Override
    public InputStream get(String key) {
        String bucket = bucketOf(key);
        String objectKey = StorageKeys.pathWithinSegment(key);
        try {
            return s3.getObject(b -> b.bucket(bucket).key(objectKey));
        } catch (NoSuchKeyException e) {
            throw new StorageObjectNotFoundException(StorageType.S3, key);
        } catch (SdkException e) {
            throw new StorageException("S3 read failed for " + key + ": " + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(String key) {
        String bucket = bucketOf(key);
        String objectKey = StorageKeys.pathWithinSegment(key);
        try {
            s3.headObject(b -> b.bucket(bucket).key(objectKey));
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw new StorageException("S3 lookup failed for " + key + ": " + e.getMessage(), e);
        } catch (SdkException e) {
            throw new StorageException("S3 lookup failed for " + key + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String key) {
        String bucket = bucketOf(key);
        String objectKey = StorageKeys.pathWithinSegment(key);
        try {
            s3.deleteObject(b -> b.bucket(bucket).key(objectKey));
        } catch (SdkException e) {
            throw new StorageException("S3 delete failed for " + key + ": " + e.getMessage(), e);
        }
    }

    @Override
    public StorageHealthResult health() {
        long start = System.nanoTime();
        try {
            s3.headBucket(b -> b.bucket(healthBucket));
            return StorageHealthResult.up(elapsedMs(start));
        } catch (RuntimeException e) {
            return StorageHealthResult.down(elapsedMs(start), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private String bucketOf(String key) {
        return bucketPrefix + StorageKeys.segmentOf(key);
    }

    private static RequestBody body(byte[] buffer, int length) {
        // Wraps the buffer without copying it.
        return RequestBody.fromInputStream(new ByteArrayInputStream(buffer, 0, length), length);
    }

    /** Fills the buffer unless the stream ends first; returns the number of bytes read. */
    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = in.read(buffer, total, buffer.length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    private void abortQuietly(String bucket, String objectKey, String uploadId) {
        try {
            s3.abortMultipartUpload(b -> b.bucket(bucket).key(objectKey).uploadId(uploadId));
        } catch (RuntimeException e) {
            log.warn("Could not abort multipart upload {} for {}/{}: {}", uploadId, bucket, objectKey, e.getMessage());
        }
    }

    private void deleteQuietly(String bucket, String objectKey) {
        try {
            s3.deleteObject(b -> b.bucket(bucket).key(objectKey));
        } catch (RuntimeException e) {
            log.warn("Could not delete incomplete object {}/{}: {}", bucket, objectKey, e.getMessage());
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
