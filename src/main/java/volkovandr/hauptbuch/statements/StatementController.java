package volkovandr.hauptbuch.statements;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import volkovandr.hauptbuch.shared.MoneyFormat;
import volkovandr.hauptbuch.web.NavItem;

/**
 * The Statements page, the two-step upload and the statement page (statements sub-plan slices
 * b2–b3). The page lists every statement and holds the upload; the upload keeps the file, then asks
 * the operator to confirm the account the file points at; the statement page corrects the period,
 * the balances and the lines, shows what the matcher proposes (slice c1), and deletes the
 * statement. Accept, pick, Unmatch and the overlap decision are the match actions of slice c2; the
 * dock arrives with slice d.
 */
@Controller
class StatementController {

  private static final String BASE_PATH = "/statements";
  private static final String REDIRECT_BASE = "redirect:" + BASE_PATH;
  private static final String NOTICE = "notice";
  private static final String ERROR = "error";
  private static final String PROFILE_ID = "profileId";
  private static final int AMOUNT_DIGITS = 2;

  private final StatementService statementService;
  private final StatementProfileService profileService;
  private final StatementReviewService reviewService;
  private final StatementMatchService matchService;

  StatementController(
      StatementService statementService,
      StatementProfileService profileService,
      StatementReviewService reviewService,
      StatementMatchService matchService) {
    this.statementService = statementService;
    this.profileService = profileService;
    this.reviewService = reviewService;
    this.matchService = matchService;
  }

  /** The statements, newest first, filterable by account, with the upload form. */
  @GetMapping(BASE_PATH)
  String list(@RequestParam(required = false) Long account, Model model) {
    model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
    model.addAttribute("rows", statementService.rows(account));
    model.addAttribute("accounts", statementService.statementAccounts());
    model.addAttribute("profiles", profileService.live());
    model.addAttribute("accountFilter", account);
    return "statements";
  }

  /** Keep the uploaded file, then send the operator to confirm the account. */
  @PostMapping(BASE_PATH + "/upload")
  String upload(
      @RequestParam long profile,
      @RequestParam("file") MultipartFile file,
      @RequestParam(required = false) Long account,
      RedirectAttributes redirectAttributes) {
    try {
      profileService.get(profile);
      String path = statementService.stage(file.getOriginalFilename(), UploadedFiles.bytesOf(file));
      return backToConfirm(profile, path, file.getOriginalFilename(), account, redirectAttributes);
    } catch (StatementFormatException e) {
      redirectAttributes.addFlashAttribute(ERROR, e.getMessage());
      return REDIRECT_BASE;
    }
  }

  /** The confirm step: how the profile read the file and the account to put it on. */
  @GetMapping(BASE_PATH + "/confirm")
  String confirm(
      @RequestParam long profileId,
      @RequestParam String path,
      @RequestParam String name,
      @RequestParam(required = false) Long account,
      Model model,
      RedirectAttributes redirectAttributes) {
    try {
      UploadPreview preview = statementService.preview(profileId, path);
      model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
      model.addAttribute("preview", preview);
      model.addAttribute("accounts", statementService.statementAccounts());
      model.addAttribute("profile", profileService.get(profileId));
      model.addAttribute("path", path);
      model.addAttribute("name", name);
      model.addAttribute("chosenAccountId", account);
      return "statement-confirm";
    } catch (StatementFormatException e) {
      redirectAttributes.addFlashAttribute(ERROR, e.getMessage());
      return REDIRECT_BASE;
    }
  }

  /** Create the statement and its lines, then open it. */
  @PostMapping(BASE_PATH + "/create")
  String create(
      @RequestParam long profileId,
      @RequestParam String path,
      @RequestParam String name,
      @RequestParam(required = false) Long account,
      RedirectAttributes redirectAttributes) {
    if (account == null) {
      redirectAttributes.addFlashAttribute(ERROR, "Choose the account this statement is for.");
      return backToConfirm(profileId, path, name, null, redirectAttributes);
    }
    try {
      long id = statementService.create(profileId, path, name, account);
      return REDIRECT_BASE + "/" + id;
    } catch (StatementFormatException e) {
      redirectAttributes.addFlashAttribute(ERROR, e.getMessage());
      return backToConfirm(profileId, path, name, account, redirectAttributes);
    }
  }

