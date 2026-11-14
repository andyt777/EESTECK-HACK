package ro.filegrid.upload;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties for file uploads ({@code filegrid.upload.*}).
 *
 * @param maxSizeBytes maximum permitted size for an upload in bytes (default 5 GB)
 */
@ConfigurationProperties("filegrid.upload")
public record UploadProperties(
        @DefaultValue("5368709120") long maxSizeBytes
) {
    public UploadProperties {
        if (maxSizeBytes <= 0) {
            maxSizeBytes = 5368709120L;
        }
    }
}
