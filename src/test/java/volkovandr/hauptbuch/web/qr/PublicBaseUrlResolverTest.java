package volkovandr.hauptbuch.web.qr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Unit tier: the "Public base URL" resolution (CONTEXT.md, issue landing-page/03) — the config
 * override first, the incoming request second, nothing at all when the request came in on loopback.
 *
 * <p>Reads a plain {@link MockHttpServletRequest} rather than a mocked servlet API: the resolver
 * takes the request as a parameter precisely so it can be driven this way. The gateway cases are
 * expressed as the <em>already-rewritten</em> request, because {@code ForwardedHeaderFilter} does
 * the rewriting before the resolver ever sees it — that the filter is installed is proved in the
 * integration tier, not here.
 */
// The loopback and LAN literals below are the cases under test, not endpoints this code talks to.
@SuppressWarnings("PMD.AvoidUsingHardCodedIP")
class PublicBaseUrlResolverTest {

  private static final PublicBaseUrlResolver RESOLVER = new PublicBaseUrlResolver(null);

  private static PublicBaseUrlResolver withOverride(String override) {
    return new PublicBaseUrlResolver(override);
  }

  private static MockHttpServletRequest request(String scheme, String host, int port) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setScheme(scheme);
    request.setServerName(host);
    request.setServerPort(port);
    return request;
  }

  @Test
  void derivesTheUrlFromHostnameRequest() {
    assertThat(RESOLVER.resolve(request("http", "raspberrypi", 8080)))
        .contains("http://raspberrypi:8080/");
  }

  @Test
  void keepsOnlyTheOriginDroppingPathAndQuery() {
    MockHttpServletRequest request = request("http", "192.168.1.14", 8080);
    request.setRequestURI("/register");
    request.setQueryString("accountId=3");

    assertThat(RESOLVER.resolve(request)).contains("http://192.168.1.14:8080/");
  }

  @Test
  void preservesTheContextPath() {
    MockHttpServletRequest request = request("http", "homenet", 80);
    request.setContextPath("/pi/hauptbuch");

    assertThat(RESOLVER.resolve(request)).contains("http://homenet/pi/hauptbuch/");
  }

  @Test
  void dropsTheDefaultPortForHttp() {
    assertThat(RESOLVER.resolve(request("http", "homenet", 80))).contains("http://homenet/");
  }

  @Test
  void dropsTheDefaultPortForHttps() {
    assertThat(RESOLVER.resolve(request("https", "homenet", 443))).contains("https://homenet/");
  }

  @Test
  void keepsNonDefaultPortForHttps() {
    assertThat(RESOLVER.resolve(request("https", "homenet", 8443)))
        .contains("https://homenet:8443/");
  }

  @Test
  void resolvesNothingForLocalhost() {
    assertThat(RESOLVER.resolve(request("http", "localhost", 8080))).isEmpty();
  }

  @Test
  void resolvesNothingForTheLoopbackIpv4Address() {
    assertThat(RESOLVER.resolve(request("http", "127.0.0.1", 8080))).isEmpty();
  }

  @Test
  void resolvesNothingForAnyAddressInTheLoopbackRange() {
    assertThat(RESOLVER.resolve(request("http", "127.1.2.3", 8080))).isEmpty();
  }

  @Test
  void resolvesNothingForTheLoopbackIpv6Address() {
    assertThat(RESOLVER.resolve(request("http", "::1", 8080))).isEmpty();
    assertThat(RESOLVER.resolve(request("http", "[::1]", 8080))).isEmpty();
    assertThat(RESOLVER.resolve(request("http", "0:0:0:0:0:0:0:1", 8080))).isEmpty();
  }

  @Test
  void treatsHostMerelyStartingWithTheLoopbackDigitsAsRoutable() {
    assertThat(RESOLVER.resolve(request("http", "127.0.0.1.example", 8080)))
        .contains("http://127.0.0.1.example:8080/");
  }

  @Test
  void bracketsAnIpv6HostSoThePortIsStillReadable() {
    assertThat(RESOLVER.resolve(request("http", "fe80::1", 8080)))
        .contains("http://[fe80::1]:8080/");
  }

  @Test
  void doesNotDoubleBracketAnAlreadyBracketedIpv6Host() {
    assertThat(RESOLVER.resolve(request("http", "[fe80::1]", 8080)))
        .contains("http://[fe80::1]:8080/");
  }

  @Test
  void overrideWinsOverPerfectlyGoodDerivedUrl() {
    Optional<String> resolved =
        withOverride("http://homenet/pi/hauptbuch").resolve(request("http", "raspberrypi", 8080));

    assertThat(resolved).contains("http://homenet/pi/hauptbuch/");
  }

  @Test
  void overrideIsUsedEvenWhenTheRequestCameInOnLoopback() {
    assertThat(withOverride("http://homenet/").resolve(request("http", "localhost", 8080)))
        .contains("http://homenet/");
  }

  @Test
  void normalisesTheOverrideToExactlyOneTrailingSlash() {
    assertThat(withOverride("http://homenet/pi").resolve(request("http", "localhost", 8080)))
        .contains("http://homenet/pi/");
    assertThat(withOverride("http://homenet/pi/").resolve(request("http", "localhost", 8080)))
        .contains("http://homenet/pi/");
    assertThat(withOverride("http://homenet/pi///").resolve(request("http", "localhost", 8080)))
        .contains("http://homenet/pi/");
  }

  @Test
  void blankOverrideIsIgnored() {
    assertThat(withOverride("   ").resolve(request("http", "raspberrypi", 8080)))
        .contains("http://raspberrypi:8080/");
  }

  @Test
  void unsetOverrideIsIgnored() {
    assertThat(withOverride(null).resolve(request("http", "raspberrypi", 8080)))
        .contains("http://raspberrypi:8080/");
  }

  @Test
  void overrideThatIsNotAnAbsoluteHttpUrlFallsBackToTheRequest() {
    assertThat(withOverride("homenet/pi").resolve(request("http", "raspberrypi", 8080)))
        .contains("http://raspberrypi:8080/");
    assertThat(withOverride("ftp://homenet/").resolve(request("http", "raspberrypi", 8080)))
        .contains("http://raspberrypi:8080/");
    assertThat(withOverride("not a url at all").resolve(request("http", "raspberrypi", 8080)))
        .contains("http://raspberrypi:8080/");
  }
}
