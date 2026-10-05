package volkovandr.hauptbuch.statements;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.accounts.PayingAccountDetector;
import volkovandr.hauptbuch.statements.repository.StatementLineRepository;
import volkovandr.hauptbuch.statements.repository.StatementRepository;

/**
 * Statements as domain operations (statements.md §3, §5): upload a file against a profile, correct
 * the header and the lines, delete. Matching arrives with slice c; until then no statement has a
 * match to remove, so a delete is just the soft-delete.
 */
@Service
public class StatementService {

  private static final Logger LOG = LoggerFactory.getLogger(StatementService.class);
  private static final int ALL_ROWS = Integer.MAX_VALUE;

  private final StatementRepository statementRepository;
  private final StatementLineRepository lineRepository;
  private final StatementProfileService profileService;
  private final StatementCsvParser parser;
  private final StatementStorage storage;
  private final AccountService accountService;
  private final PayingAccountDetector accountDetector;

  StatementService(
      StatementRepository statementRepository,
      StatementLineRepository lineRepository,
      StatementProfileService profileService,
      StatementCsvParser parser,
      StatementStorage storage,
      AccountService accountService,
      PayingAccountDetector accountDetector) {
    this.statementRepository = statementRepository;
    this.lineRepository = lineRepository;
    this.profileService = profileService;
    this.parser = parser;
    this.storage = storage;
    this.accountService = accountService;
    this.accountDetector = accountDetector;
  }

  /**
   * Keep an uploaded file on the Pi and return its root-relative path, for the confirm step to
   * refer to.
   *
   * @throws StatementFormatException when the file is empty or too large
   */
  public String stage(String originalFilename, byte[] bytes) {
    return storage.store(originalFilename, bytes);
  }

  /**
   * Read a staged file through a profile without saving anything: the counts, the problems, the
   * default period and the proposed account.
   *
   * @throws StatementFormatException when the profile cannot read the file
   */
  public UploadPreview preview(long statementProfileId, String filePath) {
    StatementProfile profile = profileService.get(statementProfileId);
    CsvStatement csv = parser.parse(profile, storage.read(filePath), null, ALL_ROWS);
    return new UploadPreview(
        csv.lines().size(),
        csv.problems(),
        csv.firstBooking(),
        csv.lastBooking(),
        accountDetector.detectByIdentifiers(csv.ibans()));
  }

  /**
   * Create the statement for a staged file: read it through the profile with the account's
   * currency, default the period to the first and last booking date, and keep every line.
   *
   * @return the new statement's id
   * @throws StatementFormatException when the profile cannot read the file or the account cannot
   *     hold a statement
   */
  @Transactional
  public long create(
      long statementProfileId, String filePath, String originalFilename, long accountId) {
    Account account = statementAccount(accountId);
    StatementProfile profile = profileService.get(statementProfileId);
    CsvStatement csv =
        parser.parse(profile, storage.read(filePath), account.currencyCode(), ALL_ROWS);
    long statementId =
        statementRepository.insert(
            statementProfileId,
            accountId,
            originalFilename,
            filePath,
            csv.firstBooking(),
            csv.lastBooking());
    csv.lines().forEach(line -> lineRepository.insert(statementId, line));
    LOG.info(
        "Created statement {} with {} lines on account {}",
        statementId,
        csv.lines().size(),
        accountId);
    return statementId;
  }

  /** The accounts a statement can be for: open, real asset and liability accounts. */
  public List<Account> statementAccounts() {
    return accountService.findLiveByTypes(List.of("asset", "liability")).stream()
        .filter(account -> !account.personLeaf() && account.closedAt() == null)
        .toList();
  }

  /** A live statement by id. */
  public Statement get(long statementId) {
    return statementRepository
        .findById(statementId)
        .filter(statement -> statement.deletedAt() == null)
        .orElseThrow(() -> new StatementFormatException("That statement no longer exists."));
  }

  /** The statement's lines in file order. */
  public List<StatementLine> lines(long statementId) {
    return lineRepository.findByStatement(statementId);
  }

  /** The live statements for the list, newest first; {@code accountId} null lists every account. */
  public List<StatementRow> rows(Long accountId) {
    return statementRepository.findLiveRows(accountId);
  }

  /**
   * Overwrite the period and the balances (statements.md §3.4). Blank balances mean the statement
   * is checked on turnover only.
   *
   * @throws StatementFormatException when a typed value cannot be read or the period is reversed
   */
  public void updateHeader(long statementId, HeaderEdit edit) {
    get(statementId);
    HeaderEdit.Header header = edit.read();
    statementRepository.updateHeader(
        statementId,
        header.periodStart(),
        header.periodEnd(),
        header.openingBalance(),
        header.closingBalance());
  }

  /**
   * Save the edited line grid. Every row is read first, so one unreadable entry saves nothing.
   *
   * @throws StatementFormatException naming the row that cannot be read
   */
  @Transactional
  public void updateLines(long statementId, List<LineEdit> edits) {
    get(statementId);
    List<StatementLine> updated =
        lineRepository.findByStatement(statementId).stream()
            .flatMap(
                old ->
                    edits.stream()
                        .filter(edit -> edit.statementLineId() == old.statementLineId())
                        .map(edit -> edit.applyTo(old)))
            .toList();
    updated.forEach(line -> lineRepository.update(statementId, line));
  }

  /** Soft-delete a statement; its file stays on the Pi (statements.md §5). */
  @Transactional
  public void delete(long statementId) {
    if (statementRepository.softDelete(statementId) > 0) {
      LOG.info("Deleted statement {}", statementId);
    }
  }

  private Account statementAccount(long accountId) {
    return statementAccounts().stream()
        .filter(account -> account.accountId() == accountId)
        .findFirst()
        .orElseThrow(
            () -> new StatementFormatException("Choose the account this statement is for."));
  }
}
