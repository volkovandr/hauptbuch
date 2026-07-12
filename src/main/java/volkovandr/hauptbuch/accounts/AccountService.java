package volkovandr.hauptbuch.accounts;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.accounts.repository.AccountRepository;

/**
 * Account definitions — the owning home of the {@code account} table since stage 6a (plan stage 6).
 * Two responsibilities:
 *
 * <ul>
 *   <li><strong>The read API the engine depends on</strong> ({@link #findById}, {@link
 *       #findLeafUnderParentNamed}, {@link #findParentAccountIds}) — the lookups {@code
 *       LedgerService} needs to validate legs (currency, leaves-only, system leaves), moved here
 *       from {@code ledger}'s stage-3 placement;
 *   <li><strong>Account management</strong> for the accounts screen: open (optionally with an
 *       opening balance, posted through the engine via {@link OpeningBalanceRecorder}), edit the
 *       free fields (name, hue), and close. The screen manages {@code asset} and {@code liability}
 *       accounts only — income/expense are categories (stage 6b) and equity is system plumbing, so
 *       both are guarded from edits here.
 * </ul>
 */
@Service
public class AccountService {

  /**
   * The stored register hues (register §2.8), as degrees on the HSL wheel, in assignment order —
   * spread around the wheel so the default account set lands on clearly distinct colours. A new
   * account gets the least-used hue, first of the palette on a tie.
   */
  static final List<Integer> HUE_PALETTE = List.of(210, 30, 140, 275, 0, 180, 330, 60, 250, 100);

  /** The account types the accounts screen manages (data-model §3.2 — the "Accounts" list). */
  private static final List<String> MANAGEABLE_TYPES = List.of("asset", "liability");

  private final AccountRepository accountRepository;

  /**
   * Resolved lazily: the engine-side implementation itself depends on this service for its account
   * lookups, so eager constructor injection would be a bean-construction cycle. {@link
   * ObjectProvider} defers resolution to first use, where the mutual construction is long over.
   */
  private final ObjectProvider<OpeningBalanceRecorder> openingBalanceRecorder;

  AccountService(
      AccountRepository accountRepository,
      ObjectProvider<OpeningBalanceRecorder> openingBalanceRecorder) {
    this.accountRepository = accountRepository;
    this.openingBalanceRecorder = openingBalanceRecorder;
  }

  /** Find an account by id. */
  public Optional<Account> findById(long accountId) {
    return accountRepository.findById(accountId);
  }

  /**
   * Resolve a system leaf by its parent's name and the leaf's currency (e.g. the {@code Opening
   * Balances} leaf for EUR). The seed (V2) creates exactly one such leaf per currency under the
   * named system parent.
   */
  public Optional<Account> findLeafUnderParentNamed(String parentName, String currencyCode) {
    return accountRepository.findLeafUnderParentNamed(parentName, currencyCode);
  }

  /** The account ids that are some other account's parent — i.e. the non-leaf accounts. */
  public List<Long> findParentAccountIds() {
    return accountRepository.findParentAccountIds();
  }

  /**
   * Resolve a top-level (parentless) system parent by name — e.g. the {@code Opening Balances} tree
   * the seed creates once. Used by the {@code createCurrency} operation to hang a new currency's
   * system leaf under the right parent (plan stage 6d).
   */
  public Optional<Account> findTopLevelByName(String name) {
    return accountRepository.findTopLevelByName(name);
  }

  /** The live children of an account, alphabetical by name. */
  public List<Account> findChildrenOf(long parentId) {
    return accountRepository.findChildrenOf(parentId);
  }

  /**
   * The account ids of a subtree — the given root and every live descendant to arbitrary depth
   * (data-model §5). Used by the category-deletion operation, which removes a whole subtree at once
   * (plan stage 6c).
   */
  public List<Long> findSubtreeAccountIds(long rootId) {
    return accountRepository.findSubtreeAccountIds(rootId);
  }

  /**
   * Soft-delete a set of accounts (plan stage 6c). Unlike {@link #closeAccount}, deletion is not a
   * reversible display state — the caller must have reassigned any postings away first. Structural
   * callers ({@code operations}) own their own validation; this is the mechanical stamp.
   *
   * @return the number of rows stamped
   */
  @Transactional
  public int softDelete(List<Long> accountIds) {
    return accountRepository.softDelete(accountIds);
  }

  /** Whether any live posting hits this account (leaves-only guard). */
  public boolean hasPostings(long accountId) {
    return accountRepository.hasPostings(accountId);
  }

