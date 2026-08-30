package volkovandr.hauptbuch.web;

import com.google.zxing.WriterException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.web.qr.PublicBaseUrlResolver;
import volkovandr.hauptbuch.web.qr.QrCodeSvgWriter;

/**
 * Builds the landing page's phone QR panel (issue landing-page/03).
 *
 * <p>Lives in {@code web} rather than with {@link volkovandr.hauptbuch.ledger.LandingController}
 * because it is UI-shell infrastructure with no domain content at all — it reads the request and
 * config, never the book. The landing controller already depends on this module's {@code NavItem},
 * so the edge exists and {@code ApplicationModules.verify()} stays green.
 *
 * <p>Takes the request as a parameter rather than reaching for {@code RequestContextHolder}: the
 * dependency is then visible in the signature, and the resolver is unit-testable with a {@code
 * MockHttpServletRequest}.
 */
@Service
public class PhoneQrCodeService {

  private static final Logger LOG = LoggerFactory.getLogger(PhoneQrCodeService.class);

  private final PublicBaseUrlResolver publicBaseUrlResolver;
  private final QrCodeSvgWriter qrCodeSvgWriter;

  PhoneQrCodeService(PublicBaseUrlResolver publicBaseUrlResolver, QrCodeSvgWriter qrCodeSvgWriter) {
    this.publicBaseUrlResolver = publicBaseUrlResolver;
    this.qrCodeSvgWriter = qrCodeSvgWriter;
  }

  /**
   * The panel model for this request, or empty when the panel should not render at all — no URL
   * could be resolved (the request came in on loopback), or encoding it failed.
   *
   * <p>Encoding one QR per landing render is microseconds, so there is no cache here.
   */
  public Optional<PhoneQrCode> forRequest(HttpServletRequest request) {
    return publicBaseUrlResolver.resolve(request).flatMap(this::encode);
  }

  private Optional<PhoneQrCode> encode(String url) {
    try {
      return Optional.of(new PhoneQrCode(url, qrCodeSvgWriter.svgFor(url)));
    } catch (WriterException e) {
      // Handled and degraded: the landing page renders without the panel.
      LOG.warn("Could not encode the phone QR code for {}; omitting the panel", url, e);
      return Optional.empty();
    }
  }
}
