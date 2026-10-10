package volkovandr.hauptbuch.statements;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.web.NavItem;

/**
 * The statement-parser prompt editor (statements.md §3.2): the operator edits the system prompt the
 * statement parser is sent. Reached from the Settings screen's AI section, beside the receipt
 * parse prompt. The text is stored opaquely on the settings row; parsing instructions only
 * (ARCH-08).
 */
@Controller
class StatementPromptController {

  private static final String VIEW = "statement-prompt";
  private static final String PATH = "/statements/ai-prompt";

  private final SettingsService settingsService;
  private final StatementPromptBuilder promptBuilder;

  StatementPromptController(SettingsService settingsService, StatementPromptBuilder promptBuilder) {
    this.settingsService = settingsService;
    this.promptBuilder = promptBuilder;
  }

  /** The prompt-edit screen: the effective instructions. */
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
    settingsService.setStatementSystemPrompt(reset ? null : instructions);
    populate(model);
    return VIEW;
  }

  private void populate(Model model) {
    String stored = settingsService.statementSystemPrompt();
    model.addAttribute(
        "instructions", stored == null ? promptBuilder.defaultInstructions() : stored);
    model.addAttribute("isCustom", stored != null);
    model.addAttribute("nav", NavItem.sectionsFor("/settings"));
    model.addAttribute("title", "Statement prompt · Hauptbuch");
  }
}
