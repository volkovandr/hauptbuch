package volkovandr.hauptbuch.accounts;

import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.accounts.repository.AccountRepository;

/**
 * Resolves a parsed payment signal to a paying account (data-model §13.4, stage 9e). Its own small
 * component rather than a method on {@link AccountService}: the signal-parsing is a distinct
 * concern from account management, and the analyse worker uses it for both the paying account
 * ({@code transaction.account}) and per-item transfer targets, which share the signal vocabulary.
 *
 * <p>The word {@code cash}/{@code Bar} (any casing) resolves to the marked cash account; otherwise
 * the trailing four digits of a card slip resolve to the account carrying that last-4. Empty when
 * the signal is blank, names cash with no cash account marked, or its last-4 matches no (or more
 * than one) account — the operator then picks.
 */
@Component
public class PayingAccountDetector {

  private static final int CARD_LAST_LENGTH = 4;

  private final AccountRepository accountRepository;

  PayingAccountDetector(AccountRepository accountRepository) {
    this.accountRepository = accountRepository;
  }

  /**
   * Resolve the paying/transfer account for a parsed payment signal, or empty when none matches.
   */
  public Optional<Account> detect(String signal) {
    if (signal == null || signal.isBlank()) {
      return Optional.empty();
    }
    String lower = signal.toLowerCase(Locale.ROOT);
    if (lower.contains("cash") || lower.contains("bar")) {
      return accountRepository.findCashAccount();
    }
    String digits = signal.replaceAll("\\D", "");
    if (digits.length() < CARD_LAST_LENGTH) {
      return Optional.empty();
    }
    return accountRepository.findByCardLast4(digits.substring(digits.length() - CARD_LAST_LENGTH));
  }
}
