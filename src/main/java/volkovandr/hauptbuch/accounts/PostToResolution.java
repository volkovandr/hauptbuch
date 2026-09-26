package volkovandr.hauptbuch.accounts;

import java.util.List;

/**
 * The outcome of reading a picked or typed own-account label back to an account of the post-to set
 * ({@link PostToAccountService#resolve}, issue transaction-register-ui/25): either the one account
 * it names, or a refusal that says <em>why</em> — so a group, an ambiguous name, and an unknown one
 * are never all reported as "no such account".
 */
public sealed interface PostToResolution {

  /** The text named exactly one account of the post-to set. */
  record Resolved(PostToAccount account) implements PostToResolution {}

  /** A refusal, carrying the message a picker shows inline. */
  sealed interface Refused extends PostToResolution {

    /** The inline message for the field. */
    String message();
  }

  /**
   * No open own account has that name or path.
   *
   * @param name the name looked up
   */
  record NotFound(String name) implements Refused {
    @Override
    public String message() {
      return "No open account named '" + name + "'";
    }
  }

  /**
   * The name matches several accounts; the user must pick one by its label.
   *
   * @param labels every matching account's label, sorted
   */
  record Ambiguous(List<String> labels) implements Refused {

    /** Defensively copy the labels. */
    public Ambiguous {
      labels = List.copyOf(labels);
    }

    @Override
    public String message() {
      return "More than one account matches — pick one of: " + String.join(", ", labels);
    }
  }

  /**
   * The text names a group — an account with children, reached only through them (leaves-only,
   * data-model §5).
   *
   * @param path the group's path
   */
  record Group(String path) implements Refused {
    @Override
    public String message() {
      return "'" + path + "' is a group — pick one of its accounts";
    }
  }

  /**
   * The name matches one account, but the typed currency suffix is not its currency. Refused rather
   * than ignored: dropping the suffix would book to an account the user did not name.
   *
   * @param label the matching account's label
   * @param typedCurrency the currency the suffix named
   */
  record WrongCurrency(String label, String typedCurrency) implements Refused {
    @Override
    public String message() {
      return "'" + label + "' is not in " + typedCurrency;
    }
  }
}
