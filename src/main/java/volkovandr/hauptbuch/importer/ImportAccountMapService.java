package volkovandr.hauptbuch.importer;

import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountDraft;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.debts.PersonProvisioningService;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.importer.repository.ImportAccountRepository;
import volkovandr.hauptbuch.ledger.CurrencyService;

/**
 * The account map (import.md §5.1, §5.4; plan c1/c2): resolves every Money account name a staged
 * file mentions to a target — an <strong>existing</strong> Hauptbuch account, a
 * <strong>new</strong> one opened here (type proposed from the file header, currency chosen since
 * QIF carries none), or a <strong>person</strong> (§5.4). The map is many-to-one — several Money
 * names may target one account, which is how a merge and the junk-account cleanup are done.
 *
 * <p>Also owns the {@code expect-file} flag per Money account name — "is this account's own export
 * still awaited?" — which the commit gate reads (§9). A staged file does not clear it: it is a
 * per-account, recorded, visible status the owner changes by hand (§5.1), the gate's only escape
 * hatch for a counterparty whose own export is not coming (§6.4).
 *
 * <p>This class is the domain <em>mutations</em>; {@link ImportAccountMapPanel} assembles the read
 * model the review renders (the same render-model-assembler shape as {@link ImportReviewService}).
 * The opening-balance reconciliation (§5.1) lands in c3. Nothing here writes to {@code transaction}
 * or {@code posting} — the campaign still commits atomically at the end (f2). Opening a new
 * account, and resolving a person target through {@link PersonProvisioningService#ensureLeaf} to
 * that person's per-currency leaf, are real {@code accounts}/{@code debts} operations done now so
 * the map holds a concrete account id: a person-mapped row is then an <em>ordinary account id</em>
 * and f2 books it exactly as a {@code for}-sigil entry does (§5.4 — "personhood exists only in the
 * map").
 */
@Service
public class ImportAccountMapService {

  private static final Logger LOG = LoggerFactory.getLogger(ImportAccountMapService.class);

  /** The account types a Money account may map to — a real account, never a category (§5.1). */
  private static final List<String> MAPPABLE_TYPES = List.of("asset", "liability");

  private final ImportSessionService importSessionService;
  private final ImportAccountRepository importAccountRepository;
  private final AccountService accountService;
  private final PersonService personService;
  private final PersonProvisioningService personProvisioningService;
  private final CurrencyService currencyService;

  ImportAccountMapService(
      ImportSessionService importSessionService,
      ImportAccountRepository importAccountRepository,
      AccountService accountService,
      PersonService personService,
      PersonProvisioningService personProvisioningService,
      CurrencyService currencyService) {
    this.importSessionService = importSessionService;
    this.importAccountRepository = importAccountRepository;
    this.accountService = accountService;
    this.personService = personService;
    this.personProvisioningService = personProvisioningService;
    this.currencyService = currencyService;
  }

