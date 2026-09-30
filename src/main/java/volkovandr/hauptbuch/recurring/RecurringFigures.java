package volkovandr.hauptbuch.recurring;

/**
 * One template's cost figures on the recurring page (data-model §14.4, recurring sub-plan slice g):
 * schedule math over its funding-leg amount, never bookkeeping. Each is formatted in the template's
 * currency, signed as the register shows it (negative = you pay), followed by base at today's rate
 * in parentheses where the currency differs and a rate is known.
 *
 * @param perMonth the amount per month
 * @param perYear the amount per year
 * @param already every occurrence from the start through today, booked or not
 * @param yetToPay the occurrences after today through the end date; null without an end
 * @param total already plus yet to pay; null without an end
 */
public record RecurringFigures(
    String perMonth, String perYear, String already, String yetToPay, String total) {}
