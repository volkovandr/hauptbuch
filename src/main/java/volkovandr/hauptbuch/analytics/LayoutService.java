package volkovandr.hauptbuch.analytics;

import java.util.Optional;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.analytics.repository.LayoutRepository;

/**
 * The main page's own 1x1 Layout (reporting.md §11, plan stage c) — one Frame, showing one Preset,
 * changeable from its picker. The reporting page's own (possibly larger) Layout, and general row x
 * column configuration, are stage c's second work package.
 */
@Service
class LayoutService {

  private final LayoutRepository layoutRepository;

  LayoutService(LayoutRepository layoutRepository) {
    this.layoutRepository = layoutRepository;
  }

  /** The main page Frame's configured Preset slug — empty when the Frame has been cleared. */
  Optional<String> mainFramePresetSlug() {
    return layoutRepository.findMainFramePresetSlug();
  }

  /**
   * Points the main page Frame at a different Preset. Rejected before the write if the slug names
   * no known Preset — the service upholds this invariant, not the repository (CLAUDE.md §1.7).
   */
  void updateMainFramePreset(String presetSlug) {
    if (PresetCatalog.find(presetSlug).isEmpty()) {
      throw new IllegalArgumentException("No such preset: " + presetSlug);
    }
    layoutRepository.updateMainFramePreset(presetSlug);
  }
}
