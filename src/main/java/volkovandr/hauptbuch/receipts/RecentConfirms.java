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
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.ledger.Settings;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * The receipts just confirmed, held in the HTTP session so a "Confirmed!" toast (receipt issue 38)
 * can be shown on whichever page the Confirm redirects to — Confirm ends in a full page load, so
 * nothing held in the page itself would survive.
 *
 * <p>Session state, not a table: a toast lives seconds, and a lost one costs nothing. Entries older
 * than the configured lifetime ({@link Settings#confirmToastSeconds()}) are dropped on every touch,
 * so the list cannot grow.
 */
@Component
class RecentConfirms {

  private static final String SESSION_KEY = RecentConfirms.class.getName();
  private static final DateTimeFormatter SHOWN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  private final Clock clock;
  private final SettingsService settingsService;
  private final ReentrantLock lock = new ReentrantLock();

  RecentConfirms(Clock clock, SettingsService settingsService) {
    this.clock = clock;
    this.settingsService = settingsService;
  }

  /** Remember a successful Confirm. Records nothing when the toast lifetime is 0 (toasts off). */
  void record(HttpSession session, long receiptId, ReceiptEditorForm form) {
    Settings settings = settingsService.get();
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
    lock.lock();
    try {
      List<ConfirmToast> kept = entries(session);
      kept.removeIf(old -> expired(old, now, settings.confirmToastSeconds()));
      kept.add(toast);
    } finally {
      lock.unlock();
    }
  }

  /** The toasts still within the configured lifetime, newest first; empty without a session. */
  List<ConfirmToastView> visible(HttpServletRequest request) {
    int lifetimeSeconds = settingsService.get().confirmToastSeconds();
    HttpSession session = request.getSession(false);
    if (session == null || lifetimeSeconds <= 0) {
      return List.of();
    }
    Instant now = clock.instant();
    lock.lock();
    try {
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
    } finally {
      lock.unlock();
    }
  }

  private static boolean expired(ConfirmToast toast, Instant now, int lifetimeSeconds) {
    return Duration.between(toast.confirmedAt(), now).compareTo(Duration.ofSeconds(lifetimeSeconds))
        >= 0;
  }

  @SuppressWarnings("unchecked")
  private static List<ConfirmToast> entries(HttpSession session) {
    Object held = session.getAttribute(SESSION_KEY);
    if (held != null) {
      return (List<ConfirmToast>) held;
    }
    List<ConfirmToast> fresh = new CopyOnWriteArrayList<>();
    session.setAttribute(SESSION_KEY, fresh);
    return fresh;
  }

  private static String shownDate(String isoDate) {
    if (isoDate == null) {
      return "";
    }
    try {
      return LocalDate.parse(isoDate).format(SHOWN_DATE);
    } catch (DateTimeParseException e) {
      return isoDate;
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
