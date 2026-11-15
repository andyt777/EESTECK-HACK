package ro.filegrid.testsupport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Standalone Tus 1.0 protocol test client using Java 21's native {@link HttpClient}.
 * Provides direct control over POST creation, PATCH chunk streaming, HEAD offset queries,
 * and DELETE cancellation, including raw response access for negative/boundary testing.
 */
public class TusTestClient {

    public static final String OMIT_HEADER = "__OMIT_HEADER__";

    private final String baseUrl;
    private final HttpClient http;

    public TusTestClient(String baseUrl) {
        this(baseUrl, HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    public TusTestClient(String baseUrl, HttpClient http) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = http;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public HttpClient getHttpClient() {
        return http;
    }

    /**
     * Applies default headers and merges custom headers:
     * - Replaces existing default headers instead of appending duplicates.
     * - If customHeaders maps a key to null, empty string, or OMIT_HEADER, omits that header entirely.
     */
    private static void applyHeaders(HttpRequest.Builder builder, Map<String, String> defaults,
                                     Map<String, String> customHeaders) {
        Map<String, String> merged = new LinkedHashMap<>(defaults);
        if (customHeaders != null) {
            for (Map.Entry<String, String> entry : customHeaders.entrySet()) {
                String key = entry.getKey();
                String val = entry.getValue();
                if (val == null || val.isBlank() || OMIT_HEADER.equals(val)) {
                    merged.remove(key);
                } else {
                    merged.put(key, val);
                }
            }
        }
        merged.forEach(builder::setHeader);
    }

    private static void verifyTusResumableHeader(HttpResponse<?> response) {
        String version = response.headers().firstValue("Tus-Resumable")
                .orElseThrow(() -> new AssertionError("Missing required 'Tus-Resumable' response header"));
        if (!"1.0.0".equals(version)) {
            throw new AssertionError("Expected 'Tus-Resumable: 1.0.0' response header but got: " + version);
        }
    }

    /**
     * Sends OPTIONS to /uploads endpoint to discover server Tus capabilities.
     */
    public HttpResponse<String> sendOptions() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder().header("Authorization", TestTokens.bearerAdmin())
                .uri(URI.create(resolveEndpoint("/uploads")))
                .header("Tus-Resumable", "1.0.0")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Sends raw POST to create an upload resource, returning full HTTP response.
     */
    public HttpResponse<String> sendCreateUpload(Long uploadLength, Map<String, String> metadata,
                                                Map<String, String> customHeaders)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder().header("Authorization", TestTokens.bearerAdmin())
                .uri(URI.create(resolveEndpoint("/uploads")));

        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("Tus-Resumable", "1.0.0");
        if (uploadLength != null) {
            defaults.put("Upload-Length", String.valueOf(uploadLength));
        }
        if (metadata != null && !metadata.isEmpty()) {
            defaults.put("Upload-Metadata", encodeMetadata(metadata));
        }

        applyHeaders(builder, defaults, customHeaders);

        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.noBody()).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Creates an upload resource expecting HTTP 201 Created and returns the Location URI.
     */
    public URI createUpload(long totalBytes, Map<String, String> metadata) throws IOException, InterruptedException {
        HttpResponse<String> response = sendCreateUpload(totalBytes, metadata, Collections.emptyMap());
        if (response.statusCode() != 201) {
            throw new AssertionError("Expected HTTP 201 Created but got " + response.statusCode()
                    + ": " + response.body());
        }
        verifyTusResumableHeader(response);
        String location = response.headers().firstValue("Location")
                .orElseThrow(() -> new AssertionError("Missing Location header in 201 Created response"));
        return resolveLocationUri(location);
    }

    /**
     * Sends HEAD request to check current upload state and offset.
     */
    public HttpResponse<Void> sendHead(URI uploadUri, Map<String, String> customHeaders)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder().header("Authorization", TestTokens.bearerAdmin())
                .uri(uploadUri);

        applyHeaders(builder, Map.of("Tus-Resumable", "1.0.0"), customHeaders);

        HttpRequest request = builder.method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
        return http.send(request, HttpResponse.BodyHandlers.discarding());
    }

