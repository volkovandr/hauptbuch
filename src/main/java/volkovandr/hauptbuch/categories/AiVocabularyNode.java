package volkovandr.hauptbuch.categories;

import java.util.List;

/**
 * One node of the AI-facing category tree (data-model §13.3, plan stage 9d) — the curated
 * projection the receipt parser is shown instead of the raw taxonomy. Every node carries its
 * <em>effective</em> name (the alias if the operator set one, else the real name), so a group alias
 * has already renamed its children's paths by the time the tree is built. Hidden branches are
 * pruned: a node appears only if it is a visible leaf or an ancestor of one.
 *
 * <p>This is the public shape {@code AiVocabularyService#aiVocabulary()} returns for consumers
 * ({@code receipts}, stage 9e) to render into the prompt — never any balance or posting (ARCH-08),
 * only these operator-curated names and notes.
 *
 * @param name the effective name (alias or real)
 * @param type {@code income} or {@code expense}
 * @param note the operator's per-category AI note, or {@code null} if none
 * @param children the visible sub-nodes (empty for a leaf)
 */
public record AiVocabularyNode(
    String name, String type, String note, List<AiVocabularyNode> children) {

  /** Defensive copy so the tree is immutable to consumers (the house pattern for record lists). */
  public AiVocabularyNode {
    children = children == null ? List.of() : List.copyOf(children);
  }
}
