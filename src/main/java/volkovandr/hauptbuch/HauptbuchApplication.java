package volkovandr.hauptbuch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for the Hauptbuch application.
 *
 * <p>Scheduling is enabled for the receipt batch poller (stage 9h) — the app's only scheduled work.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class HauptbuchApplication {

  /** Starts the application. */
  static void main(String[] args) {
    SpringApplication.run(HauptbuchApplication.class, args);
  }
}
