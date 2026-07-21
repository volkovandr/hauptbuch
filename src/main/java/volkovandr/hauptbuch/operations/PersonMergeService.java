package volkovandr.hauptbuch.operations;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.joda.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.debts.CurrencyBalance;
import volkovandr.hauptbuch.debts.Person;
import volkovandr.hauptbuch.debts.PersonLeaf;
import volkovandr.hauptbuch.debts.PersonProvisioningService;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.operations.PersonMergeView.TargetOption;
import volkovandr.hauptbuch.operations.repository.PostingReassignmentRepository;
import volkovandr.hauptbuch.shared.MoneyFactory;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * The person-merge domain operation (plan stage 8f, data-model §7): fold a source person into a
 * target by moving every one of the source's per-currency debt leaves' postings onto the target's
 * matching-currency leaf, then soft-deleting the now-zeroed source. This is the only way to remove
 * a person who is owed or owes money — a non-zero person cannot be soft-deleted (data-model §7).
 *
 * <p>It lives in {@code operations}, the home of structural data-management ops (CLAUDE.md §3),
 * because the move is a direct reassignment of {@code posting.account_id} through {@link
 * PostingReassignmentRepository} — not a re-post through the ledger engine: merge changes only
 * <em>where</em> a historical leg is filed, never its amount, currency, or transaction, so the
 * engine's balance validation does not apply (the same stance subdivision and category deletion
 * take). Provisioning the target's leaves stays in {@code debts} ({@link
 * PersonProvisioningService}); this service orchestrates.
 *
 * <p>The whole merge is one transaction: either every currency moves and the source is retired, or
 * nothing does. After the moves the source's balances are all zero, so retiring it goes through
 * {@link PersonService#softDeleteIfZeroBalance} — which doubles as a post-condition check that the
 * fold left nothing behind.
 */
@Service
class PersonMergeService {

  private final PersonService personService;
  private final PersonProvisioningService personProvisioningService;
  private final PostingReassignmentRepository postingReassignmentRepository;
  private final SettingsService settingsService;

  PersonMergeService(
      PersonService personService,
      PersonProvisioningService personProvisioningService,
      PostingReassignmentRepository postingReassignmentRepository,
      SettingsService settingsService) {
    this.personService = personService;
    this.personProvisioningService = personProvisioningService;
    this.postingReassignmentRepository = postingReassignmentRepository;
    this.settingsService = settingsService;
  }

  /**
   * Assemble the merge form for the source person: their live positions as directional sentences
   * and the live persons they can be folded into (everyone live but themselves).
   *
   * @throws IllegalArgumentException if the person is not live
   */
  PersonMergeView assemble(long personId) {
    Person source =
        personService
            .findById(personId)
            .orElseThrow(() -> new IllegalArgumentException("No person with id " + personId));
    List<TargetOption> targets =
        personService.findAllLive().stream()
            .filter(p -> p.personId() != personId)
            .map(p -> new TargetOption(p.personId(), p.name()))
            .toList();
    return new PersonMergeView(personId, source.name(), positions(source), targets);
  }

  /**
   * Merge the source person into the target: reassign every source leaf's postings onto the
   * target's leaf in the same currency (provisioning that leaf if the target has none yet), then
   * soft-delete the source. Atomic — the whole fold is one transaction.
   *
   * @throws IllegalArgumentException if source and target are the same person, or either is not a
   *     live person
   */
  @Transactional
  void merge(long sourcePersonId, long targetPersonId) {
    if (sourcePersonId == targetPersonId) {
      throw new IllegalArgumentException("Cannot merge a person into themselves");
    }
    requireLive(sourcePersonId, "source");
    requireLive(targetPersonId, "target");

    for (PersonLeaf leaf : personService.leavesOf(sourcePersonId)) {
      Account targetLeaf =
          personProvisioningService.ensureLeaf(targetPersonId, leaf.currencyCode());
      postingReassignmentRepository.reassignPostings(leaf.accountId(), targetLeaf.accountId());
    }

    // Every source leaf is now empty, so this succeeds; it also asserts the fold left nothing
    // behind.
    personService.softDeleteIfZeroBalance(sourcePersonId);
  }

  private void requireLive(long personId, String role) {
    if (personService.findById(personId).isEmpty()) {
      throw new IllegalArgumentException("No live " + role + " person with id " + personId);
    }
  }

  /** The source's current per-currency positions as directional sentences, base currency aware. */
  private List<String> positions(Person source) {
    String base = settingsService.baseCurrency().orElse("");
    List<String> positions = new ArrayList<>();
    for (CurrencyBalance balance : personService.balancesOf(source.personId())) {
      if (balance.signedBalance().signum() == 0) {
        continue;
      }
      positions.add(sentence(source.name(), balance, base));
    }
    return positions;
  }

  /** One currency's directional wording (mirrors the settle-up summary and the People overview). */
  private static String sentence(String personName, CurrencyBalance balance, String base) {
    BigDecimal signed = balance.signedBalance();
    Money magnitude = MoneyFactory.of(signed.abs(), balance.currencyCode());
    String display = MoneyFormat.display(magnitude, base);
    return signed.signum() < 0
        ? "You owe " + personName + " " + display
        : personName + " owes you " + display;
  }
}
