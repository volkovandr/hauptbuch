package volkovandr.hauptbuch.recurring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (CLAUDE.md §6): the end reminder's start (recurring sub-plan slice g). The main page
 * reminds from {@code end_date − days} onwards, and only while the reminder is ticked and the
 * template has an end.
 */
class RecurringTemplateTest {

  private static final LocalDate END = LocalDate.of(2026, 12, 31);

  private static RecurringTemplate template(LocalDate end, boolean reminder, Integer days) {
    return new RecurringTemplate(
        5L,
        "Streaming",
        LocalDate.of(2026, 1, 31),
        "month",
        1,
        end,
        0,
        "auto",
        LocalDate.of(2026, 9, 27),
        reminder,
        days,
        null,
        7L,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  @Test
  void reminderStartsTheGivenDaysBeforeTheEnd() {
    RecurringTemplate template = template(END, true, 30);

    assertThat(template.endReminderFrom()).isEqualTo(LocalDate.of(2026, 12, 1));
    assertThat(template.remindsOfEndOn(LocalDate.of(2026, 11, 30))).isFalse();
    assertThat(template.remindsOfEndOn(LocalDate.of(2026, 12, 1))).isTrue();
    assertThat(template.remindsOfEndOn(END.plusDays(10))).isTrue();
  }

  @Test
  void zeroDaysRemindsOnTheEndDate() {
    assertThat(template(END, true, 0).endReminderFrom()).isEqualTo(END);
  }

  @Test
  void noReminderWhenUntickedOrWithoutEnd() {
    assertThat(template(END, false, 30).endReminderFrom()).isNull();
    assertThat(template(null, true, 30).endReminderFrom()).isNull();
    assertThat(template(END, true, null).endReminderFrom()).isNull();
    assertThat(template(END, false, 30).remindsOfEndOn(END)).isFalse();
  }
}
