package volkovandr.hauptbuch.accounts;

/**
 * One account of the <em>post-to set</em> (issue transaction-register-ui/25): an open, real own
 * account a posting may hit, paired with its full root-to-leaf path so a picker can show where it
 * sits when a native control cannot indent.
 *
 * @param account the posting-leaf account
 * @param path its ancestors' names and its own, root first, joined by {@code " - "}
 */
public record PostToAccount(Account account, String path) {

  /** The label every own-account picker offers: {@code BankAaa - Credit card (EUR)}. */
  public String entryLabel() {
    return AccountEntryLabel.format(path, account.currencyCode());
  }
}
