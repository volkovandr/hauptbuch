package volkovandr.hauptbuch.importer;

import java.util.List;
import java.util.Set;

/**
 * The whole parsed QIF file (import.md §3/§4.1): the account type Money's header proposes, every
 * account name the file mentions, and every transaction. The type proposal is exactly that —
 * overridable at upload (§5.1); the file itself never says which Hauptbuch account it belongs to.
 *
 * @param proposedAccountType {@code "asset"} or {@code "liability"}, proposed from the {@code
 *     !Type:} header (the {@code account.type} vocabulary, data-model §3.2)
 * @param referencedAccountNames every Money account name the file mentions — the account it is for
 *     (stated by the owner, §4.1) plus every {@code [Account]} transfer counterparty, split legs
 *     included. This is the input to the account map (§5.1); the whole file is rejected before this
 *     is returned if any of these names was destroyed on export (§4.5)
 * @param transactions every parsed transaction, in file order
 */
public record ImportedFile(
    String proposedAccountType,
    Set<String> referencedAccountNames,
    List<ImportedTransaction> transactions) {

  /** Defensive copies of the collections. */
  public ImportedFile {
    referencedAccountNames =
        referencedAccountNames == null ? Set.of() : Set.copyOf(referencedAccountNames);
    transactions = transactions == null ? List.of() : List.copyOf(transactions);
  }
}
