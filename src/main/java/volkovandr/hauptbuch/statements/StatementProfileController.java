package volkovandr.hauptbuch.statements;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import volkovandr.hauptbuch.web.NavItem;

/**
 * The statement profile screens (statements sub-plan slice b1): the list, the editor and its live
 * preview. The preview parses a sample file the operator picks with the settings currently in the
 * form, so a wrong date format is seen before the profile is saved (statements.md §3.1). It is
 * reached from the Statements page, not from the top menu.
 */
@Controller
class StatementProfileController {

  private static final String BASE_PATH = "/statements/profiles";
  private static final String STATEMENTS_PATH = "/statements";
  private static final String VIEW = "statement-profile";
  private static final String PREVIEW_FRAGMENT = "statement-profile :: preview";
  private static final String PROFILE = "profile";
  private static final String ERROR = "error";
  private static final String BAD_NUMBERS =
      "Check the numbers: the window and the skipped rows are whole numbers.";
  private static final int PREVIEW_ROWS = 8;

  private final StatementProfileService profileService;
  private final StatementCsvParser parser;

  StatementProfileController(StatementProfileService profileService, StatementCsvParser parser) {
    this.profileService = profileService;
    this.parser = parser;
  }

  /** The live profiles with a link to each and to a new one. */
  @GetMapping(BASE_PATH)
  String list(Model model) {
    model.addAttribute("nav", NavItem.sectionsFor(STATEMENTS_PATH));
    model.addAttribute("profiles", profileService.live());
    return "statement-profiles";
  }

  /** The editor for a new CSV profile. */
  @GetMapping(BASE_PATH + "/new")
  String create(Model model) {
    return editor(StatementProfile.blankCsv(), model);
  }

  /** The editor for an existing profile. */
  @GetMapping(BASE_PATH + "/{id}")
  String edit(@PathVariable long id, Model model) {
    return editor(profileService.get(id), model);
  }

  /** Save the profile, then back to the profile list; a refusal re-shows the editor. */
  @PostMapping(BASE_PATH + "/save")
  String save(
      @ModelAttribute(PROFILE) StatementProfile profile,
      BindingResult binding,
      Model model,
      RedirectAttributes redirectAttributes) {
    if (binding.hasErrors()) {
      model.addAttribute(ERROR, BAD_NUMBERS);
      return editor(profile, model);
    }
    try {
      profileService.save(profile);
    } catch (StatementFormatException e) {
      model.addAttribute(ERROR, e.getMessage());
      return editor(profile, model);
    }
    redirectAttributes.addFlashAttribute(
        "notice", "Profile '" + profile.name().strip() + "' saved.");
    return "redirect:" + BASE_PATH;
  }

  /** Soft-delete a profile; statements already read through it keep working. */
  @PostMapping(BASE_PATH + "/{id}/delete")
  String delete(@PathVariable long id, RedirectAttributes redirectAttributes) {
    profileService.delete(id);
    redirectAttributes.addFlashAttribute("notice", "Profile deleted.");
    return "redirect:" + BASE_PATH;
  }

  /**
   * The live preview: the first rows of the sample file parsed with the form's current settings, or
   * the reason they cannot be read. Nothing is saved and the sample is not kept.
   */
  @PostMapping(BASE_PATH + "/preview")
  String preview(
      @ModelAttribute(PROFILE) StatementProfile profile,
      BindingResult binding,
      @RequestParam(name = "sample", required = false) MultipartFile sample,
      Model model) {
    if (sample == null || sample.isEmpty()) {
      model.addAttribute("hint", "Pick a sample file to preview how it is read.");
    } else if (binding.hasErrors()) {
      model.addAttribute(ERROR, BAD_NUMBERS);
    } else {
      try {
        StatementProfile normalised = StatementProfileService.normalised(profile);
        parser.validate(normalised);
        CsvStatement csv =
            parser.parse(normalised, UploadedFiles.bytesOf(sample), null, PREVIEW_ROWS);
        model.addAttribute("csv", csv);
      } catch (StatementFormatException e) {
        model.addAttribute(ERROR, e.getMessage());
      }
    }
    return PREVIEW_FRAGMENT;
  }

  private String editor(StatementProfile profile, Model model) {
    model.addAttribute("nav", NavItem.sectionsFor(STATEMENTS_PATH));
    model.addAttribute(PROFILE, profile);
    return VIEW;
  }
}
