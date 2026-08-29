package volkovandr.hauptbuch.accounts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import volkovandr.hauptbuch.accounts.repository.CurrencyOptionRepository;
import volkovandr.hauptbuch.shared.MoneyFormat;
import volkovandr.hauptbuch.web.NavItem;

/**
 * The accounts screen (plan stage 6a): the list of asset and liability accounts, the open-account
 * form — any seeded currency, optional opening balance posted as a real balanced transaction — and
 * the per-account edit page (name, stored hue, close).
 *
 * <p>Lives in the {@code accounts} module, not {@code web}: feature screens' controllers belong to
 * their feature module (CLAUDE.md §3). Standard server-rendered forms, redirect after POST; no
 * bespoke JS.
 */
@Controller
class AccountsController {

  private static final String BASE_PATH = "/accounts";
  private static final String LIST_VIEW = "accounts";
  private static final String EDIT_VIEW = "account-edit";
  private static final String REDIRECT_TO_LIST = "redirect:" + BASE_PATH;

  /**
   * The palette hues offered on the edit form, named for the select. Values are the stored {@link
   * AccountService#HUE_PALETTE} degrees.
   */
  private static final List<HueOption> HUE_OPTIONS =
      List.of(
          new HueOption(210, "Blue"),
          new HueOption(30, "Amber"),
          new HueOption(140, "Green"),
          new HueOption(275, "Violet"),
          new HueOption(0, "Red"),
          new HueOption(180, "Teal"),
          new HueOption(330, "Pink"),
          new HueOption(60, "Olive"),
          new HueOption(250, "Indigo"),
          new HueOption(100, "Moss"));

  private final AccountService accountService;
  private final CurrencyOptionRepository currencyOptionRepository;

  AccountsController(
      AccountService accountService, CurrencyOptionRepository currencyOptionRepository) {
    this.accountService = accountService;
    this.currencyOptionRepository = currencyOptionRepository;
  }

  /** The accounts list plus the open-account form. */
  @GetMapping(BASE_PATH)
  String accounts(Model model) {
    List<Account> accounts = accountService.manageableAccounts();
    model.addAttribute("assets", accounts.stream().filter(a -> "asset".equals(a.type())).toList());
    model.addAttribute(
        "liabilities", accounts.stream().filter(a -> "liability".equals(a.type())).toList());
    model.addAttribute("parentOptions", accountService.parentOptions());
    model.addAttribute("currencies", currencyOptionRepository.findAll());
    model.addAttribute("today", LocalDate.now());
    model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
    model.addAttribute("title", "Accounts · Hauptbuch");
    return LIST_VIEW;
  }

  /**
   * Open a new account. A non-blank opening balance (German-formatted, e.g. {@code 1.234,56}) is
   * posted through the engine as {@code Account +X / Opening Balances −X} dated {@code openedAt}.
   */
  @PostMapping(BASE_PATH)
  String openAccount(
      @RequestParam String name,
      @RequestParam String type,
      @RequestParam(required = false) Long parentId,
      @RequestParam String currencyCode,
      @RequestParam LocalDate openedAt,
      @RequestParam(required = false) String openingBalance) {
    BigDecimal balance =
        openingBalance == null || openingBalance.isBlank()
            ? null
            : MoneyFormat.parse(openingBalance);
    accountService.openAccount(
        new AccountDraft(name, type, parentId, currencyCode, openedAt, balance));
    return REDIRECT_TO_LIST;
  }

  /** The edit page for one account: name, stored hue, and the close action. */
  @GetMapping("/accounts/{accountId}")
  String editAccount(@PathVariable long accountId, Model model) {
    Account account =
        accountService
            .findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("No account with id " + accountId));
    model.addAttribute("account", account);
    model.addAttribute("hueOptions", HUE_OPTIONS);
    model.addAttribute(
        "detection",
        accountService.detectionOf(accountId).orElse(new AccountDetection(null, false)));
    model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
    model.addAttribute("title", account.name() + " · Hauptbuch");
    return EDIT_VIEW;
  }

  /**
   * Save the paying-account detection config (data-model §13.4): the labels whose appearance in a
   * receipt's payment line seeds this as the paying account, and whether it is the cash account a
   * {@code Bar}/cash line resolves to in this account's currency.
   */
  @PostMapping("/accounts/{accountId}/detection")
  String updateDetection(
      @PathVariable long accountId,
      @RequestParam(required = false) String detectionLabels,
      @RequestParam(required = false, defaultValue = "false") boolean cashAccount) {
    accountService.updateDetection(accountId, detectionLabels, cashAccount);
    return REDIRECT_TO_LIST;
  }

  /**
   * Save the freely-editable fields: display name, stored hue, and whether the account is pinned to
   * the landing page's Balances panel (CONTEXT.md "Pinned account"). An unchecked checkbox posts no
   * value, so the flag defaults to {@code false}.
   */
  @PostMapping("/accounts/{accountId}")
  String updateAccount(
      @PathVariable long accountId,
      @RequestParam String name,
      @RequestParam(required = false) Integer hue,
      @RequestParam(required = false, defaultValue = "false") boolean showOnMainPage) {
    accountService.updateAccount(accountId, name, hue, showOnMainPage);
    return REDIRECT_TO_LIST;
  }

  /** Close the account as of today. Closing hides it from daily use; history stays. */
  @PostMapping("/accounts/{accountId}/close")
  String closeAccount(@PathVariable long accountId) {
    accountService.closeAccount(accountId, LocalDate.now());
    return REDIRECT_TO_LIST;
  }

  /** Reopen a closed account, putting it back into daily use. */
  @PostMapping("/accounts/{accountId}/reopen")
  String reopenAccount(@PathVariable long accountId) {
    accountService.reopenAccount(accountId);
    return REDIRECT_TO_LIST;
  }

  /**
   * A named palette hue for the edit form's swatch picker.
   *
   * @param value degrees on the HSL wheel, as stored on the account
   * @param label the colour's display name
   */
  record HueOption(int value, String label) {}
}
