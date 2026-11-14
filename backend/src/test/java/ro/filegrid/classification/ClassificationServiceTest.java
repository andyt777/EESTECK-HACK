package ro.filegrid.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ro.filegrid.classification.ClassificationResult.Basis;
import ro.filegrid.file.FileCategory;
import ro.filegrid.policy.Policy;
import ro.filegrid.policy.PolicyParser;
import ro.filegrid.text.Texts;

class ClassificationServiceTest {

    private static final byte[] PDF_HEAD = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PNG_HEAD = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R'
    };
    private static final byte[] MP4_HEAD = new byte[] {
            0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2',
            0x00, 0x00, 0x00, 0x00, 'm', 'p', '4', '2', 'i', 's', 'o', 'm'
    };
    private static final byte[] ELF_HEAD = new byte[] {
            0x7F, 'E', 'L', 'F', 0x02, 0x01, 0x01, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    };

    private static Policy policy;
    private static ClassificationService service;

    @BeforeAll
    static void setUp() throws IOException {
        policy = PolicyParser.parse(Files.readString(Path.of("../config/policies.yaml")));
        service = new ClassificationService(null, new Texts());
    }

    @Test
    void detectsEveryCategoryWhenNoneIsDeclared() {
        ClassificationResult meter = service.classify(new ClassificationRequest(
                null, "reading.json", null,
                "{\"meterId\":\"M-100\",\"kwh\":12.5}".getBytes(StandardCharsets.UTF_8),
                Map.of("meterId", "M-100")), policy);
        assertThat(meter.category()).isEqualTo(FileCategory.METER_READING);
        assertThat(meter.basis()).isEqualTo(Basis.DETECTED);
        assertThat(meter.detectedMimeType()).isEqualTo("application/json");
        assertThat(meter.warnings()).isEmpty();

        ClassificationResult fault = service.classify(new ClassificationRequest(
                null, "avarie.pdf", null, PDF_HEAD, Map.of("station", "Statia Nord")), policy);
        assertThat(fault.category()).isEqualTo(FileCategory.FAULT_RECORD);
        assertThat(fault.basis()).isEqualTo(Basis.DETECTED);
        assertThat(fault.detectedMimeType()).isEqualTo("application/pdf");
        assertThat(fault.warnings()).isEmpty();

        ClassificationResult document = service.classify(new ClassificationRequest(
                null, "ci.png", null, PNG_HEAD, Map.of()), policy);
        assertThat(document.category()).isEqualTo(FileCategory.CONNECTION_DOCUMENT);
        assertThat(document.basis()).isEqualTo(Basis.DETECTED);
        assertThat(document.detectedMimeType()).isEqualTo("image/png");
        assertThat(document.warnings()).isEmpty();

        ClassificationResult video = service.classify(new ClassificationRequest(
                null, "inspectie.mp4", null, MP4_HEAD, Map.of()), policy);
        assertThat(video.category()).isEqualTo(FileCategory.DRONE_VIDEO);
        assertThat(video.basis()).isEqualTo(Basis.DETECTED);
        assertThat(video.detectedMimeType()).isEqualTo("video/mp4");
        assertThat(video.warnings()).isEmpty();
    }

    @Test
    void magicBytesTakePriorityOverMisleadingExtension() {
        ClassificationResult result = service.classify(new ClassificationRequest(
                null, "inspectie.mp4", "video/mp4", PDF_HEAD, Map.of()), policy);

        assertThat(result.detectedMimeType()).isEqualTo("application/pdf");
        assertThat(result.detectedCategory()).isEqualTo(FileCategory.CONNECTION_DOCUMENT);
    }

    @Test
    void declaredCategoryWinsWithWarningWhenContentDiffers() {
        ClassificationResult pdfAsVideo = service.classify(new ClassificationRequest(
                FileCategory.DRONE_VIDEO, "inspectie.mp4", "video/mp4", PDF_HEAD, Map.of()), policy);

        assertThat(pdfAsVideo.category()).isEqualTo(FileCategory.DRONE_VIDEO);
        assertThat(pdfAsVideo.basis()).isEqualTo(Basis.DECLARED);
        assertThat(pdfAsVideo.detectedCategory()).isEqualTo(FileCategory.CONNECTION_DOCUMENT);
        assertThat(pdfAsVideo.hasWarning(ClassificationService.WARNING_MISMATCH)).isTrue();

        ClassificationResult jsonAsVideo = service.classify(new ClassificationRequest(
                FileCategory.DRONE_VIDEO, "reading.json", "application/json",
                "{\"meterId\":\"M-100\"}".getBytes(StandardCharsets.UTF_8), Map.of("meterId", "M-100")), policy);

        assertThat(jsonAsVideo.category()).isEqualTo(FileCategory.DRONE_VIDEO);
        assertThat(jsonAsVideo.basis()).isEqualTo(Basis.DECLARED);
        assertThat(jsonAsVideo.detectedCategory()).isEqualTo(FileCategory.METER_READING);
        assertThat(jsonAsVideo.hasWarning(ClassificationService.WARNING_MISMATCH)).isTrue();
    }

    @Test
    void warnsWhenRequiredContextFieldsAreMissing() {
        ClassificationResult meterWithoutId = service.classify(new ClassificationRequest(
                FileCategory.METER_READING, "reading.json", null,
                "{\"kwh\":12.5}".getBytes(StandardCharsets.UTF_8), Map.of()), policy);
        assertThat(meterWithoutId.hasWarning(ClassificationService.WARNING_MISSING_FIELDS)).isTrue();
        assertThat(meterWithoutId.warnings())
                .anySatisfy(w -> assertThat(w.text()).contains("meterId"));

        ClassificationResult faultWithoutStation = service.classify(new ClassificationRequest(
                FileCategory.FAULT_RECORD, "avarie.csv", null,
                "time,voltage\n2026-11-14T10:00:00Z,230\n".getBytes(StandardCharsets.UTF_8), Map.of()), policy);
        assertThat(faultWithoutStation.hasWarning(ClassificationService.WARNING_MISSING_FIELDS)).isTrue();
        assertThat(faultWithoutStation.warnings())
                .anySatisfy(w -> assertThat(w.text()).contains("station"));
    }

    @Test
    void marksUnknownContentAsUnclassifiedWhenNothingIsDeclared() {
        ClassificationResult unknown = service.classify(new ClassificationRequest(
                null, "firmware.bin", "application/octet-stream", ELF_HEAD, Map.of()), policy);

        assertThat(unknown.category()).isNull();
        assertThat(unknown.basis()).isEqualTo(Basis.NONE);
        assertThat(unknown.hasWarning(ClassificationService.WARNING_UNCLASSIFIED)).isTrue();
    }

    @Test
    void readHeadReadsAtMostHeadBytesWithoutConsumingTheStream() throws IOException {
        byte[] all = new byte[ClassificationRequest.HEAD_BYTES + 10_000];
        ByteArrayInputStream stream = new ByteArrayInputStream(all);

        byte[] head = ClassificationRequest.readHead(stream);

        assertThat(head).hasSize(ClassificationRequest.HEAD_BYTES);
        assertThat(stream.available()).isEqualTo(10_000);

        byte[] oversized = new byte[ClassificationRequest.HEAD_BYTES + 1];
        assertThatIllegalArgumentException().isThrownBy(() -> new ClassificationRequest(
                null, "large.bin", null, oversized, Map.of()));
    }
}
