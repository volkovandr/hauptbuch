package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Unit tier (CLAUDE.md §6): {@link PresetRendering}'s editor-page helpers (reporting.md §11a.1,
 * plan stage d3) — resolving a draft (including its own renderer/trend line), building the actions
 * strip's view, {@link PresetRendering#allParams}, and carrying a draft's ephemeral row-tree
 * expansion (reporting.md §9.1, issue 02). Full-page rendering itself is each controller's own
 * integration coverage ({@code ReportControllerIntegrationTest}, {@code
 * SavedReportControllerIntegrationTest}, {@code ReportEditorControllerIntegrationTest}).
 */
class PresetRenderingTest {

  private static final PresetRendering.Presentation BASE =
      new PresetRendering.Presentation(
          "Category × month matrix", Presets.categoryMonthMatrix(), Renderer.TABLE, false);

  private static final PresetRendering.Presentation SAVED_WITH_FOOD_EXPANDED =
      new PresetRendering.Presentation(
          "My matrix", Presets.categoryMonthMatrix(), Renderer.TABLE, false, Set.of("1"));

  @Test
  void resolvePresentationReturnsBaseUnchangedWithNoDraftParams() {
    MultiValueMap<String, String> params = new LinkedMultiValueMap<>();

    PresetRendering.Presentation effective = PresetRendering.resolvePresentation(BASE, params);

    assertThat(effective).isSameAs(BASE);
  }

  @Test
  void resolvePresentationSubstitutesTheDraftSpecButKeepsTheTitle() {
    MultiValueMap<String, String> params = ReportSpecQueryString.toParams(Presets.balanceSheet());

    PresetRendering.Presentation effective = PresetRendering.resolvePresentation(BASE, params);

    assertThat(effective.spec()).isEqualTo(Presets.balanceSheet());
    assertThat(effective.title()).isEqualTo(BASE.title());
    // No renderer/trendLine params in this draft (a hand-built spec-only URL) — falls back to BASE.
    assertThat(effective.renderer()).isEqualTo(BASE.renderer());
    assertThat(effective.trendLine()).isEqualTo(BASE.trendLine());
  }

  @Test
  void resolvePresentationReadsTheDraftsOwnRendererAndTrendLine() {
    MultiValueMap<String, String> params =
        PresetRendering.allParams(
            new PresetRendering.Presentation(
                BASE.title(), Presets.netWorthOverTime(), Renderer.LINE, true));

    PresetRendering.Presentation effective = PresetRendering.resolvePresentation(BASE, params);

    assertThat(effective.renderer()).isEqualTo(Renderer.LINE);
    assertThat(effective.trendLine()).isTrue();
  }

  @Test
  void allParamsCarriesTheSpecPlusRendererAndTrendLine() {
    PresetRendering.Presentation presentation =
        new PresetRendering.Presentation(BASE.title(), Presets.balanceSheet(), Renderer.PIE, true);

    MultiValueMap<String, String> params = PresetRendering.allParams(presentation);

    assertThat(ReportSpecQueryString.fromParams(params)).isEqualTo(Presets.balanceSheet());
    assertThat(params.getFirst("renderer")).isEqualTo("PIE");
    assertThat(params.getFirst("trendLine")).isEqualTo("true");
  }

  @Test
  void editorViewCarriesTheEffectiveSpecAsRoundTrippableHiddenParams() {
    PresetRendering.ReportEditorView view =
        PresetRendering.editorView(BASE, true, 42L, "My report", "/reports/42", "My report copy");

    assertThat(view.reportId()).isEqualTo(42L);
    assertThat(view.currentName()).isEqualTo("My report");
    assertThat(view.unsaved()).isTrue();
    assertThat(view.discardUrl()).isEqualTo("/reports/42");
    assertThat(view.saveAsNewName()).isEqualTo("My report copy");
    assertThat(view.renderer()).isEqualTo("TABLE");
    assertThat(view.trendLine()).isFalse();
    assertThat(ReportSpecQueryString.fromParams(view.specParams())).isEqualTo(BASE.spec());
  }

  // ── a draft's ephemeral expansion state (reporting.md §9.1, issue 02) ───────────────────────

  @Test
  void resolvePresentationTakesExpansionFromParamsWithoutMakingDraft() {
    // A Preset or /reports/new toggled before any setting changed: only the expansion is in the
    // URL, so the spec stays the base's own and the page is not an unsaved draft.
    MultiValueMap<String, String> params = RowToggle.expansionParams(Set.of("7"));

    PresetRendering.Presentation effective = PresetRendering.resolvePresentation(BASE, params);

    assertThat(ReportSpecQueryString.isPresent(params)).isFalse();
    assertThat(effective.spec()).isEqualTo(BASE.spec());
    assertThat(effective.expandedNodeKeys()).containsExactly("7");
  }

  @Test
  void draftWithNoExpansionParamStartsFromTheSavedReportsOwnExpansion() {
    MultiValueMap<String, String> params = ReportSpecQueryString.toParams(Presets.balanceSheet());

    PresetRendering.Presentation effective =
        PresetRendering.resolvePresentation(SAVED_WITH_FOOD_EXPANDED, params);

    assertThat(effective.expandedNodeKeys()).containsExactly("1");
  }

  @Test
  void draftsOwnExpansionOverridesTheSavedReportsOne() {
    MultiValueMap<String, String> params = ReportSpecQueryString.toParams(Presets.balanceSheet());
    params.addAll(RowToggle.expansionParams(Set.of("2")));

    PresetRendering.Presentation effective =
        PresetRendering.resolvePresentation(SAVED_WITH_FOOD_EXPANDED, params);

    assertThat(effective.expandedNodeKeys()).containsExactly("2");
  }

  @Test
  void allParamsCarriesTheExpansionOnlyOnceItIsExplicit() {
    assertThat(RowToggle.expandedKeysFrom(PresetRendering.allParams(BASE))).isEmpty();
    assertThat(
            RowToggle.expandedKeysFrom(PresetRendering.allParams(SAVED_WITH_FOOD_EXPANDED))
                .orElseThrow())
        .containsExactly("1");
  }

  @Test
  void editorViewCarriesTheExpansionSoSavingKeepsIt() {
    PresetRendering.ReportEditorView view =
        PresetRendering.editorView(
            SAVED_WITH_FOOD_EXPANDED, true, 42L, "My matrix", "/reports/42", "My matrix copy");

    assertThat(RowToggle.expandedKeysFrom(view.specParams()).orElseThrow()).containsExactly("1");
  }

  @Test
  void draftToggleCarriesTheWholeStateButNotTheOldExpansion() {
    PresetRendering.Presentation draft =
        new PresetRendering.Presentation(
            "My matrix", Presets.balanceSheet(), Renderer.TABLE, false, Set.of("1"));

    RowToggle toggle = PresetRendering.draftToggle(draft, true, "/reports/42");

    assertThat(toggle.persists()).isFalse();
    assertThat(ReportSpecQueryString.fromParams(toggle.carriedParams()))
        .isEqualTo(Presets.balanceSheet());
    assertThat(toggle.carriedParams()).doesNotContainKey(RowToggle.EXPANDED);
    assertThat(toggle.expandedKeys()).containsExactly("1");
  }

  @Test
  void toggleOnUneditedPageCarriesNoSpecSoItDoesNotBecomeDraft() {
    RowToggle toggle = PresetRendering.draftToggle(BASE, false, "/reports/new");

    assertThat(toggle.carriedParams()).isEmpty();
  }
}
