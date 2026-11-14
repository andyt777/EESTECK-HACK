package ro.filegrid.upload;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.filegrid.file.DataOrigin;
import ro.filegrid.file.FileCategory;

import static org.assertj.core.api.Assertions.assertThat;

class TusMetadataParserTest {

    private TusMetadataParser parser;

    @BeforeEach
    void setUp() {
        parser = new TusMetadataParser();
    }

    private String encode(String val) {
        return Base64.getEncoder().encodeToString(val.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesCompleteMetadataHeader() {
        String header = String.join(",",
                "category " + encode("METER_READING"),
                "zone " + encode("Muntenia"),
                "station " + encode("Statia Nord 110kV"),
                "equipment " + encode("TR_110_20_1"),
                "meterId " + encode("MTR-4001"),
                "eventType " + encode("OVERVOLTAGE"),
                "eventTime " + encode("2026-11-14T16:20:00Z"),
                "description " + encode("Surge on phase A"),
                "dataOrigin " + encode("SIMULATED"),
                "filename " + encode("meter_2026-11-14.csv"),
                "contentType " + encode("text/csv")
        );

        TusMetadataParser.ParsedTusMetadata result = parser.parse(header);

        assertThat(result.category()).contains(FileCategory.METER_READING);
        assertThat(result.zone()).contains("Muntenia");
        assertThat(result.station()).contains("Statia Nord 110kV");
        assertThat(result.equipment()).contains("TR_110_20_1");
        assertThat(result.meterId()).contains("MTR-4001");
        assertThat(result.eventType()).contains("OVERVOLTAGE");
        assertThat(result.eventTime()).contains(Instant.parse("2026-11-14T16:20:00Z"));
        assertThat(result.description()).contains("Surge on phase A");
        assertThat(result.dataOrigin()).isEqualTo(DataOrigin.SIMULATED);
        assertThat(result.filename()).contains("meter_2026-11-14.csv");
        assertThat(result.contentType()).contains("text/csv");

        assertThat(result.toContextMap())
                .containsEntry("zone", "Muntenia")
                .containsEntry("station", "Statia Nord 110kV")
                .containsEntry("equipment", "TR_110_20_1")
                .containsEntry("meterId", "MTR-4001")
                .containsEntry("eventType", "OVERVOLTAGE")
                .containsEntry("eventTime", "2026-11-14T16:20:00Z")
                .containsEntry("description", "Surge on phase A");
    }

    @Test
    void parsesEmptyOrNullHeaderGracefully() {
        TusMetadataParser.ParsedTusMetadata resultNull = parser.parse(null);
        assertThat(resultNull.category()).isEmpty();
        assertThat(resultNull.filename()).isEmpty();
        assertThat(resultNull.dataOrigin()).isEqualTo(DataOrigin.REAL);

        TusMetadataParser.ParsedTusMetadata resultEmpty = parser.parse("   ");
        assertThat(resultEmpty.category()).isEmpty();
        assertThat(resultEmpty.filename()).isEmpty();
    }

    @Test
    void fallsBackToNameAndFiletype() {
        String header = String.join(",",
                "name " + encode("backup.tar.gz"),
                "filetype " + encode("application/gzip")
        );

        TusMetadataParser.ParsedTusMetadata result = parser.parse(header);
        assertThat(result.filename()).contains("backup.tar.gz");
        assertThat(result.contentType()).contains("application/gzip");
    }

    @Test
    void handlesInvalidCategoryAndInvalidDateSafely() {
        String header = String.join(",",
                "category " + encode("NON_EXISTENT_CATEGORY"),
                "eventTime " + encode("not-a-date"),
                "corruptKey !!!not-base64!!!"
        );

        TusMetadataParser.ParsedTusMetadata result = parser.parse(header);
        assertThat(result.category()).isEmpty();
        assertThat(result.eventTime()).isEmpty();
    }
}
