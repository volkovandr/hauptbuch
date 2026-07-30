package volkovandr.hauptbuch.receipts;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Module-internal wiring for {@link ReceiptStorage}. Supplies the system {@link Clock} the storage
 * layer stamps capture-timestamp filenames from — a bean so tests can drive it with a fixed clock
 * to exercise the collision-suffix path deterministically.
 */
@Configuration(proxyBeanMethods = false)
class ReceiptStorageConfig {

  @Bean
  Clock receiptStorageClock() {
    return Clock.systemDefaultZone();
  }
}
