package volkovandr.hauptbuch.analytics.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tier (CLAUDE.md §6): {@link NodeKey} — what one segment of an axis node's key names, so the
 * engine can pick the right children query for a real account/tag, the "Personal debts" node, or
 * one person beneath it (reporting issue 06).
 */
class NodeKeyTest {

  @Test
  void numericSegmentIsRealNode() {
    assertThat(NodeKey.parse("42")).isEqualTo(new NodeKey(NodeKey.Kind.NODE, 42));
  }

  @Test
  void personalSegmentIsPersonalDebtsNode() {
    assertThat(NodeKey.parse("personal").kind()).isEqualTo(NodeKey.Kind.PERSONAL_DEBTS);
  }

  @Test
  void personSegmentCarriesThePersonId() {
    assertThat(NodeKey.parse("person:7")).isEqualTo(new NodeKey(NodeKey.Kind.PERSON, 7));
    assertThat(NodeKey.personKey(7)).isEqualTo("person:7");
  }

  @Test
  void lastSegmentOfCompositeKeyNamesTheNode() {
    assertThat(NodeKey.ofLastSegment("1|10")).isEqualTo(new NodeKey(NodeKey.Kind.NODE, 10));
    assertThat(NodeKey.ofLastSegment("personal|person:7"))
        .isEqualTo(new NodeKey(NodeKey.Kind.PERSON, 7));
    assertThat(NodeKey.ofLastSegment("personal|person:7|31"))
        .isEqualTo(new NodeKey(NodeKey.Kind.NODE, 31));
  }

  @Test
  void malformedSegmentDegradesToNodeWithNoRealId() {
    // Expanded keys are persisted, hand-editable request input: garbage (or Tag's own
    // "<id>:unspecified" bucket, never itself expandable) must never throw.
    assertThat(NodeKey.parse("abc")).isEqualTo(new NodeKey(NodeKey.Kind.NODE, -1));
    assertThat(NodeKey.parse("5:unspecified")).isEqualTo(new NodeKey(NodeKey.Kind.NODE, -1));
    assertThat(NodeKey.parse("person:x")).isEqualTo(new NodeKey(NodeKey.Kind.NODE, -1));
    // The pre-issue-06 per-currency bucket key is stale, never a real node.
    assertThat(NodeKey.parse("personal:EUR")).isEqualTo(new NodeKey(NodeKey.Kind.NODE, -1));
  }
}