  /**
   * Insert a fresh leaf account with no opening balance, hue, or open date — used by structural
   * operations that create accounts directly (e.g. category subdivision, {@code operations}
   * module).
   *
   * @return the persisted account
   */
  public Account insertLeaf(String name, String type, Long parentId, String currencyCode) {
    long accountId =
        accountRepository.insert(
            new Account(null, name, type, parentId, currencyCode, null, null, null, null, false));
    return accountRepository.findById(accountId).orElseThrow();
  }

  /**
   * Insert a per-currency leaf, marked as auto-managed by its parent (data-model §6.5) — hidden
   * from every picker and the categories screen, and carried along automatically when its parent is
   * subdivided, renamed, or deleted. Named after the bare currency code (e.g. {@code "EUR"}), never
   * the parent's name — the flag, not the name, is what marks it. Used only by {@code
   * CurrencyLeafService}.
   *
   * @return the persisted account
   */
  public Account insertCurrencyLeaf(String currencyCode, String type, long parentId) {
    long accountId =
        accountRepository.insert(
            new Account(
                null, currencyCode, type, parentId, currencyCode, null, null, null, null, true));
    return accountRepository.findById(accountId).orElseThrow();
  }

  /**
   * Re-parent an account — used only by the currency-leaf-aware subdivision operation to move a
   * category's existing per-currency leaves under its new catch-all sibling when the category
   * itself gains a real child (data-model §6.5). Not a user-facing edit.
   */
  @Transactional
  public void reparent(long accountId, long newParentId) {
    accountRepository.updateParent(accountId, newParentId);
  }

