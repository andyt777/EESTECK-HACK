package ro.filegrid;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * FileGrid backend. The same application runs as {@code api} or as {@code worker},
 * depending on the active Spring profile.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class FileGridApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileGridApplication.class, args);
    }
}
