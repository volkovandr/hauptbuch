package volkovandr.hauptbuch.recurring.repository;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.recurring.RecurringTemplate;
import volkovandr.hauptbuch.recurring.RecurringTemplateDraft;
import volkovandr.hauptbuch.recurring.RecurringTemplateLine;
import volkovandr.hauptbuch.recurring.RecurringTemplateLineDraft;

/**
 * Native-SQL access to {@code recurring_template}, its lines and its two tag junctions (data-model
 * §14.2). Plain inserts, updates and by-id reads, so the round-trips live in the integration tier
 * (CLAUDE.md §6).
 *
 * <p>A save writes the whole entry: {@link #insert} and {@link #update} each write the header, the
 * header tags, the lines and the line tags together. An update replaces the lines and tags outright
 * rather than diffing them; nothing else references a template line.
 */
@Repository
public class RecurringTemplateRepository {

  private static final String TEMPLATE_ID = "recurringTemplateId";

  private static final String SELECT_TEMPLATE =
      """
      select recurring_template_id, name, start_date, cadence_unit, cadence_n, end_date,
             lead_days, confirmation, booked_through, end_reminder, end_reminder_days,
             management_url, account_id, person_id, funding_person_direction, payee_id, note,
             spending_currency_code, funding_total, base_total, created_at, updated_at, deleted_at
      from recurring_template
      """;

  private final JdbcClient jdbcClient;

  RecurringTemplateRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /**
   * Insert a new template with its tags and lines, starting its cursor at {@code bookedThrough}
   * (data-model §14.3), and return its generated id.
   */
  public long insert(RecurringTemplateDraft draft, LocalDate bookedThrough) {
    long templateId =
        jdbcClient
            .sql(
                """
                insert into recurring_template
                  (name, start_date, cadence_unit, cadence_n, end_date, lead_days, confirmation,
                   booked_through, end_reminder, end_reminder_days, management_url, account_id,
                   person_id, funding_person_direction, payee_id, note, spending_currency_code,
                   funding_total, base_total)
                values
                  (:name, :startDate, :cadenceUnit, :cadenceN, :endDate, :leadDays, :confirmation,
                   :bookedThrough, :endReminder, :endReminderDays, :managementUrl, :accountId,
                   :personId, :fundingPersonDirection, :payeeId, :note, :spendingCurrencyCode,
                   :fundingTotal, :baseTotal)
                returning recurring_template_id
                """)
            .params(headerParams(draft))
            .param("bookedThrough", bookedThrough)
            .query(Long.class)
            .single();
    insertTagsAndLines(templateId, draft);
    return templateId;
  }

  /**
   * Overwrite a template's header, tags and lines with {@code draft}. The cursor is left alone; the
   * booking run owns it.
   *
   * @return the number of templates updated (0 when the id is unknown or soft-deleted)
   */
  public int update(long recurringTemplateId, RecurringTemplateDraft draft) {
    int rows =
        jdbcClient
            .sql(
                """
                update recurring_template
                set name = :name, start_date = :startDate, cadence_unit = :cadenceUnit,
                    cadence_n = :cadenceN, end_date = :endDate, lead_days = :leadDays,
                    confirmation = :confirmation, end_reminder = :endReminder,
                    end_reminder_days = :endReminderDays, management_url = :managementUrl,
                    account_id = :accountId, person_id = :personId,
                    funding_person_direction = :fundingPersonDirection, payee_id = :payeeId,
                    note = :note, spending_currency_code = :spendingCurrencyCode,
                    funding_total = :fundingTotal, base_total = :baseTotal, updated_at = now()
                where recurring_template_id = :recurringTemplateId and deleted_at is null
                """)
            .params(headerParams(draft))
            .param(TEMPLATE_ID, recurringTemplateId)
            .update();
    if (rows > 0) {
      deleteTagsAndLines(recurringTemplateId);
      insertTagsAndLines(recurringTemplateId, draft);
    }
    return rows;
  }

  /** A template by id, live or soft-deleted. */
  public Optional<RecurringTemplate> findById(long recurringTemplateId) {
    return jdbcClient
        .sql(SELECT_TEMPLATE + "where recurring_template_id = :recurringTemplateId")
        .param(TEMPLATE_ID, recurringTemplateId)
        .query(RecurringTemplate.class)
        .optional();
  }

  /** The live (not soft-deleted) templates, by name. */
  public List<RecurringTemplate> findLive() {
    return jdbcClient
        .sql(
            SELECT_TEMPLATE
                + "where deleted_at is null order by lower(name), recurring_template_id")
        .query(RecurringTemplate.class)
        .list();
  }

  /** A template's header tags, which land on the funding leg. */
  public List<Long> findTagIds(long recurringTemplateId) {
    return jdbcClient
        .sql(
            """
            select tag_id from recurring_template_tag
            where recurring_template_id = :recurringTemplateId
            order by tag_id
            """)
        .param(TEMPLATE_ID, recurringTemplateId)
        .query(Long.class)
        .list();
  }