  private static String backToConfirm(
      long profileId,
      String path,
      String name,
      Long account,
      RedirectAttributes redirectAttributes) {
    redirectAttributes.addAttribute(PROFILE_ID, profileId);
    redirectAttributes.addAttribute("path", path);
    redirectAttributes.addAttribute("name", name);
    if (account != null) {
      redirectAttributes.addAttribute("account", account);
    }
    return REDIRECT_BASE + "/confirm";
  }

  /** The statement page: header, then the line grid. */
  @GetMapping(BASE_PATH + "/{id}")
  String show(@PathVariable long id, Model model, RedirectAttributes redirectAttributes) {
    Statement statement;
    try {
      statement = statementService.get(id);
    } catch (StatementFormatException e) {
      redirectAttributes.addFlashAttribute(ERROR, e.getMessage());
      return REDIRECT_BASE;
    }
    model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
    model.addAttribute("statement", statement);
    model.addAttribute("accountName", statementService.accountName(statement.accountId()));
    model.addAttribute("profileName", profileService.nameOf(statement.statementProfileId()));
    model.addAttribute("opening", number(statement.openingBalance()));
    model.addAttribute("closing", number(statement.closingBalance()));
    model.addAttribute("lines", statementService.lines(id).stream().map(LineView::of).toList());
    StatementReview review = reviewService.review(id);
    model.addAttribute("review", review);
    model.addAttribute(
        "reviewLines",
        review.lines().stream().map(StatementReviewViews.ReviewLineView::of).toList());
    model.addAttribute(
        "reviewExtras", review.extras().stream().map(StatementReviewViews.ExtraView::of).toList());
    return "statement";
  }

  /** Save the period and the balances. */
  @PostMapping(BASE_PATH + "/{id}/header")
  String saveHeader(
      @PathVariable long id,
      @RequestParam(required = false) String periodStart,
      @RequestParam(required = false) String periodEnd,
      @RequestParam(required = false) String openingBalance,
      @RequestParam(required = false) String closingBalance,
      RedirectAttributes redirectAttributes) {
    return saved(
        id,
        () ->
            statementService.updateHeader(
                id, new HeaderEdit(periodStart, periodEnd, openingBalance, closingBalance)),
        "Header saved.",
        redirectAttributes);
  }

  /** Save the edited line grid. */
  @PostMapping(BASE_PATH + "/{id}/lines")
  String saveLines(
      @PathVariable long id,
      @RequestParam MultiValueMap<String, String> params,
      RedirectAttributes redirectAttributes) {
    return saved(
        id,
        () -> statementService.updateLines(id, edits(params)),
        "Lines saved.",
        redirectAttributes);
  }

  /** Confirm one candidate for a line (Accept, a pick, or "the same bank movement"). */
  @PostMapping(BASE_PATH + "/{id}/lines/{lineId}/accept")
  String accept(
      @PathVariable long id,
      @PathVariable long lineId,
      @RequestParam long posting,
      RedirectAttributes redirectAttributes) {
    return saved(
        id, () -> matchService.accept(id, lineId, posting), "Line matched.", redirectAttributes);
  }

  /** Confirm every unambiguous exact proposal. */
  @PostMapping(BASE_PATH + "/{id}/accept-all")
  String acceptAll(@PathVariable long id, RedirectAttributes redirectAttributes) {
    attempt(
        () -> {
          int matched = matchService.acceptAllExact(id);
          return matched + (matched == 1 ? " line matched." : " lines matched.");
        },
        redirectAttributes);
    return REDIRECT_BASE + "/" + id;
  }

