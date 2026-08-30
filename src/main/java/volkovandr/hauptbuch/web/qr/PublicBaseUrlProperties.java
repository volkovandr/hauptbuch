package volkovandr.hauptbuch.web.qr;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The operator's override for the app's public base URL (CONTEXT.md "Public base URL").
 *
 * <p>Unset by default, and only needed when the URL derived from the incoming request is wrong — in
 * practice, a gateway that cannot be made to send {@code X-Forwarded-Prefix}. See {@code
 * deploy/application.yml.example}.
 *
 * @param publicBaseUrl an absolute {@code http(s)} URL, or {@code null}/blank to derive it from the
 *     request instead
 */
@ConfigurationProperties("hauptbuch")
public record PublicBaseUrlProperties(String publicBaseUrl) {}
