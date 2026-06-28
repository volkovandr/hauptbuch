package volkovandr.hauptbuch;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Architecture fitness test — the module boundaries have teeth (CLAUDE.md §1.1/§1.2).
 *
 * <p>A red result here means a structural rule was broken: a module reached into another module's
 * internals, a layer-first package appeared, or a dependency cycle was introduced. The fix is to
 * repair the boundary, never to suppress this test. It lives in the fast unit suite so it runs on
 * every {@code ./gradlew test}.
 */
class ModularityTests {

  static final ApplicationModules MODULES = ApplicationModules.of(HauptbuchApplication.class);

  @Test
  void verifiesModuleStructure() {
    MODULES.verify();
  }
}