  /**
   * Map a Money account name to an existing Hauptbuch account (§5.1). The target must be a live
   * asset or liability account — a category or a per-person leaf is not a mapping target here (a
   * person is c2).
   *
   * @throws IllegalStateException if no campaign is open
   * @throws IllegalArgumentException if the row is not in the open campaign, or the account does
   *     not exist or is not mappable
   */
  @Transactional
  public void mapToExisting(long importAccountId, long accountId) {
    ImportAccount row = requireRowInOpenSession(importAccountId);
    Account target =
        accountService
            .findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("No account with id " + accountId));
    if (!MAPPABLE_TYPES.contains(target.type()) || target.personLeaf() || target.currencyLeaf()) {
      throw new IllegalArgumentException(
          "\"" + row.moneyAccountName() + "\" must map to an asset or liability account");
    }
    importAccountRepository.mapToAccount(importAccountId, accountId, null);
    LOG.info(
        "Import account \"{}\" mapped to account {} ({})",
        row.moneyAccountName(),
        accountId,
        target.name());
  }

  /**
   * Open a new account for a Money account name and map to it (§5.1). Type is {@code asset} or
   * {@code liability} (proposed from the file header, overridable); the currency is required since
   * QIF carries none. The new account has no opening balance — c3 reconciles that.
   *
   * @throws IllegalStateException if no campaign is open
   * @throws IllegalArgumentException if the row is not in the open campaign, the currency is
   *     missing, or {@link AccountService#openAccount} rejects the draft
   */
  @Transactional
  public void mapToNew(long importAccountId, String name, String type, String currencyCode) {
    ImportAccount row = requireRowInOpenSession(importAccountId);
    requireKnownCurrency(currencyCode, row.moneyAccountName());
    Account created =
        accountService.openAccount(new AccountDraft(name, type, null, currencyCode, null, null));
    importAccountRepository.mapToAccount(importAccountId, created.accountId(), currencyCode);
    LOG.info(
        "Import account \"{}\" mapped to new account {} ({})",
        row.moneyAccountName(),
        created.accountId(),
        created.name());
  }

  /**
   * Map a Money account name to a <strong>person</strong> (§5.4) — money the owner lent to or
   * borrowed from someone, which Money records as an ordinary account. Resolves through {@link
   * PersonProvisioningService#ensureLeaf} to that person's per-currency leaf and maps the row to
   * that leaf's account id, so from here it is an ordinary account target (§5.4). Targets an
   * existing live person by id, or a person named in {@code newPersonName} (created if absent; a
   * name matching only a soft-deleted person yields a fresh distinct person — {@code revive} is
   * {@code false} because the review has no place for a revival prompt). The currency selects the
   * leaf and is required — QIF carries none, and a cross-currency transfer to the person must land
   * on the right leaf.
   *
   * @throws IllegalStateException if no campaign is open
   * @throws IllegalArgumentException if the row is not in the open campaign, the currency is
   *     missing or unknown, neither a person id nor a new name was given, the person id is unknown,
   *     or the name is ambiguous among live persons
   */
  @Transactional
  public void mapToPerson(
      long importAccountId, Long personId, String newPersonName, String currencyCode) {
    ImportAccount row = requireRowInOpenSession(importAccountId);
    requireKnownCurrency(currencyCode, row.moneyAccountName());
    Account leaf;
    if (personId != null) {
      personService
          .findById(personId)
          .orElseThrow(() -> new IllegalArgumentException("No live person with id " + personId));
      leaf = personProvisioningService.ensureLeaf(personId, currencyCode);
    } else if (newPersonName != null && !newPersonName.isBlank()) {
      leaf = personProvisioningService.ensureLeaf(newPersonName, currencyCode, false);
    } else {
      throw new IllegalArgumentException(
          "Choose an existing person or name a new one for \"" + row.moneyAccountName() + "\"");
    }
    importAccountRepository.mapToAccount(importAccountId, leaf.accountId(), currencyCode);
    LOG.info(
        "Import account \"{}\" mapped to person leaf {} ({})",
        row.moneyAccountName(),
        leaf.accountId(),
        currencyCode);
  }

  /**
   * Set the {@code expect-file} flag on a map row (§5.1, §6.4) — "am I still waiting for this
   * account's own export?". The owner clears a counterparty account's flag to accept its transfers
   * as the one file states them, or re-sets it to lock the gate again.
   *
   * @throws IllegalStateException if no campaign is open
   * @throws IllegalArgumentException if the row is not in the open campaign
   */
  @Transactional
  public void setExpectFile(long importAccountId, boolean expectFile) {
    ImportAccount row = requireRowInOpenSession(importAccountId);
    importAccountRepository.setExpectFile(importAccountId, expectFile);
    LOG.debug("Import account \"{}\" expect-file set to {}", row.moneyAccountName(), expectFile);
  }

  /**
   * Record the opening-balance reconciliation for one map row (import.md §5.1; plan c3). Money's
   * opening balance is a self-transfer and the target Hauptbuch account usually already has one —
   * the owner picks the winner: {@code keep_hauptbuch} drops Money's, {@code take_money} voids
   * Hauptbuch's own at commit and books Money's, {@code override} books {@code amount}. Only the
   * decision is stored; f2 acts on it. Nothing here writes to {@code transaction} / {@code
   * posting}.
   *
   * @throws IllegalStateException if no campaign is open
   * @throws IllegalArgumentException if the row is not in the open campaign, the choice is not one
   *     of the three, or {@code override} is chosen without an amount
   */
  @Transactional
  public void reconcileOpeningBalance(long importAccountId, String choice, BigDecimal amount) {
    ImportAccount row = requireRowInOpenSession(importAccountId);
    if (!OpeningBalanceChoice.isValid(choice)) {
      throw new IllegalArgumentException("Unrecognised opening-balance choice \"" + choice + "\"");
    }
    boolean override = OpeningBalanceChoice.OVERRIDE.equals(choice);
    if (override && amount == null) {
      throw new IllegalArgumentException(
          "Type an amount to override the opening balance for \"" + row.moneyAccountName() + "\"");
    }
    BigDecimal storedAmount = override ? amount : null;
    importAccountRepository.setOpeningBalanceChoice(importAccountId, choice, storedAmount);
    LOG.debug(
        "Import account \"{}\" opening-balance choice set to {}", row.moneyAccountName(), choice);
  }

  private void requireKnownCurrency(String currencyCode, String moneyAccountName) {
    if (currencyCode == null || currencyCode.isBlank()) {
      throw new IllegalArgumentException("Choose a currency for \"" + moneyAccountName + "\"");
    }
    if (!currencyService.exists(currencyCode)) {
      // AccountService.openAccount does not validate the currency — an unknown code would surface
      // only as a FK violation (an HTTP 500), so check it here where the message can be friendly.
      throw new IllegalArgumentException("Unknown currency \"" + currencyCode + "\"");
    }
  }

  private ImportAccount requireRowInOpenSession(long importAccountId) {
    long sessionId =
        importSessionService
            .currentSession()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No import session is open — start one before mapping accounts."))
            .importSessionId();
    return importAccountRepository.findBySession(sessionId).stream()
        .filter(row -> row.importAccountId() == importAccountId)
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "No account-map row " + importAccountId + " in the open campaign"));
  }
}