    /**
     * Queries the current upload offset via HEAD request.
     */
    public long getOffset(URI uploadUri) throws IOException, InterruptedException {
        HttpResponse<Void> response = sendHead(uploadUri, Collections.emptyMap());
        // tus 1.0: a HEAD answer is "200 OK or 204 No Content".
        if (response.statusCode() != 200 && response.statusCode() != 204) {
            throw new AssertionError("Expected HTTP 200/204 from HEAD but got " + response.statusCode());
        }
        verifyTusResumableHeader(response);
        return Long.parseLong(response.headers().firstValue("Upload-Offset")
                .orElseThrow(() -> new AssertionError("Missing Upload-Offset header in HEAD response")));
    }

    /**
     * Sends a PATCH chunk streaming an InputStream directly.
     */
    public HttpResponse<String> sendPatch(URI uploadUri, InputStream chunkStream, long offset,
                                          String contentType, Map<String, String> customHeaders)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder().header("Authorization", TestTokens.bearerAdmin())
                .uri(uploadUri);

        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("Tus-Resumable", "1.0.0");
        defaults.put("Upload-Offset", String.valueOf(offset));
        defaults.put("Content-Type", contentType != null ? contentType : "application/offset+octet-stream");

        applyHeaders(builder, defaults, customHeaders);

        HttpRequest request = builder.method("PATCH", HttpRequest.BodyPublishers.ofInputStream(() -> chunkStream)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Uploads a chunk via InputStream expecting HTTP 204 No Content, returning new Upload-Offset.
     */
    public long uploadChunk(URI uploadUri, InputStream chunkStream, long offset)
            throws IOException, InterruptedException {
        HttpResponse<String> response = sendPatch(uploadUri, chunkStream, offset,
                "application/offset+octet-stream", Collections.emptyMap());
        if (response.statusCode() != 204) {
            throw new AssertionError("Expected HTTP 204 No Content from PATCH but got " + response.statusCode()
                    + ": " + response.body());
        }
        verifyTusResumableHeader(response);
        return Long.parseLong(response.headers().firstValue("Upload-Offset")
                .orElseThrow(() -> new AssertionError("Missing Upload-Offset header in PATCH response")));
    }

    /**
     * Convenience method to upload byte array chunk expecting HTTP 204 No Content.
     */
    public long uploadBytes(URI uploadUri, byte[] data, long offset) throws IOException, InterruptedException {
        return uploadChunk(uploadUri, new ByteArrayInputStream(data), offset);
    }

    /**
     * Sends DELETE request to cancel an upload and purge staging data.
     */
    public HttpResponse<String> sendDelete(URI uploadUri, Map<String, String> customHeaders)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder().header("Authorization", TestTokens.bearerAdmin())
                .uri(uploadUri);

        applyHeaders(builder, Map.of("Tus-Resumable", "1.0.0"), customHeaders);

        HttpRequest request = builder.DELETE().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Cancels an upload expecting HTTP 204 No Content.
     */
    public void deleteUpload(URI uploadUri) throws IOException, InterruptedException {
        HttpResponse<String> response = sendDelete(uploadUri, Collections.emptyMap());
        if (response.statusCode() != 204 && response.statusCode() != 200) {
            throw new AssertionError("Expected HTTP 204 or 200 from DELETE but got " + response.statusCode());
        }
        verifyTusResumableHeader(response);
    }

    /**
     * Encodes a metadata key-value map according to Tus 1.0 specification (RFC comma-delimited key base64(value)).
     */
    public static String encodeMetadata(Map<String, String> metadata) {
        return metadata.entrySet().stream()
                .map(e -> {
                    String key = e.getKey();
                    String val = e.getValue() != null ? e.getValue() : "";
                    String base64Val = Base64.getEncoder().encodeToString(val.getBytes(StandardCharsets.UTF_8));
                    return key + " " + base64Val;
                })
                .collect(Collectors.joining(","));
    }

    private String resolveEndpoint(String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        String cleanPath = path.startsWith("/") ? path : "/" + path;
        return baseUrl + cleanPath;
    }

    private URI resolveLocationUri(String location) {
        if (location.startsWith("http://") || location.startsWith("https://")) {
            return URI.create(location);
        }
        URI base = URI.create(baseUrl);
        String hostAndPort = base.getScheme() + "://" + base.getAuthority();
        String basePath = base.getPath();
        if (basePath.endsWith("/")) {
            basePath = basePath.substring(0, basePath.length() - 1);
        }
        String cleanLoc = location.startsWith("/") ? location : "/" + location;
        if (!basePath.isEmpty() && cleanLoc.startsWith(basePath + "/")) {
            return URI.create(hostAndPort + cleanLoc);
        }
        return URI.create(baseUrl + cleanLoc);
    }
}
