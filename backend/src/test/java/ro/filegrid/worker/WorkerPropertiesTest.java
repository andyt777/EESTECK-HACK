package ro.filegrid.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import ro.filegrid.file.FileCategory;

class WorkerPropertiesTest {

    @Test
    void parsesCommaSeparatedCategories() {
        WorkerProperties properties = new WorkerProperties(" METER_READING, FAULT_RECORD ,", 4);

        assertThat(properties.parsedCategories())
                .containsExactlyInAnyOrder(FileCategory.METER_READING, FileCategory.FAULT_RECORD);
    }

    @Test
    void blankMeansNoCategories() {
        assertThat(new WorkerProperties("", 4).parsedCategories()).isEmpty();
        assertThat(new WorkerProperties(null, 4).parsedCategories()).isEmpty();
    }

    @Test
    void unknownCategoryIsRejectedWithTheAllowedValues() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WorkerProperties("METER_READING,PHOTOS", 4).parsedCategories())
                .withMessageContaining("PHOTOS")
                .withMessageContaining("DRONE_VIDEO");
    }

    @Test
    void workerRoleRefusesToStartWithoutCategories() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new WorkerRole(new WorkerProperties("", 4)))
                .withMessageContaining("WORKER_CATEGORIES");
    }

    @Test
    void workerRoleExposesItsCategories() {
        WorkerRole role = new WorkerRole(new WorkerProperties("DRONE_VIDEO", 2));

        assertThat(role.categories()).containsExactly(FileCategory.DRONE_VIDEO);
        assertThat(role.concurrency()).isEqualTo(2);
    }

    @Test
    void workerRoleRefusesAConcurrencyBelowOne() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new WorkerRole(new WorkerProperties("DRONE_VIDEO", 0)))
                .withMessageContaining("WORKER_CONCURRENCY");
    }
}