  /**
   * Rename any account, regardless of type. Unlike {@link #updateAccount}, this does not gate on
   * the accounts screen's managed types — callers (e.g. {@code categories}) own their own
   * manageability check first.
   *
   * @throws IllegalArgumentException if the account does not exist or the name is blank
   */
  @Transactional
  public void renameAccount(long accountId, String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("An account needs a name");
    }
    Account account =
        accountRepository
            .findById(accountId)
            .filter(a -> a.deletedAt() == null)
            .orElseThrow(() -> new IllegalArgumentException("No account with id " + accountId));
    accountRepository.updateNameAndHue(accountId, name, account.hue());
  }

  /** The live asset and liability accounts the accounts screen lists and manages. */
  public List<Account> manageableAccounts() {
    return accountRepository.findLiveByTypes(MANAGEABLE_TYPES);
  }

  /** The live accounts of the given types — the read other modules' screens list against. */
  public List<Account> findLiveByTypes(List<String> types) {
    return accountRepository.findLiveByTypes(types);
  }

  /**
   * The live accounts of the given types, each annotated with its true hierarchy depth and listed
   * depth-first (every node immediately followed by all of its descendants) — what a screen needs
   * to indent a multi-level tree correctly, unlike the flat {@link #findLiveByTypes}.
   */
  public List<AccountNode> findLiveByTypesWithDepth(List<String> types) {
    return accountRepository.findLiveByTypesWithDepth(types);
  }

  /**
   * The accounts the create form may offer as parents: live, open, managed accounts that are
   * already parents or have never been posted to — an account with postings can only become a
   * parent through the stage-6b subdivision operation (leaves-only, data-model §5).
   */
  public List<Account> parentOptions() {
    Set<Long> posted = new HashSet<>(accountRepository.findPostedAccountIds());
    Set<Long> parents = new HashSet<>(accountRepository.findParentAccountIds());
    return manageableAccounts().stream()
        .filter(a -> a.closedAt() == null)
        .filter(a -> parents.contains(a.accountId()) || !posted.contains(a.accountId()))
        .toList();
  }

  /**
   * Open a new account: validate the draft, assign the stored register hue (register §2.8), insert
   * the row and — when the draft carries a non-zero opening balance — post it as a real balanced
   * transaction through the engine (data-model T-DM-4), all atomically.
   *
   * @return the persisted account
   * @throws IllegalArgumentException if the draft violates a rule (blank name, unmanaged type, or
   *     an unusable parent)
   */
  @Transactional
  public Account openAccount(AccountDraft draft) {
    validateDraft(draft);
    LocalDate openedAt = draft.openedAt() == null ? LocalDate.now() : draft.openedAt();
    long accountId =
        accountRepository.insert(
            new Account(
                null,
                draft.name(),
                draft.type(),
                draft.parentId(),
                draft.currencyCode(),
                nextHue(),
                openedAt,
                null,
                null,
                false));

    if (draft.openingBalance() != null && draft.openingBalance().signum() != 0) {
      openingBalanceRecorder
          .getObject()
          .recordOpeningBalance(accountId, draft.openingBalance(), openedAt);
    }
    return accountRepository.findById(accountId).orElseThrow();
  }

  /**
   * Update an account's freely-editable fields: display name and stored hue. Type, currency, and
   * parent are immutable through the UI — postings inherit the account's currency, and re-parenting
   * a posted-to account would break leaves-only (data-model §5); the stage-6b subdivision operation
   * is the sanctioned path for that.
   *
   * @throws IllegalArgumentException if the account does not exist, is not one the screen manages,
   *     the name is blank, or the hue is off the colour wheel
   */
  @Transactional
  public void updateAccount(long accountId, String name, Integer hue) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("An account needs a name");
    }
    if (hue != null && (hue < 0 || hue > 359)) {
      throw new IllegalArgumentException("Hue must be a degree on the colour wheel (0–359)");
    }
    requireManageable(accountId);
    accountRepository.updateNameAndHue(accountId, name, hue);
  }

  /**
   * Close an account as of the given date (today when null). Closing is a display state, not a
   * delete: the account and its history stay, the screen files it under "Closed".
   *
   * @throws IllegalArgumentException if the account does not exist or is not one the screen manages
   * @throws IllegalStateException if the account is already closed
   */
  @Transactional
  public void closeAccount(long accountId, LocalDate closedAt) {
    requireManageable(accountId);
    LocalDate effective = closedAt == null ? LocalDate.now() : closedAt;
    if (accountRepository.close(accountId, effective) == 0) {
      throw new IllegalStateException("Account " + accountId + " is already closed");
    }
  }

  /**
   * Reopen a closed account: clears {@code closed_at}, putting it back into daily use. The reverse
   * of {@link #closeAccount} — closing is a display state, not a delete, so it is fully reversible.
   *
   * @throws IllegalArgumentException if the account does not exist or is not one the screen manages
   * @throws IllegalStateException if the account is not currently closed
   */
  @Transactional
  public void reopenAccount(long accountId) {
    requireManageable(accountId);
    if (accountRepository.reopen(accountId) == 0) {
      throw new IllegalStateException("Account " + accountId + " is not closed");
    }
  }

  /** The open-account rules: a name, a managed type, and — when given — a usable parent. */
  private void validateDraft(AccountDraft draft) {
    if (draft.name() == null || draft.name().isBlank()) {
      throw new IllegalArgumentException("An account needs a name");
    }
    if (!MANAGEABLE_TYPES.contains(draft.type())) {
      throw new IllegalArgumentException(
          "Account type must be one of " + MANAGEABLE_TYPES + ", not '" + draft.type() + "'");
    }
    if (draft.parentId() != null) {
      requireUsableParent(draft.parentId(), draft.type());
    }
  }

  /** The account, if it is live and of a type the accounts screen manages. */
  private Account requireManageable(long accountId) {
    return accountRepository
        .findById(accountId)
        .filter(a -> a.deletedAt() == null)
        .filter(a -> MANAGEABLE_TYPES.contains(a.type()))
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "No manageable account with id "
                        + accountId
                        + " — categories and system accounts are not edited here"));
  }

  /**
   * A parent must be a live, same-type account that has never been posted to: leaves-only
   * (data-model §5) means an account with postings can only become a parent through the stage-6b
   * subdivision operation, which reassigns its postings first.
   */
  private void requireUsableParent(long parentId, String childType) {
    Account parent =
        accountRepository
            .findById(parentId)
            .filter(a -> a.deletedAt() == null)
            .orElseThrow(
                () -> new IllegalArgumentException("No parent account with id " + parentId));
    if (!parent.type().equals(childType)) {
      throw new IllegalArgumentException(
          "Parent '" + parent.name() + "' is a " + parent.type() + " account, not " + childType);
    }
    if (accountRepository.hasPostings(parentId)) {
      throw new IllegalArgumentException(
          "'"
              + parent.name()
              + "' has postings and cannot become a parent (leaves-only, data-model §5); "
              + "subdividing a posted-to leaf arrives with stage 6b");
    }
  }

  /** The least-used palette hue among the managed accounts; first of the palette on a tie. */
  private int nextHue() {
    Map<Integer, Long> usage = new HashMap<>();
    for (Account account : manageableAccounts()) {
      if (account.hue() != null) {
        usage.merge(account.hue(), 1L, Long::sum);
      }
    }
    int best = HUE_PALETTE.get(0);
    long bestCount = Long.MAX_VALUE;
    for (int hue : HUE_PALETTE) {
      long count = usage.getOrDefault(hue, 0L);
      if (count < bestCount) {
        best = hue;
        bestCount = count;
      }
    }
    return best;
  }
}
