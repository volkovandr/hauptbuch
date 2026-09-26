package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.analytics.repository.LeafGrainRepository;
import volkovandr.hauptbuch.analytics.repository.TopLevelNode;

/**
 * Integration tier (CLAUDE.md §6): {@link LeafGrainRepository#payees()}'s round trip — the raw
 * export's payee labels (reporting.md §13). The leaf-grain queries whose logic lives in SQL are
 * {@code RawReportSqlLogicTest}'s.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class LeafGrainRepositoryIntegrationTest {

  @Autowired LeafGrainRepository leafGrainRepository;
  @Autowired JdbcClient jdbcClient;

  private long insertPayee(String name) {
    return jdbcClient
        .sql("insert into payee (name) values (:n) returning payee_id")
        .param("n", name)
        .query(Long.class)
        .single();
  }

  @Test
  void payeesAreTheLiveOnesKeyedByTheirId() {
    long shop = insertPayee("ShopAaa");
    long gone = insertPayee("ShopBbb");
    jdbcClient
        .sql("update payee set deleted_at = now() where payee_id = :p")
        .param("p", gone)
        .update();

    assertThat(leafGrainRepository.payees())
        .contains(new TopLevelNode(String.valueOf(shop), "ShopAaa", null))
        .extracting(TopLevelNode::key)
        .doesNotContain(String.valueOf(gone));
  }
}
