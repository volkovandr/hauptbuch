package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.ledger.RegisterRowView.CategoryChip;
import volkovandr.hauptbuch.ledger.repository.RegisterRepository;

/**
 * Unit tier (plan §1.5): {@link RegisterRowRenderer}'s presentation logic with the repository
 * mocked — the Category-cell summarisation (register §2.6), the same-hue zebra alternation (§2.8),
 * German money formatting (§2.9), and the income/negative/pending flags (§2.8/§2.10). The
 * SQL-resident half (running balance, filters, leg lookup) is verified against real Postgres in
 * {@link RegisterSqlLogicTest}.
 */
@ExtendWith(MockitoExtension.class)
class RegisterRowRendererTest {

  private static final String EUR = "EUR";
  private static final String CHF = "CHF";
  private static final String ASSET = "asset";
  private static final String EXPENSE = "expense";
  private static final String CONFIRMED = "confirmed";
  private static final long CASH = 10L;
  private static final long GIRO = 11L;

  @Mock private RegisterRepository registerRepository;

  private RegisterRowRenderer renderer;

  @BeforeEach
  void setUp() {
    renderer = new RegisterRowRenderer(registerRepository);
  }

  private RegisterRow row(
      long postingId, long txnId, long accountId, String currency, String amount) {
    return new RegisterRow(
        postingId,
        txnId,
        LocalDate.parse("2026-01-01"),
        accountId,
        "Cash",
        210,
        currency,
        EUR.equals(currency),
        null,
        new BigDecimal(amount),
        new BigDecimal(amount),
        CONFIRMED,
        "unreconciled");
  }

  private RegisterCounterpartLeg leg(long txnId, String name, String type, String amount) {
    return leg(txnId, 99L, name, type, amount);
  }

  private RegisterCounterpartLeg leg(
      long txnId, long accountId, String name, String type, String amount) {
    return new RegisterCounterpartLeg(txnId, accountId, name, type, new BigDecimal(amount));
  }

  private void stubLegs(RegisterCounterpartLeg... legs) {
    when(registerRepository.findTransactionLegs(anyList())).thenReturn(List.of(legs));
  }

  private RegisterRowView renderOne(RegisterRow row) {
    return renderer.render(List.of(row)).get(0);
  }

  // ── Category cell ─────────────────────────────────────────────────────────

  @Test
  void singleExpenseCategoryRendersOneChipNoOverflow() {
    stubLegs(leg(100L, "Food", EXPENSE, "20.00"));

    RegisterRowView view = renderOne(row(1L, 100L, CASH, EUR, "-20.00"));

    assertThat(view.category().chips()).containsExactly(new CategoryChip("Food", false, false));
    assertThat(view.category().overflow()).isZero();
  }

  @Test
  void splitOverThreeCategoriesShowsTopThreeThenOverflow() {
    // The SQL returns legs biggest-magnitude first; the cell keeps that order.
    stubLegs(
        leg(100L, "Food", EXPENSE, "30.00"),
        leg(100L, "Fun", EXPENSE, "15.00"),
        leg(100L, "Travel", EXPENSE, "10.00"),
        leg(100L, "Misc", EXPENSE, "5.00"));

    RegisterRowView view = renderOne(row(1L, 100L, CASH, EUR, "-60.00"));

    assertThat(view.category().chips())
        .extracting(CategoryChip::label)
        .containsExactly("Food", "Fun", "Travel");
    assertThat(view.category().overflow()).isEqualTo(1);
  }

  @Test
  void ownAccountCounterpartRendersAsInboundTransfer() {
    // Cash +200 came from Giro (Giro −200, a credit) → the chip is an inbound transfer, ← Giro.
    stubLegs(leg(100L, "Giro", ASSET, "-200.00"));

    RegisterRowView view = renderOne(row(1L, 100L, CASH, EUR, "200.00"));

    assertThat(view.category().chips()).containsExactly(new CategoryChip("Giro", true, true));
  }

  @Test
  void transferBetweenTwoViewedAccountsShowsEachOtherWithOppositeArrows() {
    // A transfer Cash −20 / Giro +20, both accounts in view: two rows, each showing the other leg
    // (register §2.6, plan stage 7d.3) — the bug this fixes left both Category cells empty because
    // the counterpart was a viewed account.
    RegisterCounterpartLeg cashLeg = leg(100L, CASH, "Cash", ASSET, "-20.00");
    RegisterCounterpartLeg giroLeg = leg(100L, GIRO, "Giro", ASSET, "20.00");
    when(registerRepository.findTransactionLegs(anyList())).thenReturn(List.of(cashLeg, giroLeg));

    List<RegisterRowView> views =
        renderer.render(
            List.of(row(1L, 100L, CASH, EUR, "-20.00"), row(2L, 100L, GIRO, EUR, "20.00")));

    // Cash's row: money went out to Giro → → Giro (outbound). Giro's row: money came from Cash →
    // ← Cash (inbound).
    assertThat(views.get(0).category().chips())
        .containsExactly(new CategoryChip("Giro", true, false));
    assertThat(views.get(1).category().chips())
        .containsExactly(new CategoryChip("Cash", true, true));
  }

  // ── Formatting, colour, lifecycle ─────────────────────────────────────────

  @Test
  void baseAmountRendersBareAndIncomeIsFlagged() {
    stubLegs();

    RegisterRowView view = renderOne(row(1L, 100L, CASH, EUR, "1234.56"));

    assertThat(view.amountDisplay()).isEqualTo("1.234,56");
    assertThat(view.balanceDisplay()).isEqualTo("1.234,56");
    assertThat(view.income()).isTrue();
    assertThat(view.negativeBalance()).isFalse();
  }

  @Test
  void nonBaseAmountCarriesItsCurrencySymbol() {
    stubLegs();

    RegisterRowView view = renderOne(row(1L, 100L, CASH, CHF, "-80.00"));

    assertThat(view.amountDisplay()).contains("CHF").contains("80,00");
    assertThat(view.negativeBalance()).isTrue();
  }

  @Test
  void pendingRowIsMutedWithNoBalance() {
    stubLegs();
    RegisterRow pending =
        new RegisterRow(
            1L,
            100L,
            LocalDate.parse("2026-01-01"),
            CASH,
            "Cash",
            210,
            EUR,
            true,
            null,
            new BigDecimal("-10.00"),
            new BigDecimal("-10.00"),
            "pending_review",
            "unreconciled");

    RegisterRowView view = renderOne(pending);

    assertThat(view.pending()).isTrue();
    assertThat(view.balanceDisplay()).isEqualTo("—");
    // A pending row does not colour its (absent) balance red even when the amount is negative.
    assertThat(view.negativeBalance()).isFalse();
  }

  @Test
  void zebraAlternatesPerAccountThreadNotPerScreenRow() {
    stubLegs();
    // Two Cash rows interleaved with a Giro row; the zebra follows each account's own count.
    List<RegisterRow> rows =
        List.of(
            row(1L, 100L, CASH, EUR, "-10.00"),
            row(2L, 101L, GIRO, EUR, "-40.00"),
            row(3L, 102L, CASH, EUR, "-5.00"));

    List<RegisterRowView> views = renderer.render(rows);

    // Cash: 1st light, 2nd dark. Giro: 1st light. Zebra is per-thread, so the middle (Giro) row
    // being light does not shift Cash's alternation.
    assertThat(views).extracting(RegisterRowView::zebraDark).containsExactly(false, false, true);
  }
}
