package volkovandr.hauptbuch.accounts;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.accounts.repository.AccountRepository;

/**
 * The account editor's re-parent move (issue account-management/03): change a managed account's
 * parent, or clear it to the top level. Its own concern, kept out of {@link AccountService} —
 * structural hierarchy mutation with cycle detection, not the account CRUD and engine reads that
 * service carries.
 *
 * <p>The parent rules are not restated here: {@link AccountService#requireManageable} and {@link
 * AccountService#requireUsableParent} (both same-module, package-private) are the ones the create
 * path already enforces. This class adds only what a <em>move</em> needs on top — the auto-managed
 * leaf and closed-account guards on the new parent, and the cycle check.
 *
 * <p>Its own class rather than more methods on {@link AccountService}: that service already sits at
 * the static-analysis ceiling for class size, and this module already factors specific concerns
 * into collaborators ({@code OpeningBalanceRecorder}, {@code PayingAccountDetector}).
 */
@Service
class AccountReparenter {

  private static final Logger LOG = LoggerFactory.getLogger(AccountReparenter.class);

  private final AccountService accountService;
  private final AccountRepository accountRepository;

  AccountReparenter(AccountService accountService, AccountRepository accountRepository) {
    this.accountService = accountService;
    this.accountRepository = accountRepository;
  }

  /**
   * Move {@code accountId} under {@code newParentId}, or to the top level when it is null. The
   * account keeps every posting and its running balance is unaffected — it stays a leaf, only its
   * position changes.
   *
   * <p>Refused, with a message naming the reason, when the account is a per-person debt leaf (moved
   * through its owner, not here), when the new parent is a different type, already holds postings
   * (leaves-only, data-model §5), is closed, or is itself an auto-managed leaf, or when the new
   * parent is the account itself or one of its descendants (a cycle). The candidate dropdown
   * already excludes all of these — the checks here defend the endpoint against a hand-built
   * request.
   *
   * @throws IllegalArgumentException naming the reason when any rule is broken
   */
  @Transactional
  void changeParent(long accountId, Long newParentId) {
    Account account = accountService.requireManageable(accountId);
    if (account.personLeaf()) {
      throw new IllegalArgumentException(
          "'" + account.name() + "' is an auto-managed debt leaf — move it through its owner");
    }
    if (newParentId != null) {
      requireUsableNewParent(account, newParentId);
    }
    accountRepository.updateParent(accountId, newParentId);
    LOG.info(
        "Account re-parented: id={}, name={}, parentId={}",
        account.accountId(),
        account.name(),
        newParentId);
  }

  /**
   * The move-only rules on the chosen parent, on top of {@link AccountService#requireUsableParent}.
   */
  private void requireUsableNewParent(Account account, long newParentId) {
    Account parent = accountService.requireUsableParent(newParentId, account.type());
    if (parent.personLeaf() || parent.currencyLeaf()) {
      throw new IllegalArgumentException(
          "'" + parent.name() + "' is an auto-managed leaf and cannot take child accounts");
    }
    if (parent.closedAt() != null) {
      throw new IllegalArgumentException(
          "'" + parent.name() + "' is closed — reopen it before nesting accounts under it");
    }
    if (accountRepository.findSubtreeAccountIds(account.accountId()).contains(newParentId)) {
      throw new IllegalArgumentException(
          "'"
              + parent.name()
              + "' is '"
              + account.name()
              + "' itself or one of its descendants — that move would make a cycle");
    }
  }

  /**
   * The accounts the editor may offer as a new parent for {@code accountId}: the same-type {@link
   * AccountService#parentOptions} (live, open, childless-or-never-posted) minus the account itself
   * and its own descendants, which a move must not descend into.
   *
   * @throws IllegalArgumentException if the account is not one the screen manages
   */
  List<Account> parentCandidatesFor(long accountId) {
    Account account = accountService.requireManageable(accountId);
    Set<Long> subtree = new HashSet<>(accountRepository.findSubtreeAccountIds(accountId));
    return accountService.parentOptions().stream()
        .filter(a -> a.type().equals(account.type()) && !subtree.contains(a.accountId()))
        .toList();
  }
}
