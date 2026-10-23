package volkovandr.hauptbuch.receipts;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.ledger.Settings;

/**
 * The receipts just confirmed, held in the HTTP session so a "Confirmed!" toast (receipt issue 38)
 * can be shown on whichever page the Confirm redirects to — Confirm ends in a full page load, so
 * nothing held in the page itself would survive.
 *
 * <p>Session state, not a table: a toast lives seconds, and a lost one costs nothing. Entries older
 * than the configured lifetime are dropped on every touch, so the list cannot grow.
 */
@Component
class RecentConfirms {

  private static final String SESSION_KEY = RecentConfirms.class.getName();
  private static final DateTimeFormatter SHOWN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  private final Clock clock;
  private final Object lock = new Object();

  RecentConfirms(Clock clock) {
    this.clock = clock;
  }

  /**
   * Remember a successful Confirm. Records nothing when the toast lifetime is 0 (toasts off).
   *
   * @param settings supplies the toast lifetime and the base currency the amount is shown against
   */
  void record(HttpSession session, long receiptId, ReceiptEditorForm form, Settings settings) {
    if (settings.confirmToastSeconds() <= 0) {
      return;
    }
    Instant now = clock.instant();
    ConfirmToast toast =
        new ConfirmToast(
            receiptId,
            shownDate(form.date()),
            form.payeeText() == null ? "" : form.payeeText().strip(),
            shownAmount(form, settings.baseCurrency()),
            now);
    synchronized (lock) {
      List<ConfirmToast> kept = entries(session);
      kept.removeIf(old -> expired(old, now, settings.confirmToastSeconds()));
      kept.add(toast);
    }
  }

  /** The toasts still within {@code lifetimeSeconds}, newest first; empty without a session. */
  List<ConfirmToastView> visible(HttpServletRequest request, int lifetimeSeconds) {
    HttpSession session = request.getSession(false);
    if (session == null || lifetimeSeconds <= 0) {
      return List.of();
    }
    Instant now = clock.instant();
    synchronized (lock) {
      List<ConfirmToast> kept = entries(session);
      kept.removeIf(old -> expired(old, now, lifetimeSeconds));
      List<ConfirmToast> newestFirst = new ArrayList<>(kept);
      newestFirst.sort(Comparator.comparing(ConfirmToast::confirmedAt).reversed());
      return newestFirst.stream()
          .map(
              t ->
                  new ConfirmToastView(
                      t.receiptId(),
                      t.date(),
                      t.payee(),
                      t.amount(),
                      lifetimeSeconds,
                      Duration.between(t.confirmedAt(), now).toMillis() / 1000.0))
          .toList();
    }
  }

  private static boolean expired(ConfirmToast toast, Instant now, int lifetimeSeconds) {
    return Duration.between(toast.confirmedAt(), now).compareTo(Duration.ofSeconds(lifetimeSeconds))
        >= 0;
  }

  @SuppressWarnings("unchecked")
  private static List<ConfirmToast> entries(HttpSession session) {
    Object held = session.getAttribute(SESSION_KEY);
    if (held == null) {
      held = new CopyOnWriteArrayList<ConfirmToast>();
      session.setAttribute(SESSION_KEY, held);
    }
    return (List<ConfirmToast>) held;
  }

  private static String shownDate(String isoDate) {
    try {
      return LocalDate.parse(isoDate).format(SHOWN_DATE);
    } catch (DateTimeParseException | NullPointerException e) {
      return isoDate == null ? "" : isoDate;
    }
  }

  /** The typed total, bare in the base currency and suffixed with its code otherwise. */
  private static String shownAmount(ReceiptEditorForm form, String baseCurrency) {
    String total = form.total() == null ? "" : form.total().strip();
    String currency = form.currencyCode();
    boolean foreign = currency != null && !currency.isBlank() && !currency.equals(baseCurrency);
    return foreign ? total + " " + currency : total;
  }
}
