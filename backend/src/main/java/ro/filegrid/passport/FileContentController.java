package ro.filegrid.passport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import ro.filegrid.events.FileLifecycle;
import ro.filegrid.file.FileEventType;
import ro.filegrid.file.FileRecord;
import ro.filegrid.file.IntegrityStatus;
import ro.filegrid.file.Sensitivity;
import ro.filegrid.security.AccessPolicy;
import ro.filegrid.security.CurrentActorProvider;
import ro.filegrid.storage.Sha256;
import ro.filegrid.storage.StorageException;
import ro.filegrid.text.Texts;

/**
 * Retrieval with integrity checking: {@code GET /api/files/{id}/content} and
 * {@code POST /api/files/{id}/verify}. A file is never handed over as valid
 * when its SHA-256 no longer matches.
 */
@RestController
@Profile("api")
public class FileContentController {

    public static final String PERMISSION_READ = "file:read";
    public static final String PERMISSION_PII = "pii:read";
    public static final String PERMISSION_SUSPECT = "file:read-suspect";
    public static final String PERMISSION_VERIFY = "file:verify";

    private static final Logger log = LoggerFactory.getLogger(FileContentController.class);
    private static final int BUFFER = 64 * 1024;

    private final FileContentService content;
    private final FileLifecycle lifecycle;
    private final CurrentActorProvider actors;
    private final AccessPolicy access;
    private final Texts texts;
    private final long verifyFirstMaxBytes;
    private final Path downloadDir;

    public FileContentController(FileContentService content, FileLifecycle lifecycle, CurrentActorProvider actors,
                                 AccessPolicy access, Texts texts,
                                 @Value("${filegrid.retrieval.verify-before-serve-max-bytes:209715200}")
                                 long verifyFirstMaxBytes,
                                 @Value("${filegrid.staging.directory}") String stagingDirectory) {
        this.content = content;
        this.lifecycle = lifecycle;
        this.actors = actors;
        this.access = access;
        this.texts = texts;
        this.verifyFirstMaxBytes = verifyFirstMaxBytes;
        this.downloadDir = Path.of(stagingDirectory, "downloads");
    }

    @PostMapping("/files/{id}/verify")
    public FileContentService.VerifyResult verify(@PathVariable UUID id) {
        String actor = actors.currentActor();
        require(actor, PERMISSION_VERIFY);
        return content.verify(id, actor, "ON_DEMAND");
    }

    @GetMapping("/files/{id}/content")
    public ResponseEntity<StreamingResponseBody> content(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean acknowledgeSuspect,
            @RequestParam(defaultValue = "attachment") String disposition,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) throws IOException {
        String actor = actors.currentActor();
        require(actor, PERMISSION_READ);
        FileRecord file = content.require(id);
        if (file.getSensitivity() == Sensitivity.PERSONAL_DATA) {
            require(actor, PERMISSION_PII);
        }
        content.requireStored(file);
        boolean suspect = file.getIntegrityStatus() == IntegrityStatus.MISMATCH;
        if (suspect && !(acknowledgeSuspect && access.isAllowed(actor, PERMISSION_SUSPECT))) {
            throw new ProblemException(HttpStatus.CONFLICT, "integrity-mismatch", texts.get("file.error.suspect"));
        }
        boolean inline = "inline".equalsIgnoreCase(disposition);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType(file));
        headers.setContentDisposition((inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(file.getOriginalName(), StandardCharsets.UTF_8).build());
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.set("X-Content-SHA256", file.getSha256Plain() != null ? file.getSha256Plain()
                : content.expectedHash(file));
        headers.setCacheControl("no-store");
        FileEventType eventType = inline ? FileEventType.ACCESSED : FileEventType.DOWNLOADED;

        long size = file.getSizeBytes();
        long[] bounds = parseRange(range, size);
        if (bounds != null) {
            return partial(file, bounds, headers, actor, eventType);
        }
        if (suspect) {
            // Explicitly acknowledged by an administrator: served as is, labelled as suspect.
            headers.set("X-Integrity", "suspect");
            headers.setContentLength(size);
            audit(file, eventType, actor, "suspect-acknowledged");
            return ResponseEntity.ok().headers(headers).body(out -> copyAll(file, out));
        }
        if (size <= verifyFirstMaxBytes) {
            return verifiedFirst(file, headers, actor, eventType);
        }
        headers.set("X-Integrity", "verified-while-streaming");
        headers.setContentLength(size);
        audit(file, eventType, actor, "streamed");
        return ResponseEntity.ok().headers(headers).body(out -> streamVerifying(file, out, actor));
    }

