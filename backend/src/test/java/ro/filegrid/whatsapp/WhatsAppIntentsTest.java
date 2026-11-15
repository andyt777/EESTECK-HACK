package ro.filegrid.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ro.filegrid.whatsapp.WhatsAppIntents.Intent;
import ro.filegrid.whatsapp.WhatsAppIntents.Kind;

class WhatsAppIntentsTest {

    @ParameterizedTest
    @CsvSource({"OK,", "ok,", "Ok!,", "OK INC-0007,INC-0007", "ok inc-12,INC-12", " OK   INC-0042. ,INC-0042",
            "OK INC-2026-000002,INC-2026-000002"})
    void okConfirmsWithOptionalIncidentCode(String body, String code) {
        Intent intent = WhatsAppIntents.parse(body, null);
        assertThat(intent.kind()).isEqualTo(Kind.CONFIRM);
        assertThat(intent.incidentCode()).isEqualTo(code);
    }

    @ParameterizedTest
    @CsvSource({"DA,APPROVE", "da.,APPROVE", "Da!,APPROVE", "NU,REJECT", "nu,REJECT", "Anulează,REJECT"})
    void yesNoAnswerAPendingReport(String body, Kind kind) {
        assertThat(WhatsAppIntents.parse(body, null).kind()).isEqualTo(kind);
    }

    @Test
    void reportPrefixStartsAFieldReportWithTheRestAsText() {
        Intent intent = WhatsAppIntents.parse("Raport: fum la PT-DOB-04-03", null);
        assertThat(intent.kind()).isEqualTo(Kind.REPORT_TEXT);
        assertThat(intent.text()).isEqualTo("fum la PT-DOB-04-03");
    }

    @Test
    void mediaDecidesBeforeText() {
        assertThat(WhatsAppIntents.parse("", "audio/ogg").kind()).isEqualTo(Kind.REPORT_VOICE);
        Intent photo = WhatsAppIntents.parse("stâlp căzut", "image/jpeg");
        assertThat(photo.kind()).isEqualTo(Kind.REPORT_PHOTO);
        assertThat(photo.text()).isEqualTo("stâlp căzut");
        assertThat(WhatsAppIntents.parse("x", "application/pdf").kind()).isEqualTo(Kind.UNSUPPORTED);
    }

    @Test
    void anythingElseIsAQuestionAndBlankIsEmpty() {
        assertThat(WhatsAppIntents.parse("Ce incidente sunt deschise în Tulcea?", null).kind())
                .isEqualTo(Kind.QUESTION);
        assertThat(WhatsAppIntents.parse("Okay, mulțumesc", null).kind()).isEqualTo(Kind.QUESTION);
        assertThat(WhatsAppIntents.parse("   ", null).kind()).isEqualTo(Kind.EMPTY);
        assertThat(WhatsAppIntents.parse(null, null).kind()).isEqualTo(Kind.EMPTY);
    }
}
