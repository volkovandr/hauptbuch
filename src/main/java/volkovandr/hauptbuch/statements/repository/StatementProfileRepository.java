package volkovandr.hauptbuch.statements.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.statements.StatementProfile;

/**
 * Native-SQL access to {@code statement_profile} (data-model §15). Plain inserts, updates and by-id
 * reads, so the round-trips live in the integration tier (CLAUDE.md §6).
 */
@Repository
public class StatementProfileRepository {

  private static final String PROFILE_ID = "statementProfileId";

  private static final String SELECT_PROFILE =
      """
      select statement_profile_id, name, format, window_days_before, window_days_after, ai_note,
             csv_delimiter, csv_quote, csv_encoding, csv_skip_rows, csv_has_header,
             csv_decimal_separator, csv_date_format, csv_sign_mode, col_booking_date,
             col_value_date, col_amount, col_debit, col_credit, col_currency, col_counterparty,
             col_description, col_bank_category, col_iban, deleted_at
      from statement_profile
      """;

  private final JdbcClient jdbcClient;

  StatementProfileRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** Insert a new profile and return its generated id. */
  public long insert(StatementProfile profile) {
    return bind(
            jdbcClient.sql(
                """
                insert into statement_profile
                  (name, format, window_days_before, window_days_after, ai_note, csv_delimiter,
                   csv_quote, csv_encoding, csv_skip_rows, csv_has_header, csv_decimal_separator,
                   csv_date_format, csv_sign_mode, col_booking_date, col_value_date, col_amount,
                   col_debit, col_credit, col_currency, col_counterparty, col_description,
                   col_bank_category, col_iban)
                values
                  (:name, :format, :windowDaysBefore, :windowDaysAfter, :aiNote, :csvDelimiter,
                   :csvQuote, :csvEncoding, :csvSkipRows, :csvHasHeader, :csvDecimalSeparator,
                   :csvDateFormat, :csvSignMode, :colBookingDate, :colValueDate, :colAmount,
                   :colDebit, :colCredit, :colCurrency, :colCounterparty, :colDescription,
                   :colBankCategory, :colIban)
                returning statement_profile_id
                """),
            profile)
        .query(Long.class)
        .single();
  }

  /**
   * Overwrite a live profile with {@code profile}.
   *
   * @return the number of profiles updated (0 when the id is unknown or soft-deleted)
   */
  public int update(long statementProfileId, StatementProfile profile) {
    return bind(
            jdbcClient.sql(
                """
                update statement_profile
                set name = :name, format = :format, window_days_before = :windowDaysBefore,
                    window_days_after = :windowDaysAfter, ai_note = :aiNote,
                    csv_delimiter = :csvDelimiter, csv_quote = :csvQuote,
                    csv_encoding = :csvEncoding, csv_skip_rows = :csvSkipRows,
                    csv_has_header = :csvHasHeader, csv_decimal_separator = :csvDecimalSeparator,
                    csv_date_format = :csvDateFormat, csv_sign_mode = :csvSignMode,
                    col_booking_date = :colBookingDate, col_value_date = :colValueDate,
                    col_amount = :colAmount, col_debit = :colDebit, col_credit = :colCredit,
                    col_currency = :colCurrency, col_counterparty = :colCounterparty,
                    col_description = :colDescription, col_bank_category = :colBankCategory,
                    col_iban = :colIban
                where statement_profile_id = :statementProfileId and deleted_at is null
                """),
            profile)
        .param(PROFILE_ID, statementProfileId)
        .update();
  }

  /** A profile by id, live or soft-deleted. */
  public Optional<StatementProfile> findById(long statementProfileId) {
    return jdbcClient
        .sql(SELECT_PROFILE + "where statement_profile_id = :statementProfileId")
        .param(PROFILE_ID, statementProfileId)
        .query(StatementProfile.class)
        .optional();
  }

  /** The live (not soft-deleted) profiles, by name. */
  public List<StatementProfile> findLive() {
    return jdbcClient
        .sql(SELECT_PROFILE + "where deleted_at is null order by lower(name), statement_profile_id")
        .query(StatementProfile.class)
        .list();
  }

  /**
   * Soft-delete a live profile; statements already read through it keep their reference.
   *
   * @return the number of profiles deleted (0 when the id is unknown or already deleted)
   */
  public int softDelete(long statementProfileId) {
    return jdbcClient
        .sql(
            """
            update statement_profile set deleted_at = now()
            where statement_profile_id = :statementProfileId and deleted_at is null
            """)
        .param(PROFILE_ID, statementProfileId)
        .update();
  }

  private static StatementSpec bind(StatementSpec spec, StatementProfile profile) {
    return spec.param("name", profile.name())
        .param("format", profile.format())
        .param("windowDaysBefore", profile.windowDaysBefore())
        .param("windowDaysAfter", profile.windowDaysAfter())
        .param("aiNote", profile.aiNote())
        .param("csvDelimiter", profile.csvDelimiter())
        .param("csvQuote", profile.csvQuote())
        .param("csvEncoding", profile.csvEncoding())
        .param("csvSkipRows", profile.csvSkipRows())
        .param("csvHasHeader", profile.csvHasHeader())
        .param("csvDecimalSeparator", profile.csvDecimalSeparator())
        .param("csvDateFormat", profile.csvDateFormat())
        .param("csvSignMode", profile.csvSignMode())
        .param("colBookingDate", profile.colBookingDate())
        .param("colValueDate", profile.colValueDate())
        .param("colAmount", profile.colAmount())
        .param("colDebit", profile.colDebit())
        .param("colCredit", profile.colCredit())
        .param("colCurrency", profile.colCurrency())
        .param("colCounterparty", profile.colCounterparty())
        .param("colDescription", profile.colDescription())
        .param("colBankCategory", profile.colBankCategory())
        .param("colIban", profile.colIban());
  }
}