    /** Small and medium files: copied to a temporary file and checked before the first byte is sent. */
    private ResponseEntity<StreamingResponseBody> verifiedFirst(FileRecord file, HttpHeaders headers, String actor,
                                                                FileEventType eventType) throws IOException {
        Files.createDirectories(downloadDir);
        Path temp = Files.createTempFile(downloadDir, file.getId().toString(), ".download");
        String actual;
        try (InputStream in = content.open(file); OutputStream out = Files.newOutputStream(temp)) {
            MessageDigest digest = Sha256.newDigest();
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
            actual = Sha256.hex(digest);
        } catch (IOException | StorageException e) {
            Files.deleteIfExists(temp);
            throw new ProblemException(HttpStatus.SERVICE_UNAVAILABLE, "storage-unavailable",
                    texts.get("file.error.storageUnavailable", lifecycle.storageName(file.getStorageType())));
        }
        FileContentService.VerifyResult result = content.record(file, actual, actor, "RETRIEVAL");
        if (!result.intact()) {
            Files.deleteIfExists(temp);
            throw new ProblemException(HttpStatus.CONFLICT, "integrity-mismatch", texts.get("file.error.suspect"));
        }
        headers.set("X-Integrity", "verified");
        headers.setContentLength(Files.size(temp));
        audit(file, eventType, actor, "verified-before-serving");
        return ResponseEntity.ok().headers(headers).body(out -> {
            try (InputStream in = Files.newInputStream(temp)) {
                in.transferTo(out);
            } finally {
                Files.deleteIfExists(temp);
            }
        });
    }

    /**
     * Large files: streamed directly. The last chunk is held back until the
     * hash of the whole content is known; on a mismatch the connection is cut
     * before it, so the client never ends up with a complete, "successful" download.
     */
    private void streamVerifying(FileRecord file, OutputStream out, String actor) throws IOException {
        MessageDigest digest = Sha256.newDigest();
        byte[] current = new byte[BUFFER];
        byte[] held = new byte[BUFFER];
        int heldLength = 0;
        try (InputStream in = content.open(file)) {
            int read;
            while ((read = in.readNBytes(current, 0, BUFFER)) > 0) {
                digest.update(current, 0, read);
                if (heldLength > 0) {
                    out.write(held, 0, heldLength);
                }
                byte[] swap = held;
                held = current;
                current = swap;
                heldLength = read;
            }
        }
        FileContentService.VerifyResult result = content.record(file, Sha256.hex(digest), actor, "RETRIEVAL");
        if (!result.intact()) {
            log.warn("Download of {} aborted before the last chunk: integrity mismatch", file.getId());
            throw new IOException("Integrity mismatch: download of " + file.getId() + " aborted");
        }
        out.write(held, 0, heldLength);
    }

    private void copyAll(FileRecord file, OutputStream out) throws IOException {
        try (InputStream in = content.open(file)) {
            in.transferTo(out);
        }
    }

    /** Range request (video preview): a part cannot be verified on its own, which the header says. */
    private ResponseEntity<StreamingResponseBody> partial(FileRecord file, long[] bounds, HttpHeaders headers,
                                                          String actor, FileEventType eventType) {
        long start = bounds[0];
        long end = bounds[1];
        long length = end - start + 1;
        headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + file.getSizeBytes());
        headers.set("X-Integrity", "partial");
        headers.setContentLength(length);
        if (start == 0) {
            audit(file, eventType, actor, "range");
        }
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).headers(headers).body(out -> {
            try (InputStream in = content.open(file)) {
                in.skipNBytes(start);
                byte[] buffer = new byte[BUFFER];
                long remaining = length;
                while (remaining > 0) {
                    int read = in.read(buffer, 0, (int) Math.min(BUFFER, remaining));
                    if (read == -1) {
                        break;
                    }
                    out.write(buffer, 0, read);
                    remaining -= read;
                }
            }
        });
    }

    /** @return {first, last} byte positions, or {@code null} when the whole file is requested */
    private long[] parseRange(String range, long size) {
        if (range == null || !range.startsWith("bytes=") || size == 0) {
            return null;
        }
        String spec = range.substring(6).trim();
        if (spec.contains(",")) {
            return null; // multiple ranges: answer with the whole file
        }
        int dash = spec.indexOf('-');
        try {
            long start;
            long end;
            if (dash == 0) {
                long suffix = Long.parseLong(spec.substring(1));
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                start = Long.parseLong(spec.substring(0, dash));
                end = dash == spec.length() - 1 ? size - 1 : Math.min(size - 1, Long.parseLong(spec.substring(dash + 1)));
            }
            if (dash < 0 || start > end || start >= size) {
                throw new ProblemException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "range-not-satisfiable",
                        texts.get("file.error.range"));
            }
            return new long[] {start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static MediaType mediaType(FileRecord file) {
        try {
            return file.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM
                    : MediaType.parseMediaType(file.getContentType());
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private void audit(FileRecord file, FileEventType type, String actor, String mode) {
        lifecycle.audit(file, type, actor, lifecycle.details().put("what", "content").put("mode", mode)
                .put("storage", file.getStorageType().name()));
    }

    private void require(String actor, String permission) {
        if (!access.isAllowed(actor, permission)) {
            throw new ProblemException(HttpStatus.FORBIDDEN, "forbidden", texts.get("file.error.forbidden"));
        }
    }
}
