package volkovandr.hauptbuch.receipts;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import volkovandr.hauptbuch.categories.AiVocabularyService;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.web.NavItem;

/**
 * The receipt-parser prompt editor (owner feedback 2026-08-02): the operator edits the system
 * prompt's instructions and sees, read-only, the category list exactly as it is injected at parse
 * time. Reached from the Settings screen's AI section.
 *
 * <p>Lives in {@code receipts}, not {@code ledger}: assembling the prompt (the instructions + the
 * rendered {@link AiVocabularyService} vocabulary) is a receipts concern, and {@code ledger} may
 * not depend on {@code receipts} (it would cycle). The stored text itself lives on the settings row
 * via {@link SettingsService}, which treats it opaquely (ARCH-08: still parsing instructions only —
 * never ledger content).
 */
@Controller
class ReceiptPromptController {

  private static final String VIEW = "ai-prompt";
  private static final String PATH = "/receipts/ai-prompt";

  private final SettingsService settingsService;
  private final ReceiptPromptBuilder promptBuilder;
  private final AiVocabularyService aiVocabularyService;

  ReceiptPromptController(
      SettingsService settingsService,
      ReceiptPromptBuilder promptBuilder,
      AiVocabularyService aiVocabularyService) {
    this.settingsService = settingsService;
    this.promptBuilder = promptBuilder;
    this.aiVocabularyService = aiVocabularyService;
  }

  /** The prompt-edit screen: the effective instructions and the read-only category preview. */
  @GetMapping(PATH)
  String promptScreen(Model model) {
    populate(model);
    return VIEW;
  }

  /**
   * Save the edited instructions, or (with {@code reset} set) clear the override to fall back to
   * the built-in default. A blank Save also clears — an empty prompt is never sent.
   */
  @PostMapping(PATH)
  String savePrompt(
      @RequestParam(required = false) String instructions,
      @RequestParam(required = false, defaultValue = "false") boolean reset,
      Model model) {
    settingsService.setAiSystemPrompt(reset ? null : instructions);
    populate(model);
    return VIEW;
  }

  private void populate(Model model) {
    String stored = settingsService.aiSystemPrompt();
    model.addAttribute(
        "instructions", stored == null ? promptBuilder.defaultInstructions() : stored);
    model.addAttribute("isCustom", stored != null);
    model.addAttribute(
        "categoriesPreview", promptBuilder.renderCategories(aiVocabularyService.aiVocabulary()));
    model.addAttribute("nav", NavItem.sectionsFor("/settings"));
    model.addAttribute("title", "Parse prompt · Hauptbuch");
  }
}
