package volkovandr.hauptbuch.operations;

/**
 * A page outside the register that hosts the split panel (fragments/split-panel), and so edits an
 * entry without booking it — the recurring template editor (data-model §14.1). The panel then posts
 * its round-trips and Save under {@code baseUrl}, drops the register's filter inputs and Void, and
 * offers Cancel and Delete links back to the host. The register itself passes no host.
 *
 * @param baseUrl the host's panel endpoints: {@code /add-line}, {@code /remove-line}, {@code
 *     /currency} and {@code /save} are posted under it
 * @param dateLabel the Date field's label on this host
 * @param cancelUrl where Cancel goes
 * @param deleteUrl what Delete posts to, or null to offer no Delete
 * @param deleteConfirm the question Delete asks first
 * @param totalsHelp a {@code .help} note beside the cross-currency totals, or null
 */
public record SplitPanelHost(
    String baseUrl,
    String dateLabel,
    String cancelUrl,
    String deleteUrl,
    String deleteConfirm,
    String totalsHelp) {}
