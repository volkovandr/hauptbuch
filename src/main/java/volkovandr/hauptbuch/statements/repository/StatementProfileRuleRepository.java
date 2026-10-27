package volkovandr.hauptbuch.statements.repository;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.statements.ParseRule;

/**
 * Native-SQL access to {@code statement_profile_rule} (data-model §15): the ordered parse rules of
 * a PDF (rules) profile. The rules are always written as a whole list, so a save replaces them.
 * Plain by-profile reads and writes, so the round-trips live in the integration tier (CLAUDE.md
 * §6).
 */
@Repository
public class StatementProfileRuleRepository {

  private static final String PROFILE_ID = "statementProfileId";

  private final JdbcClient jdbcClient;

  StatementProfileRuleRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** The profile's rules, in their order. */
  public List<ParseRule> findByProfile(long statementProfileId) {
    return jdbcClient
        .sql(
            """
            select kind, pattern, pattern_end
            from statement_profile_rule
            where statement_profile_id = :statementProfileId
            order by sort_order
            """)
        .param(PROFILE_ID, statementProfileId)
        .query(ParseRule.class)
        .list();
  }

  /** Replace the profile's rules with {@code rules}, numbered in list order. */
  public void replace(long statementProfileId, List<ParseRule> rules) {
    jdbcClient
        .sql("delete from statement_profile_rule where statement_profile_id = :statementProfileId")
        .param(PROFILE_ID, statementProfileId)
        .update();
    for (int i = 0; i < rules.size(); i++) {
      ParseRule rule = rules.get(i);
      jdbcClient
          .sql(
              """
              insert into statement_profile_rule
                (statement_profile_id, sort_order, kind, pattern, pattern_end)
              values (:statementProfileId, :sortOrder, :kind, :pattern, :patternEnd)
              """)
          .param(PROFILE_ID, statementProfileId)
          .param("sortOrder", i)
          .param("kind", rule.kind())
          .param("pattern", rule.pattern())
          .param("patternEnd", rule.patternEnd())
          .update();
    }
  }
}
