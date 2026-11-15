package ro.filegrid.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RolePermissionsTest {

    @ParameterizedTest
    @ValueSource(strings = {"connections", "admin"})
    void onlyConnectionsAndAdminReadPersonalData(String role) {
        assertThat(RolePermissions.of(List.of(role))).contains(RolePermissions.PII_READ);
    }

    @ParameterizedTest
    @ValueSource(strings = {"field", "dispatcher", "analyst", "incident-manager",
            "svc-meter-ingest", "svc-alert-ingest", "svc-intelligence", "svc-whatsapp"})
    void otherRolesDoNotReadPersonalData(String role) {
        assertThat(RolePermissions.of(List.of(role))).doesNotContain(RolePermissions.PII_READ);
    }

    @Test
    void onlyAdminWritesPoliciesAndReadsSuspectFiles() {
        for (String role : List.of("field", "dispatcher", "connections", "analyst", "incident-manager")) {
            assertThat(RolePermissions.of(List.of(role)))
                    .doesNotContain(RolePermissions.POLICY_WRITE, RolePermissions.FILE_READ_SUSPECT);
        }
        assertThat(RolePermissions.of(List.of("admin")))
                .contains(RolePermissions.POLICY_WRITE, RolePermissions.FILE_READ_SUSPECT);
    }

    @Test
    void permissionsOfSeveralRolesAreUnited() {
        assertThat(RolePermissions.of(List.of("field", "analyst")))
                .contains(RolePermissions.FILE_WRITE, RolePermissions.INTELLIGENCE_WRITE)
                .doesNotContain(RolePermissions.PII_READ);
    }

    @Test
    void whatsappChannelOnlyReadsAndAsksNeverWrites() {
        assertThat(RolePermissions.of(List.of("svc-whatsapp")))
                .contains(RolePermissions.ASSISTANT_USE, RolePermissions.VOICE_USE)
                .doesNotContain(RolePermissions.PII_READ, RolePermissions.FILE_WRITE, RolePermissions.INCIDENT_WRITE,
                        RolePermissions.INCIDENT_CREATE, RolePermissions.POLICY_WRITE);
    }

    @Test
    void unknownRolesGrantNothing() {
        assertThat(RolePermissions.of(List.of("offline_access", "uma_authorization", "root"))).isEmpty();
    }
}
