package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Unit tier (CLAUDE.md §6): {@link PresetRendering}'s editor-page helpers (reporting.md §11a.1,
 * plan stage d3) — resolving a draft (including its own renderer/trend line), building the actions
 * strip's view, and {@link PresetRendering#allParams}. Full-page rendering itself is each
 * controller's own integration coverage ({@code ReportControllerIntegrationTest}, {@code
 * SavedReportControllerIntegrationTest}, {@code ReportEditorControllerIntegrationTest}).
 */
class PresetRenderingTest {

  private static final PresetRendering.Presentation BASE =
      new PresetRendering.Presentation(
          "Category × month matrix", Presets.categoryMonthMatrix(), Renderer.TABLE, false);

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
}
