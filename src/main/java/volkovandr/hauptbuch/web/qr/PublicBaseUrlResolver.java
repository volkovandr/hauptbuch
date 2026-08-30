package volkovandr.hauptbuch.web.qr;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves the "Public base URL" (CONTEXT.md) — the URL a phone on the same LAN can reach this app
 * at, always ending in exactly one {@code /}.
 *
 * <p>Two sources, override first:
 *
 * <ol>
 *   <li>{@code hauptbuch.public-base-url}, when set to an absolute {@code http(s)} URL. The escape
 *       hatch for a deployment the request cannot describe.
 *   <li>The origin of the incoming request — the URL the desktop browser is already reaching the
 *       app at, which is by construction one that resolves on this LAN. {@code
 *       server.forward-headers-strategy: framework} installs Spring's {@code
 *       ForwardedHeaderFilter}, so a gateway's {@code X-Forwarded-Proto}/{@code -Host}/{@code
 *       -Port}/{@code -Prefix} have <em>already</em> been folded into the request by the time it
 *       gets here; this class reads the plain servlet getters and handles no headers itself.
 * </ol>
 *
 * <p>A request that arrived on loopback resolves to nothing, and the panel does not render: a QR of
 * {@code http://localhost:8080/} is useless on a phone, and dev is exactly where that happens. An
 * explicit override is honoured even then — overriding is the operator saying they know better than
 * the request, and second-guessing it would defeat the point.
 */
@Component
public class PublicBaseUrlResolver {

  private static final Logger LOG = LoggerFactory.getLogger(PublicBaseUrlResolver.class);

  /** IPv4 loopback is the whole {@code 127.0.0.0/8} block, not just {@code 127.0.0.1}. */
  private static final Pattern LOOPBACK_IPV4 =
      Pattern.compile("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

  private final String override;

  PublicBaseUrlResolver(PublicBaseUrlProperties properties) {
    this.override = properties.publicBaseUrl();
  }

  /** The public base URL for this request, or empty when none can usefully be offered. */
  public Optional<String> resolve(HttpServletRequest request) {
    return fromOverride().or(() -> fromRequest(request));
  }

  private Optional<String> fromOverride() {
    if (override == null || override.isBlank()) {
      return Optional.empty();
    }
    String candidate = override.trim();
    if (!isAbsoluteHttpUrl(candidate)) {
      LOG.warn(
          "Ignoring hauptbuch.public-base-url: '{}' is not an absolute http(s) URL;"
              + " deriving the phone QR URL from the request instead",
          candidate);
      return Optional.empty();
    }
    return Optional.of(withSingleTrailingSlash(candidate));
  }

  private static boolean isAbsoluteHttpUrl(String candidate) {
    try {
      URI uri = new URI(candidate);
      String scheme = uri.getScheme();
      return uri.isAbsolute()
          && uri.getHost() != null
          && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
    } catch (URISyntaxException e) {
      return false;
    }
  }

  private static Optional<String> fromRequest(HttpServletRequest request) {
    String host = request.getServerName();
    if (isLoopback(host)) {
      return Optional.empty();
    }
    String scheme = request.getScheme();
    StringBuilder url = new StringBuilder(scheme).append("://").append(bracketIfIpv6(host));
    int port = request.getServerPort();
    if (port != defaultPortFor(scheme)) {
      url.append(':').append(port);
    }
    // The context path only — never the request's own path or query string. Behind a gateway the
    // ForwardedHeaderFilter has already set this from X-Forwarded-Prefix.
    url.append(request.getContextPath());
    return Optional.of(withSingleTrailingSlash(url.toString()));
  }

  /**
   * An IPv6 literal has to be bracketed to sit in a URL, or the address's own colons run into the
   * port. The servlet API hands the host over unbracketed; a hostname or IPv4 address has no colon
   * and is returned untouched.
   */
  private static String bracketIfIpv6(String host) {
    return host.indexOf(':') >= 0 && host.charAt(0) != '[' ? "[" + host + "]" : host;
  }

  private static int defaultPortFor(String scheme) {
    return "https".equalsIgnoreCase(scheme) ? 443 : 80;
  }

  // The loopback literals are this method's subject, not a hardcoded endpoint it talks to —
  // which is what PMD's rule is actually for.
  @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
  private static boolean isLoopback(String host) {
    if (host == null) {
      return true;
    }
    // Never resolves a name: a DNS lookup on every landing render would be both slow and wrong
    // (a LAN hostname is exactly what we want to keep).
    String bare = host.replace("[", "").replace("]", "").toLowerCase(Locale.ROOT);
    return "localhost".equals(bare)
        || "::1".equals(bare)
        || "0:0:0:0:0:0:0:1".equals(bare)
        || LOOPBACK_IPV4.matcher(bare).matches();
  }

  private static String withSingleTrailingSlash(String url) {
    int end = url.length();
    while (end > 0 && url.charAt(end - 1) == '/') {
      end--;
    }
    return url.substring(0, end) + "/";
  }
}