  /** A template's lines, in order. */
  public List<RecurringTemplateLine> findLines(long recurringTemplateId) {
    return jdbcClient
        .sql(
            """
            select recurring_template_line_id, recurring_template_id, account_id,
                   transfer_direction, person_id, person_direction, amount, note, sort_order
            from recurring_template_line
            where recurring_template_id = :recurringTemplateId
            order by sort_order, recurring_template_line_id
            """)
        .param(TEMPLATE_ID, recurringTemplateId)
        .query(RecurringTemplateLine.class)
        .list();
  }

  /** A template line's own tags. */
  public List<Long> findLineTagIds(long recurringTemplateLineId) {
    return jdbcClient
        .sql(
            """
            select tag_id from recurring_template_line_tag
            where recurring_template_line_id = :recurringTemplateLineId
            order by tag_id
            """)
        .param("recurringTemplateLineId", recurringTemplateLineId)
        .query(Long.class)
        .list();
  }

  /**
   * Soft-delete a live template (data-model §14.3). Its booked transactions keep their stamp.
   *
   * @return the number of templates deleted (0 when unknown or already deleted)
   */
  public int softDelete(long recurringTemplateId) {
    return jdbcClient
        .sql(
            """
            update recurring_template set deleted_at = now()
            where recurring_template_id = :recurringTemplateId and deleted_at is null
            """)
        .param(TEMPLATE_ID, recurringTemplateId)
        .update();
  }

  /** The header columns a save writes, by their named parameters. */
  private static Map<String, Object> headerParams(RecurringTemplateDraft draft) {
    Map<String, Object> params = new HashMap<>();
    params.put("name", draft.name());
    params.put("startDate", draft.startDate());
    params.put("cadenceUnit", draft.cadenceUnit());
    params.put("cadenceN", draft.cadenceN());
    params.put("endDate", draft.endDate());
    params.put("leadDays", draft.leadDays());
    params.put("confirmation", draft.confirmation());
    params.put("endReminder", draft.endReminder());
    params.put("endReminderDays", draft.endReminderDays());
    params.put("managementUrl", draft.managementUrl());
    params.put("accountId", draft.accountId());
    params.put("personId", draft.personId());
    params.put("fundingPersonDirection", draft.fundingPersonDirection());
    params.put("payeeId", draft.payeeId());
    params.put("note", draft.note());
    params.put("spendingCurrencyCode", draft.spendingCurrencyCode());
    params.put("fundingTotal", draft.fundingTotal());
    params.put("baseTotal", draft.baseTotal());
    return params;
  }

  private void insertTagsAndLines(long recurringTemplateId, RecurringTemplateDraft draft) {
    for (Long tagId : draft.tagIds()) {
      jdbcClient
          .sql(
              """
              insert into recurring_template_tag (recurring_template_id, tag_id)
              values (:recurringTemplateId, :tagId)
              on conflict (recurring_template_id, tag_id) do nothing
              """)
          .param(TEMPLATE_ID, recurringTemplateId)
          .param("tagId", tagId)
          .update();
    }
    List<RecurringTemplateLineDraft> lines = draft.lines();
    for (int sortOrder = 0; sortOrder < lines.size(); sortOrder++) {
      insertLine(recurringTemplateId, lines.get(sortOrder), sortOrder);
    }
  }

  private void insertLine(
      long recurringTemplateId, RecurringTemplateLineDraft line, int sortOrder) {
    long lineId =
        jdbcClient
            .sql(
                """
                insert into recurring_template_line
                  (recurring_template_id, account_id, transfer_direction, person_id,
                   person_direction, amount, note, sort_order)
                values
                  (:recurringTemplateId, :accountId, :transferDirection, :personId,
                   :personDirection, :amount, :note, :sortOrder)
                returning recurring_template_line_id
                """)
            .param(TEMPLATE_ID, recurringTemplateId)
            .param("accountId", line.accountId())
            .param("transferDirection", line.transferDirection())
            .param("personId", line.personId())
            .param("personDirection", line.personDirection())
            .param("amount", line.amount())
            .param("note", line.note())
            .param("sortOrder", sortOrder)
            .query(Long.class)
            .single();
    for (Long tagId : line.tagIds()) {
      jdbcClient
          .sql(
              """
              insert into recurring_template_line_tag (recurring_template_line_id, tag_id)
              values (:recurringTemplateLineId, :tagId)
              on conflict (recurring_template_line_id, tag_id) do nothing
              """)
          .param("recurringTemplateLineId", lineId)
          .param("tagId", tagId)
          .update();
    }
  }

  private void deleteTagsAndLines(long recurringTemplateId) {
    jdbcClient
        .sql(
            """
            delete from recurring_template_line_tag
            where recurring_template_line_id in (select recurring_template_line_id
                                                 from recurring_template_line
                                                 where recurring_template_id = :recurringTemplateId)
            """)
        .param(TEMPLATE_ID, recurringTemplateId)
        .update();
    jdbcClient
        .sql(
            """
            delete from recurring_template_line
            where recurring_template_id = :recurringTemplateId
            """)
        .param(TEMPLATE_ID, recurringTemplateId)
        .update();
    jdbcClient
        .sql(
            "delete from recurring_template_tag where recurring_template_id = :recurringTemplateId")
        .param(TEMPLATE_ID, recurringTemplateId)
        .update();
  }
}
