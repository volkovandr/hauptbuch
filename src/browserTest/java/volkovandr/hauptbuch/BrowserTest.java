package volkovandr.hauptbuch;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Browser tier: the app on a random port against this suite's own Postgres, driven by a headless
 * Chromium. For what only a real browser can show — the client-side behaviour of the sanctioned JS
 * leaves (CLAUDE.md §1.6); everything server-side stays in the MockMvc acceptance tests of the
 * integration tier.
 *
 * <p>One browser per test class, and a fresh context (cookies, storage) and page per test. The app
 * commits what it writes, so a test class seeds its data once, with names no other class uses, and
 * asserts only on what it seeded.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class BrowserTest {

  @LocalServerPort private int port;

  private Playwright playwright;
  private Browser browser;
  private BrowserContext context;
  protected Page page;

  @BeforeAll
  void launchBrowser() {
    playwright = Playwright.create();
    browser = playwright.chromium().launch();
  }

  @AfterAll
  void closeBrowser() {
    playwright.close();
  }

  @BeforeEach
  void openPage() {
    context = browser.newContext();
    page = context.newPage();
  }

  @AfterEach
  void closePage() {
    context.close();
  }

  /** The absolute URL of {@code path} on the app under test. */
  protected String url(String path) {
    return "http://localhost:" + port + path;
  }
}
