package volkovandr.hauptbuch.recurring;

import volkovandr.hauptbuch.operations.SplitForm;

/**
 * What the template editor shows (data-model §14.1): the split panel's form and the schedule block.
 *
 * @param split the entry, as the split panel edits it; its date is the template's start
 * @param schedule the schedule block
 */
record RecurringEditor(SplitForm split, RecurringScheduleForm schedule) {}
