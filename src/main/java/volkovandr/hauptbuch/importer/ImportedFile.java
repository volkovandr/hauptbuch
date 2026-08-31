package volkovandr.hauptbuch.importer;

import java.util.List;

/**
 * The whole parsed QIF file (import.md §3/§4.1): the account type Money's header proposes, and
 * every transaction. The proposal is exactly that — overridable at upload (§5.1); the file itself
 * never says which Hauptbuch account it belongs to.
 *
 * @param proposedAccountType {@code "asset"} or {@code "liability"}, proposed from the {@code
 *     !Type:} header (the {@code account.type} vocabulary, data-model §3.2)
 * @param transactions every parsed transaction, in file order
 */
public record ImportedFile(String proposedAccountType, List<ImportedTransaction> transactions) {

  /** Defensive copy of {@code transactions}. */
  public ImportedFile {
    transactions = transactions == null ? List.of() : List.copyOf(transactions);
  }
}
