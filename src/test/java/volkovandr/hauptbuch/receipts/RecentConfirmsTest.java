package volkovandr.hauptbuch.receipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import volkovandr.hauptbuch.ledger.Settings;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Unit tier: the session-held "Confirmed!" toasts (receipt issue 38) — what is remembered, how it
 * reads (newest first, date and amount as shown), and when it stops showing.
 */
class RecentConfirmsTest {

  private static final Instant START = Instant.parse("2026-08-03T10:00:00Z");

  private final MockHttpSession session = new MockHttpSession();
  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final SettingsService settingsService = mock(SettingsService.class);
  private Instant now = START;
  private final Clock clock =
      new Clock() {
        @Override
        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return now;
        }
      };
  private final RecentConfirms recentConfirms = new RecentConfirms(clock, settingsService);

  RecentConfirmsTest() {
    request.setSession(session);
    lifetime(3);
  }

  private void lifetime(int seconds) {
    when(settingsService.get()).thenReturn(new Settings("EUR", null, seconds));
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
  void recordedConfirmShowsDatePayeeAndAmountAsOperatorSeesThem() {
    recentConfirms.record(session, 7, form("2026-08-03", " ShopAaa ", "42,14", "EUR"));

    List<ConfirmToastView> toasts = recentConfirms.visible(request);

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
  void nonBaseCurrencyAmountCarriesItsCode() {
    recentConfirms.record(session, 7, form("2026-08-03", "ShopAaa", "9,50", "CHF"));

    assertThat(recentConfirms.visible(request))
        .extracting(ConfirmToastView::amount)
        .containsExactly("9,50 CHF");
  }

  @Test
  void severalConfirmsListNewestFirstWithTheirOwnElapsedTime() {
    recentConfirms.record(session, 1, form("2026-08-03", "ShopAaa", "1,00", "EUR"));
    advance(1500);
    recentConfirms.record(session, 2, form("2026-08-03", "ShopBbb", "2,00", "EUR"));
    advance(500);

    List<ConfirmToastView> toasts = recentConfirms.visible(request);

    assertThat(toasts).extracting(ConfirmToastView::receiptId).containsExactly(2L, 1L);
    assertThat(toasts).extracting(ConfirmToastView::elapsedSeconds).containsExactly(0.5, 2.0);
  }

  @Test
  void toastStopsShowingOnceLifetimeHasPassed() {
    recentConfirms.record(session, 1, form("2026-08-03", "ShopAaa", "1,00", "EUR"));
    advance(2999);
    assertThat(recentConfirms.visible(request)).hasSize(1);

    advance(1);

    assertThat(recentConfirms.visible(request)).isEmpty();
  }

  @Test
  void lifetimeOfZeroRecordsNothing() {
    lifetime(0);
    recentConfirms.record(session, 1, form("2026-08-03", "ShopAaa", "1,00", "EUR"));
    lifetime(3);

    assertThat(recentConfirms.visible(request)).isEmpty();
  }

  @Test
  void lifetimeOfZeroShowsNothingEvenIfRecordedEarlier() {
    recentConfirms.record(session, 1, form("2026-08-03", "ShopAaa", "1,00", "EUR"));
    lifetime(0);

    assertThat(recentConfirms.visible(request)).isEmpty();
  }

  @Test
  void recordingDropsEntriesThatAlreadyExpired() {
    recentConfirms.record(session, 1, form("2026-08-03", "ShopAaa", "1,00", "EUR"));
    advance(5000);
    recentConfirms.record(session, 2, form("2026-08-03", "ShopBbb", "2,00", "EUR"));
    lifetime(60);

    assertThat(recentConfirms.visible(request))
        .extracting(ConfirmToastView::receiptId)
        .containsExactly(2L);
  }

  @Test
  void requestWithoutSessionHasNoToastsAndDoesNotStartOne() {
    MockHttpServletRequest sessionless = new MockHttpServletRequest();

    assertThat(recentConfirms.visible(sessionless)).isEmpty();
    assertThat(sessionless.getSession(false)).isNull();
  }

  @Test
  void missingPayeeAndUnparsableDateDoNotBreakToast() {
    recentConfirms.record(session, 1, form("not a date", null, "1,00", "EUR"));

    ConfirmToastView toast = recentConfirms.visible(request).get(0);
    assertThat(toast.date()).isEqualTo("not a date");
    assertThat(toast.payee()).isEmpty();
  }

  @Test
  void amountIsShownInGermanFormatHoweverItWasTyped() {
    recentConfirms.record(session, 1, form("2026-08-03", "ShopAaa", "1234.5", "EUR"));

    assertThat(recentConfirms.visible(request))
        .extracting(ConfirmToastView::amount)
        .containsExactly("1.234,50");
  }

  @Test
  void reconfirmingReceiptReplacesItsEarlierToast() {
    recentConfirms.record(session, 1, form("2026-08-03", "ShopAaa", "1,00", "EUR"));
    advance(1000);
    recentConfirms.record(session, 1, form("2026-08-03", "ShopAaa", "2,00", "EUR"));

    assertThat(recentConfirms.visible(request))
        .extracting(ConfirmToastView::amount)
        .containsExactly("2,00");
  }
}
