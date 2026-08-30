/**
 * Internals of the phone QR panel (issue landing-page/03): resolving the app's public base URL from
 * the incoming request, and rendering it as inline SVG.
 *
 * <p>A sub-package of {@code web}, so Modulith treats it as module-internal (CLAUDE.md §1.1) — its
 * types are {@code public} only so the module's root-package {@link
 * volkovandr.hauptbuch.web.PhoneQrCodeService} can call them; no other module may reach in.
 */
package volkovandr.hauptbuch.web.qr;
