package volkovandr.hauptbuch.receipts;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Hands every screen the "Confirmed!" toasts still on screen (receipt issue 38), so the shared
 * layout can render them wherever the operator lands after a Confirm — the next receipt or the
 * register. The model attribute is simply ignored by pages and fragments that do not render it.
 */
@ControllerAdvice
class ConfirmToastAdvice {

  private final RecentConfirms recentConfirms;

  ConfirmToastAdvice(RecentConfirms recentConfirms) {
    this.recentConfirms = recentConfirms;
  }

  @ModelAttribute("confirmToasts")
  List<ConfirmToastView> confirmToasts(HttpServletRequest request) {
    return recentConfirms.visible(request);
  }
}