  /** An overlapping candidate is "a different transaction that looks the same". */
  @PostMapping(BASE_PATH + "/{id}/lines/{lineId}/different")
  String different(
      @PathVariable long id,
      @PathVariable long lineId,
      @RequestParam long posting,
      RedirectAttributes redirectAttributes) {
    return saved(
        id,
        () -> matchService.rejectCandidate(id, lineId, posting),
        "Kept apart: the line is now treated as missing.",
        redirectAttributes);
  }

  /** Remove a line's match; its posting goes back to unreconciled. */
  @PostMapping(BASE_PATH + "/{id}/lines/{lineId}/unmatch")
  String unmatch(
      @PathVariable long id, @PathVariable long lineId, RedirectAttributes redirectAttributes) {
    return saved(
        id,
        () -> matchService.unmatch(id, lineId),
        "Match removed; the posting is unreconciled.",
        redirectAttributes);
  }

  /**
   * Delete the statement, removing its matches; {@code reconciliation} is {@code keep} (the
   * postings stay reconciled) or {@code reset} (they go back to unreconciled). The file stays on
   * the Pi.
   */
  @PostMapping(BASE_PATH + "/{id}/delete")
  String delete(
      @PathVariable long id,
      @RequestParam(defaultValue = "keep") String reconciliation,
      RedirectAttributes redirectAttributes) {
    attempt(
        () -> {
          matchService.deleteStatement(id, "reset".equals(reconciliation));
          return "Statement deleted. The file stays on the Pi.";
        },
        redirectAttributes);
    return REDIRECT_BASE;
  }

  private String saved(
      long id, Runnable save, String message, RedirectAttributes redirectAttributes) {
    attempt(
        () -> {
          save.run();
          return message;
        },
        redirectAttributes);
    return REDIRECT_BASE + "/" + id;
  }

  /** Run an action, flashing its notice, or the reason the operator's input was refused. */
  private static void attempt(Supplier<String> action, RedirectAttributes redirectAttributes) {
    try {
      redirectAttributes.addFlashAttribute(NOTICE, action.get());
    } catch (StatementFormatException e) {
      redirectAttributes.addFlashAttribute(ERROR, e.getMessage());
    }
  }

  /**
   * The grid's rows: every field is posted as {@code <field>_<lineId>}, the ids as {@code line}.
   */
  private static List<LineEdit> edits(MultiValueMap<String, String> params) {
    List<String> ids = params.getOrDefault("line", List.of());
    return ids.stream()
        .map(
            id ->
                new LineEdit(
                    Long.parseLong(id),
                    params.getFirst("bookingDate_" + id),
                    params.getFirst("valueDate_" + id),
                    params.getFirst("amount_" + id),
                    params.getFirst("counterparty_" + id),
                    params.getFirst("description_" + id),
                    params.getFirst("bankCategory_" + id)))
        .toList();
  }

  /** A line as the grid shows it: dates as ISO text, the amount German-formatted. */
  record LineView(
      long id,
      String bookingDate,
      String valueDate,
      String amount,
      String counterparty,
      String description,
      String bankCategory,
      String rawText,
      String problem) {

    static LineView of(StatementLine line) {
      return new LineView(
          line.statementLineId(),
          line.bookingDate() == null ? "" : line.bookingDate().toString(),
          line.valueDate() == null ? "" : line.valueDate().toString(),
          number(line.amount()),
          line.counterparty() == null ? "" : line.counterparty(),
          line.description() == null ? "" : line.description(),
          line.bankCategory() == null ? "" : line.bankCategory(),
          line.rawText(),
          line.problem());
    }
  }

  static String number(BigDecimal value) {
    return value == null ? "" : MoneyFormat.number(value, AMOUNT_DIGITS);
  }

  static String date(LocalDate date) {
    return date == null ? "" : date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
  }
}
