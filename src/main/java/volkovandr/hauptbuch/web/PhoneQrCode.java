package volkovandr.hauptbuch.web;

/**
 * The landing page's phone QR panel model (CONTEXT.md "Phone QR panel"): the app's public base URL
 * and that same URL rendered as an inline {@code <svg>} QR code.
 *
 * <p>Both halves are shown — the code for a camera, the text for reading off and typing. The URL
 * carries no credential and grants nothing the LAN did not already grant.
 *
 * @param url the public base URL, ending in exactly one {@code /}
 * @param svg the QR code as a complete inline {@code <svg>} element, already HTML-escaped where it
 *     interpolates the URL, and inlined with {@code th:utext}
 */
public record PhoneQrCode(String url, String svg) {}
