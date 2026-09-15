package volkovandr.hauptbuch.categories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.categories.repository.TagRepository;

/**
 * SQL-logic tier (CLAUDE.md §6): {@link TagRepository#findLiveWithDepth}'s recursive-CTE walk, the
 * Tag filter section's hierarchy picker (reporting.md §11a.5, plan stage d3-4), mirroring {@code
 * AccountTreeSqlLogicTest}'s coverage of {@code AccountRepository#findLiveByTypesWithDepth}.
 *
 * <p>This suite boots a Spring context (via {@link TestcontainersConfiguration}) so the query under
 * test is the <em>real</em> repository SQL, not a copy pasted into the test; raw {@link JdbcClient}
 * is used only to seed crafted trees. {@code @Transactional} rolls each test back on the reused
 * container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class TagTreeSqlLogicTest {

  @Autowired JdbcClient jdbcClient;
  @Autowired TagRepository tagRepository;

  private long insertRoot(String name) {
    return jdbcClient
        .sql("insert into tag (name) values (:n) returning tag_id")
        .param("n", name)
        .query(Long.class)
        .single();
  }

  private long insertChild(String name, long parentId) {
    return jdbcClient
        .sql("insert into tag (name, parent_id) values (:n, :p) returning tag_id")
        .param("n", name)
        .param("p", parentId)
        .query(Long.class)
        .single();
  }

  private void softDelete(long tagId) {
    jdbcClient
        .sql("update tag set deleted_at = now() where tag_id = :id")
        .param("id", tagId)
        .update();
  }

  @Test
  void depthWalkGoesArbitrarilyDeepAndOrdersDepthFirst() {
    long car = insertRoot("Car");
    long audi = insertChild("Audi", car);
    long a4 = insertChild("A4", audi);
    long vw = insertChild("VW", car);

    List<TagNode> nodes = tagRepository.findLiveWithDepth();

    assertThat(nodes)
        .extracting(n -> n.tag().tagId(), TagNode::depth)
        .containsExactly(tuple(car, 0), tuple(audi, 1), tuple(a4, 2), tuple(vw, 1));
  }

  @Test
  void depthWalkPrunesSoftDeletedNodesAndTheirDescendants() {
    long car = insertRoot("Car");
    long audi = insertChild("Audi", car);
    long a4 = insertChild("A4", audi);
    softDelete(audi);

    List<TagNode> nodes = tagRepository.findLiveWithDepth();

    assertThat(nodes).extracting(n -> n.tag().tagId()).contains(car).doesNotContain(audi, a4);
  }

  @Test
  void depthWalkOrdersSiblingsAlphabeticallyWithinEachLevel() {
    long car = insertRoot("Car");
    long vw = insertChild("VW", car);
    long audi = insertChild("Audi", car);
    long bmw = insertChild("BMW", car);

    List<TagNode> nodes = tagRepository.findLiveWithDepth();

    assertThat(nodes).extracting(n -> n.tag().tagId()).containsExactly(car, audi, bmw, vw);
  }

  @Test
  void depthWalkReturnsEmptyWhenNoTagExists() {
    assertThat(tagRepository.findLiveWithDepth()).isEmpty();
  }
}
