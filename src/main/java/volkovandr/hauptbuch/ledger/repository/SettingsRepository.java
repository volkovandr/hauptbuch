package volkovandr.hauptbuch.ledger.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.ledger.Settings;

/**
 * Native-SQL access to the single-row {@code settings} entity (data-model §3.8). The row always
 * exists (seeded by V1 with a null base currency); this reads and updates it. The write-once guard
 * on {@code base_currency} is enforced in {@link SettingsService}, not here.
 */
@Repository
public class SettingsRepository {

  private static final String BASE_CURRENCY = "baseCurrency";
  private static final String DISPLAY_NAME = "displayName";

  private final JdbcClient jdbcClient;

  SettingsRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** Load the single settings row (base currency + display name). */
  public Settings load() {
    return jdbcClient
        .sql("select base_currency, display_name from settings where settings_id = 1")
        .query(Settings.class)
        .single();
  }

  /** Set the base currency (the write-once guard lives in {@code SettingsService}, not here). */
  public void updateBaseCurrency(String baseCurrency) {
    jdbcClient
        .sql("update settings set base_currency = :baseCurrency where settings_id = 1")
        .param(BASE_CURRENCY, baseCurrency)
        .update();
  }

  /** Set the freely-editable display name backing the greeting. */
  public void updateDisplayName(String displayName) {
    jdbcClient
        .sql("update settings set display_name = :displayName where settings_id = 1")
        .param(DISPLAY_NAME, displayName)
        .update();
  }
}
