package volkovandr.hauptbuch.ledger;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;

/**
 * A tiny parameterized data-insertion helper for the SQL-logic suite. The tests in this suite craft
 * ledger rows by hand (so they can deliberately build invalid books the engine would reject) but do
 * so through {@link PreparedStatement}s rather than concatenated SQL — keeping the inserts
 * injection- safe and the suite focused on the query under test, not on string-building.
 *
 * <p>Owns a connection with autocommit off; {@link #close()} rolls it back, so crafted data never
 * leaks into the reused container.
 */
final class TestLedger implements AutoCloseable {

  private final Connection conn;

  TestLedger(Connection conn) throws SQLException {
    this.conn = conn;
    // EUR is the base currency for the crafted books (data-model §3.8 precondition).
    try (PreparedStatement ps =
        conn.prepareStatement("update settings set base_currency = 'EUR' where settings_id = 1")) {
      ps.executeUpdate();
    }
  }

  Connection connection() {
    return conn;
  }

  long insertAccount(String name, String type, String currency, Long parentId) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into account (name, type, currency_code, parent_id) "
                + "values (?, ?, ?, ?) returning account_id")) {
      ps.setString(1, name);
      ps.setString(2, type);
      ps.setString(3, currency);
      if (parentId == null) {
        ps.setNull(4, Types.BIGINT);
      } else {
        ps.setLong(4, parentId);
      }
      return single(ps);
    }
  }

  long insertTransaction(String date) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into transaction (date) values (?) returning transaction_id")) {
      ps.setObject(1, LocalDate.parse(date));
      return single(ps);
    }
  }

  void insertPosting(long txnId, long accountId, BigDecimal amount, BigDecimal baseAmount)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into posting (transaction_id, account_id, amount, base_amount) "
                + "values (?, ?, ?, ?)")) {
      ps.setLong(1, txnId);
      ps.setLong(2, accountId);
      ps.setBigDecimal(3, amount);
      if (baseAmount == null) {
        ps.setNull(4, Types.NUMERIC);
      } else {
        ps.setBigDecimal(4, baseAmount);
      }
      ps.executeUpdate();
    }
  }

  void softDeleteTransaction(long txnId) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "update transaction set deleted_at = now() where transaction_id = ?")) {
      ps.setLong(1, txnId);
      ps.executeUpdate();
    }
  }

  @Override
  public void close() throws SQLException {
    conn.rollback();
    conn.close();
  }

  private static long single(PreparedStatement ps) throws SQLException {
    try (ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
