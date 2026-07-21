package volkovandr.hauptbuch.web;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Global htmx error boundary (issue 10) — the safety net beneath every screen's own inline
 * validation. Lives in the {@code web} shell so it covers every feature module's controllers
 * without any of them depending on it (a plain cross-cutting web concern; see {@code
 * package-info}).
 *
 * <p>An <em>unexpected</em> exception escaping a controller handler would otherwise reach htmx as a
 * plain 500. htmx 2.0.4 does not swap 4xx/5xx responses (its default {@code responseHandling} marks
 * them {@code swap:false, error:true}), so the user sees no change at all — a silent no-op with the
 * dock left wedged (issue 05). This boundary turns such a failure, for an htmx request, into a 200
 * whose body is retargeted to the shell's {@code #app-error} slot and swapped {@code innerHTML} — a
 * visible toast that never touches (and so never blanks) the element that triggered the request.
 *
 * <p>Deliberately narrow: it engages <strong>only</strong> for htmx requests (the {@code
 * HX-Request} header). A non-htmx request re-throws, keeping the container's default whole-page
 * error behaviour unchanged. Expected, user-facing validation is still handled inline by each
 * screen and never reaches here — this catches the genuinely unexpected, and logs it (nothing leaks
 * to the user).
 */
@ControllerAdvice
class GlobalHtmxErrorAdvice {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalHtmxErrorAdvice.class);

  private static final String HX_REQUEST = "HX-Request";
  private static final String HX_RETARGET = "HX-Retarget";
  private static final String HX_RESWAP = "HX-Reswap";
  private static final String ERROR_SLOT = "#app-error";
  private static final String INNER_HTML = "innerHTML";

  /** Shown to the user; carries no ledger or exception detail on purpose. */
  private static final String USER_MESSAGE =
      "Something went wrong and your change was not saved. Please try again.";

  @ExceptionHandler(Exception.class)
  String handle(
      Exception exception, HttpServletRequest request, HttpServletResponse response, Model model)
      throws ServletException {
    if (!isHtmxRequest(request)) {
      // Not htmx: let the exception propagate to the default (whole-page) error handling, exactly
      // as an uncaught handler exception does today (the container wraps it the same way).
      throw new ServletException(exception);
    }
    LOG.error(
        "Unhandled exception during htmx request {} {}",
        request.getMethod(),
        request.getRequestURI(),
        exception);
    response.setHeader(HX_RETARGET, ERROR_SLOT);
    response.setHeader(HX_RESWAP, INNER_HTML);
    // The view name carries no expression: Thymeleaf refuses an expression-bearing view name when
    // the request URL looks expression-like, and this boundary fires on any path. The fragment
    // reads `message` from the model instead.
    model.addAttribute("message", USER_MESSAGE);
    return "fragments/app-error :: error";
  }

  private static boolean isHtmxRequest(HttpServletRequest request) {
    return "true".equalsIgnoreCase(request.getHeader(HX_REQUEST));
  }
}
