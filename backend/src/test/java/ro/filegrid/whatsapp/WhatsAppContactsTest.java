package ro.filegrid.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class WhatsAppContactsTest {

    private final WhatsAppContacts contacts = new WhatsAppContacts(Map.of(
            "NOTIFY_CONTACT_DISPATCH", "+40 722 000 111",
            "NOTIFY_CONTACT_FIELD_TEAM_TULCEA", "+40722000222",
            "NOTIFY_CONTACT_IT_SECURITY", "",
            "OTHER_VAR", "+40722000333"));

    @Test
    void findsTheRoleOfAConfiguredNumberWhateverTheFormatting() {
        assertThat(contacts.roleOf("whatsapp:+40722000111")).contains("DISPATCH");
        assertThat(contacts.roleOf("+40722000222")).contains("FIELD_TEAM_TULCEA");
    }

    @Test
    void unknownBlankOrUnrelatedNumbersHaveNoRole() {
        assertThat(contacts.roleOf("whatsapp:+40722000333")).isEmpty();
        assertThat(contacts.roleOf("whatsapp:+40799999999")).isEmpty();
        assertThat(contacts.roleOf("")).isEmpty();
        assertThat(contacts.roleOf(null)).isEmpty();
    }

    @Test
    void phoneIsNormalisedToE164() {
        assertThat(WhatsAppContacts.normalise("whatsapp:+40 (722) 000-111")).isEqualTo("+40722000111");
    }
}
