package volkovandr.hauptbuch.importer.repository;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.importer.ImportPosting;

/**
 * Native-SQL access to {@code import_posting} (import.md §11; plan b3). A plain insert (the {@code
 * insert(record)} idiom) and a by-transaction select — row-mapping round-trips for the integration
 * tier (CLAUDE.md §6). Rows are removed only by cascade when their {@code import_file} is deleted.
 */
@Repository
public class ImportPostingRepository {

  private static final String TRANSACTION_ID = "transactionId";

  private final JdbcClient jdbcClient;

  ImportPostingRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /**
   * Stage one leg. Exactly one of {@code moneyCategoryPath} / {@code moneyAccountName} is set;
   * {@code funding} marks the synthesised funding leg (§7); {@code importPostingId}, {@code
   * mirrorPairId} and {@code counterAmount} on the draft are ignored — slice e links mirrors and
   * stamps the far amount directly in SQL ({@code ImportMirrorRepository}).
   */
  public void insert(ImportPosting posting) {
    jdbcClient
        .sql(
            """
            insert into import_posting
              (import_transaction_id, amount, note, money_category_path, money_account_name,
               class_name, funding)
            values
              (:transactionId, :amount, :note, :moneyCategoryPath, :moneyAccountName, :className,
               :funding)
            """)
        .param(TRANSACTION_ID, posting.importTransactionId())
        .param("amount", posting.amount())
        .param("note", posting.note())
        .param("moneyCategoryPath", posting.moneyCategoryPath())
        .param("moneyAccountName", posting.moneyAccountName())
        .param("className", posting.className())
        .param("funding", posting.funding())
        .update();
  }

  /** The staged legs of a transaction, in id order. */
  public List<ImportPosting> findByTransaction(long importTransactionId) {
    return jdbcClient
        .sql(
            "select * from import_posting where import_transaction_id = :transactionId"
                + " order by import_posting_id")
        .param(TRANSACTION_ID, importTransactionId)
        .query(ImportPosting.class)
        .list();
  }
}
