package volkovandr.hauptbuch.statements;

import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/**
 * The parse rules part of the PDF (rules) profile editor (statements.md §3.2a), all htmx: the live
 * check of the rules as they are typed, the row buttons (add, remove, move), loading a PDF's text
 * into the test area, and <em>Try</em>, which shows the TOON the rules make of that text. Nothing
 * here writes: the rules are saved with the profile, and the test text is never stored.
 */
@Controller
class ParseRuleEditorController {

  private static final String BASE_PATH = "/statements/profiles/rules";
  private static final String PROFILE = "profile";
  private static final String ERROR = "error";
  private static final String TEST_TEXT = "testText";
  private static final String BAD_NUMBERS =
      "Check the numbers: the window and the smart space width are whole numbers.";

  private final ParseRuleEngine engine;
  private final StatementPdfText pdfText;

  ParseRuleEditorController(ParseRuleEngine engine, StatementPdfText pdfText) {
    this.engine = engine;
    this.pdfText = pdfText;
  }

  /**
   * The live check: each rule's problem and the profile's, swapped out of band into the form —
   * judged exactly as Save will judge them.
   */
  @PostMapping(BASE_PATH + "/check")
  String check(
      @ModelAttribute(PROFILE) StatementProfile profile,
      BindingResult binding,
      @RequestParam MultiValueMap<String, String> params,
      Model model) {
    addRules(model, profile, binding, ParseRuleForm.rulesOf(params));
    return "statement-profile :: checks";
  }

  /** A row button: add a rule, or remove or move one; the rule list is shown again. */
  @PostMapping(BASE_PATH + "/edit")
  String edit(
      @ModelAttribute(PROFILE) StatementProfile profile,
      BindingResult binding,
      @RequestParam MultiValueMap<String, String> params,
      @RequestParam String op,
      Model model) {
    addRules(model, profile, binding, ParseRuleForm.edited(ParseRuleForm.rulesOf(params), op));
    return "statement-profile :: rules";
  }

  /**
   * Load a PDF's text into the test area: extracted locally, unmasked, and the file dropped at once
   * — nothing of it is stored.
   */
  @PostMapping(BASE_PATH + "/test-text")
  String testText(
      @RequestParam(name = "testPdf", required = false) MultipartFile pdf, Model model) {
    if (pdf == null || pdf.isEmpty()) {
      model.addAttribute(ERROR, "Pick a PDF to load its text.");
      return "statement-profile :: test-pdf-error";
    }
    try {
      model.addAttribute(TEST_TEXT, pdfText.extract(UploadedFiles.bytesOf(pdf)));
      return "statement-profile :: test-text-loaded";
    } catch (StatementFormatException e) {
      model.addAttribute(ERROR, e.getMessage());
      return "statement-profile :: test-pdf-error";
    }
  }

  /** Try: the TOON the rules make of the test text, or why they cannot run. */
  @PostMapping(BASE_PATH + "/try")
  String tryRules(
      @ModelAttribute(PROFILE) StatementProfile profile,
      BindingResult binding,
      @RequestParam MultiValueMap<String, String> params,
      @RequestParam(name = TEST_TEXT, defaultValue = "") String text,
      Model model) {
    if (binding.hasErrors()) {
      model.addAttribute(ERROR, BAD_NUMBERS);
    } else if (text.isBlank()) {
      model.addAttribute(ERROR, "Load a PDF or paste a statement's text to try the rules on.");
    } else {
      try {
        StatementProfile normalised = StatementProfileService.normalised(profile);
        List<ParseRule> rules =
            StatementProfileService.normalisedRules(ParseRuleForm.rulesOf(params));
        model.addAttribute("toon", engine.toToon(text, rules, normalised.rulesSettings()));
      } catch (StatementFormatException e) {
        model.addAttribute(ERROR, e.getMessage());
      }
    }
    return "statement-profile :: try";
  }

  /**
   * The rules as typed and their problems as Save will judge them — the settings normalised, and a
   * number that will not bind reported first.
   */
  private void addRules(
      Model model, StatementProfile profile, BindingResult binding, List<ParseRule> rules) {
    ParseRuleSettings settings = StatementProfileService.normalised(profile).rulesSettings();
    ParseRuleForm.addTo(model, rules, settings, engine);
    if (binding.hasErrors()) {
      model.addAttribute(
          ParseRuleForm.PROFILE_PROBLEMS,
          Stream.concat(Stream.of(BAD_NUMBERS), engine.profileProblems(rules, settings).stream())
              .toList());
    }
  }
}
