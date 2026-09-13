package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.analytics.repository.LayoutRepository;

/**
 * Unit tier: {@link LayoutService} orchestration with {@link LayoutRepository} mocked — the
 * unknown-slug rejection (CLAUDE.md §1.7) is decidable without the DB and belongs here; the
 * persistence round-trip is {@link LayoutRepositoryIntegrationTest}'s job.
 */
@ExtendWith(MockitoExtension.class)
class LayoutServiceTest {

  @Mock private LayoutRepository layoutRepository;
  @InjectMocks private LayoutService layoutService;

  @Test
  void mainFramePresetSlugDelegatesToTheRepository() {
    when(layoutRepository.findMainFramePresetSlug()).thenReturn(Optional.of("balance-sheet"));

    assertThat(layoutService.mainFramePresetSlug()).contains("balance-sheet");
  }

  @Test
  void updateMainFramePresetWritesKnownSlug() {
    layoutService.updateMainFramePreset("balance-sheet");

    verify(layoutRepository).updateMainFramePreset("balance-sheet");
  }

  @Test
  void updateMainFramePresetRejectsAnUnknownSlugBeforeWriting() {
    assertThatThrownBy(() -> layoutService.updateMainFramePreset("no-such-preset"))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(layoutRepository);
  }
}
