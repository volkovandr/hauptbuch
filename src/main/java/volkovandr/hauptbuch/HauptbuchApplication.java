package volkovandr.hauptbuch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Spring Boot entry point for the Hauptbuch application. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class HauptbuchApplication {

  /** Starts the application. */
  static void main(String[] args) {
    SpringApplication.run(HauptbuchApplication.class, args);
  }
}
