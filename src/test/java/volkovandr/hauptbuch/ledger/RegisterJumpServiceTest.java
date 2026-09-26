package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.ledger.repository.RegisterRepository;
import volkovandr.hauptbuch.ledger.repository.TransactionRepository;

/**
 * Unit tier (CLAUDE.md §6): {@link RegisterJumpService} — the register filter a {@code selected=}
 * jump derives from a transaction (register §7; reporting.md §12's handoff). Which own leg leads is
 * the repository's SQL order, covered in {@code RegisterSqlLogicTest}.
 */
class RegisterJumpServiceTest {

  private static final LocalDate JAN_5 = LocalDate.of(2026, 1, 5);

  private final RegisterRepository registerRepository = mock();
  private final TransactionRepository transactionRepository = mock();
  private final RegisterJumpService service =
      new RegisterJumpService(registerRepository, transactionRepository);

  private static Transaction transaction(OffsetDateTime deletedAt) {
    return new Transaction(7L, JAN_5, null, null, "confirmed", null, null, deletedAt);
  }

  @Test
  void viewsTheLeadingOwnLegFromTheTransactionsDate() {
    when(registerRepository.findOwnLegs(7L))
        .thenReturn(List.of(new RegisterOwnLeg(JAN_5, 3L), new RegisterOwnLeg(JAN_5, 4L)));

    assertThat(service.filterForTransaction(7L))
        .contains(new RegisterFilter(List.of(3L), RegisterPicker.ALL, JAN_5, null, null));
  }

  @Test
  void viewsEveryAccountFromTheDateWhenTheTransactionHasNoOwnLeg() {
    // A category-to-category correction: nothing to pre-select, so the register opens unfiltered.
    when(registerRepository.findOwnLegs(7L)).thenReturn(List.of());
    when(transactionRepository.findById(7L)).thenReturn(Optional.of(transaction(null)));

    assertThat(service.filterForTransaction(7L))
        .contains(new RegisterFilter(List.of(), RegisterPicker.ALL, JAN_5, null, null));
  }

  @Test
  void jumpsNowhereForVoidedOrUnknownTransactions() {
    when(registerRepository.findOwnLegs(7L)).thenReturn(List.of());
    when(transactionRepository.findById(7L))
        .thenReturn(Optional.of(transaction(OffsetDateTime.now())));

    assertThat(service.filterForTransaction(7L)).isEmpty();
    assertThat(service.filterForTransaction(8L)).isEmpty();
  }
}
