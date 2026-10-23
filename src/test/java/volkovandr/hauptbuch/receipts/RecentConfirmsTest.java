package volkovandr.hauptbuch.receipts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import volkovandr.hauptbuch.ledger.Settings;

/**
 * Unit tier: the session-held "Confirmed!" toasts (receipt issue 38) — what is remembered, how it
 * reads (newest first, date and amount as shown), and when it stops showing.
 */
class RecentConfirmsTest {

  private static final Instant START = Instant.parse("2026-08-03T10:00:00Z");
  private static final Settings THREE_SECONDS = new Settings("EUR", null, 3);

  private final MockHttpSession session = new MockHttpSession();
  private final MockHttpServletRequest request = requestOn(session);
  private Instant now = START;
  private final Clock clock =
      new Clock() {
        @Override
        public ZoneOffset getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return now;
        }
      };
  private final RecentConfirms recentConfirms = new RecentConfirms(clock);

  private static MockHttpServletRequest requestOn(MockHttpSession session) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSession(session);
    return request;
  }

  private static ReceiptEditorForm form(String date, String payee, String total, String currency) {
    return new ReceiptEditorForm(
        date, payee, 1L, currency, total, "", "", "", "", null, null, null, null, null, null, null,
        null, null, null, null, null);
  }

  private void advance(long millis) {
    now = now.plus(Duration.ofMillis(millis));
  }

  @Test
  void aRecordedConfirmShowsTheDatePayeeAndAmountAsTheOperatorSeesThem() {
    recentConfirms.record(
        session, 7, form("2026-08-03", " ShopAaa ", "42,14", "EUR"), THREE_SECONDS);

    List<ConfirmToastView> toasts = recentConfirms.visible(request, 3);

    assertThat(toasts).hasSize(1);
    ConfirmToastView toast = toasts.get(0);
    assertThat(toast.receiptId()).isEqualTo(7);
    assertThat(toast.date()).isEqualTo("03.08.2026");
    assertThat(toast.payee()).isEqualTo("ShopAaa");
    assertThat(toast.amount()).isEqualTo("42,14");
    assertThat(toast.durationSeconds()).isEqualTo(3);
    assertThat(toast.elapsedSeconds()).isZero();
  }

  @Test
  void aNonBaseCurrencyAmountCarriesItsCode() {
    recentConfirms.record(session, 7, form("2026-08-03", "ShopAaa", "9,50", "CHF"), THREE_SECONDS);

    assertThat(recentConfirms.visible(request, 3))
        .extracting(ConfirmToastView::amount)
        .containsExactly("9,50 CHF");
  }

  @Test
  void severalConfirmsListNewestFirstWithTheirOwnElapsedTime() {
    recentConfirms.record(session, 1, form("2026-08-03", "ShopAaa", "1,00", "EUR"), THREE_SECONDS);
    advance(1500);
    recentConfirms.record(session, 2, form("2026-08-03", "ShopBbb", "2,00", "EUR"), THREE_SECONDS);
    advance(500);

    List<ConfirmToastView> toasts = recentConfirms.visible(request, 3);

    assertThat(toasts).extracting(ConfirmToastView::receiptId).containsExactly(2L, 1L);
    assertThat(toasts).extracting(ConfirmToastView::elapsedSeconds).containsExactly(0.5, 2.0);
  }

  @Test
  void aToastStopsShowingOnceItsLifetimeHasPassed() {
    recentConfirms.record(session, 1, form("2026-08-03", "ShopAaa", "1,00", "EUR"), THREE_SECONDS);
    advance(2999);
    assertThat(recentConfirms.visible(request, 3)).hasSize(1);

    advance(1);

    assertThat(recentConfirms.visible(request, 3)).isEmpty();
  }

  @Test
  void aLifetimeOfZeroRecordsNothing() {
    recentConfirms.record(
        session, 1, form("2026-08-03", "ShopAaa", "1,00", "EUR"), new Settings("EUR", null, 0));

    assertThat(recentConfirms.visible(request, 3)).isEmpty();
  }

  @Test
  void aLifetimeOfZeroShowsNothingEvenIfSomethingWasRecordedEarlier() {
    recentConfirms.record(session, 1, form("2026-08-03", "ShopAaa", "1,00", "EUR"), THREE_SECONDS);

    assertThat(recentConfirms.visible(request, 0)).isEmpty();
  }

  @Test
  void recordingDropsEntriesThatAlreadyExpired() {
    recentConfirms.record(session, 1, form("2026-08-03", "ShopAaa", "1,00", "EUR"), THREE_SECONDS);
    advance(5000);
    recentConfirms.record(session, 2, form("2026-08-03", "ShopBbb", "2,00", "EUR"), THREE_SECONDS);

    assertThat(recentConfirms.visible(request, 60))
        .extracting(ConfirmToastView::receiptId)
        .containsExactly(2L);
  }

  @Test
  void aRequestWithoutASessionHasNoToastsAndDoesNotStartOne() {
    MockHttpServletRequest sessionless = new MockHttpServletRequest();

    assertThat(recentConfirms.visible(sessionless, 3)).isEmpty();
    assertThat(sessionless.getSession(false)).isNull();
  }

  @Test
  void aMissingPayeeAndAnUnparsableDateDoNotBreakTheToast() {
    recentConfirms.record(session, 1, form("not a date", null, "1,00", "EUR"), THREE_SECONDS);

    ConfirmToastView toast = recentConfirms.visible(request, 3).get(0);
    assertThat(toast.date()).isEqualTo("not a date");
    assertThat(toast.payee()).isEmpty();
  }
}
