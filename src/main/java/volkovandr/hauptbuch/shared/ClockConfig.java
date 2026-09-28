package volkovandr.hauptbuch.shared;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application's one system {@link Clock}. Code that needs "now" or "today" takes it by
 * injection rather than calling {@code LocalDate.now()}, so a unit test can drive it with {@code
 * Clock.fixed} — the receipt storage stamps capture filenames from it, and the recurring templates
 * compute "today" from it.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfig {

  @Bean
  Clock clock() {
    return Clock.systemDefaultZone();
  }
}
