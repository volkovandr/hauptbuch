package volkovandr.hauptbuch.operations;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.ledger.TransactionTag;

/**
 * Resolves a split's tag ids to their chip pills (register §3.6, plan stage 7e.3). The split panel
 * form carries only tag <em>ids</em> (one hidden input per pill), so the header's and each line's
 * chips are re-rendered from the shared {@code tag} vocabulary on every server round-trip.
 * Extracted from {@link SplitPanelAssembler} so the panel assembler stays focused on the split's
 * readout math and neither class reaches over the coupling/complexity thresholds.
 */
@Component
class SplitTagPills {

  private final LedgerService ledgerService;

  SplitTagPills(LedgerService ledgerService) {
    this.ledgerService = ledgerService;
  }

  /**
   * The labels of every tag id in play on a form — the header's plus all the lines' — in one
   * lookup, so a panel render can turn each chip id into a pill. A {@link LinkedHashSet}
   * de-duplicates ids shared across legs (a transaction-level tag sits on the header and every
   * line) before the query.
   */
  Map<Long, String> labelsFor(SplitForm form) {
    Set<Long> ids = new LinkedHashSet<>(orEmpty(form.tagId()));
    if (form.lineTagIds() != null) {
      for (List<Long> lineTags : form.lineTagIds()) {
        if (lineTags != null) {
          ids.addAll(lineTags);
        }
      }
    }
    return ledgerService.labelsForTagIds(ids);
  }

  /** The chip pills for a leg's tag ids, in entry order, skipping any id with no label. */
  List<TransactionTag> pills(List<Long> ids, Map<Long, String> labels) {
    List<TransactionTag> pills = new ArrayList<>();
    for (Long id : orEmpty(ids)) {
      String label = labels.get(id);
      if (label != null) {
        pills.add(new TransactionTag(id, label));
      }
    }
    return pills;
  }

  /**
   * A leg's tag ids resolved to pills on their own (register §3.6) — the split's cancel path
   * carries the header tags back to the dock (a fresh label lookup, off the hot re-render path).
   * Prefer {@link #labelsFor} + {@link #pills} in the panel, which batch every leg's ids into one
   * query.
   */
  List<TransactionTag> resolvePills(List<Long> ids) {
    return pills(ids, ledgerService.labelsForTagIds(orEmpty(ids)));
  }

  private static List<Long> orEmpty(List<Long> ids) {
    return ids == null ? List.of() : ids;
  }
}
