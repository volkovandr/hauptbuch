package volkovandr.hauptbuch.receipts;

import java.util.List;

/**
 * The Confirm gate refused (plan §9g): one or more hard blocks stand between the draft and a booked
 * transaction. Carries every finding, so the screen states them all at once rather than making the
 * operator rediscover them one Confirm at a time.
 */
public class ReceiptConfirmException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final List<String> gateProblems;

  ReceiptConfirmException(List<String> problems) {
    super(String.join(" ", problems));
    this.gateProblems = List.copyOf(problems);
  }

  /** Every hard block found, in reading order. */
  public List<String> problems() {
    return gateProblems;
  }
}
