package volkovandr.hauptbuch.debts.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.debts.Person;

/**
 * Repository for {@link Person} entities. All queries scope to `deleted_at is null` (live persons)
 * unless otherwise noted.
 */
@Repository
public class PersonRepository {

  private final JdbcClient jdbcClient;

  PersonRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** Insert a person and return the persisted row with its generated ID. */
  public Person insert(String name) {
    return jdbcClient
        .sql("insert into person (name) values (:n) returning *")
        .param("n", name)
        .query(Person.class)
        .single();
  }

  /** Fetch a live person by ID; returns empty if deleted or absent. */
  public Optional<Person> findById(Long personId) {
    return jdbcClient
        .sql("select * from person where person_id = :id and deleted_at is null")
        .param("id", personId)
        .query(Person.class)
        .optional();
  }

  /**
   * Fetch a person by ID whether live or soft-deleted; returns empty only if absent. Used for
   * display lookups (e.g. resolving an old transaction's funding leg back to a person's current
   * name, plan stage 8b) where a since-deleted person must still resolve, not vanish.
   */
  public Optional<Person> findByIdIncludingDeleted(Long personId) {
    return jdbcClient
        .sql("select * from person where person_id = :id")
        .param("id", personId)
        .query(Person.class)
        .optional();
  }

  /** Fetch all live persons, ordered by name. */
  public List<Person> findAllLive() {
    return jdbcClient
        .sql("select * from person where deleted_at is null order by name")
        .query(Person.class)
        .list();
  }

  /** Fetch all live persons matching a name (exact match or substring search). */
  public List<Person> findByNameContaining(String nameFragment) {
    return jdbcClient
        .sql(
            """
            select * from person
            where deleted_at is null
              and lower(name) like lower(:fragment)
            order by name
            """)
        .param("fragment", "%" + nameFragment + "%")
        .query(Person.class)
        .list();
  }

  /**
   * Fetch a live person by exact name match; returns empty if no match or all matching persons are
   * deleted.
   */
  public Optional<Person> findByNameExact(String name) {
    return jdbcClient
        .sql("select * from person where name = :n and deleted_at is null")
        .param("n", name)
        .query(Person.class)
        .optional();
  }

  /**
   * Fetch every person (live or soft-deleted) with this exact name — the raw material for the entry
   * dock's match-status classification (plan stage 8b): more than one live row is ambiguous, zero
   * live rows with at least one deleted row needs a revival decision, and no rows at all is a
   * genuinely new person. Ordered live-first, then most-recently-deleted first.
   */
  public List<Person> findAllByNameExact(String name) {
    return jdbcClient
        .sql(
            """
            select * from person where name = :n
            order by deleted_at is null desc, deleted_at desc
            """)
        .param("n", name)
        .query(Person.class)
        .list();
  }

  /**
   * Fetch the sole live person with this exact name, or a soft-deleted one if no live person
   * exists. Used for revival confirmation — if a name is re-entered and there is only one
   * soft-deleted row, we ask for confirmation before reviving it. When multiple soft-deleted rows
   * exist, returns the most recently deleted one.
   */
  public Optional<Person> findByNameExactIncludingDeleted(String name) {
    return jdbcClient
        .sql(
            """
            select * from person where name = :n
            order by deleted_at is null desc, deleted_at desc
            limit 1
            """)
        .param("n", name)
        .query(Person.class)
        .optional();
  }

  /** Update the person's name. Soft-deleted persons may be renamed (not on the live-only path). */
  public Person updateName(Long personId, String newName) {
    return jdbcClient
        .sql("update person set name = :n where person_id = :id returning *")
        .param("n", newName)
        .param("id", personId)
        .query(Person.class)
        .single();
  }

  /** Soft-delete a person. Soft-delete is reversible. */
  public void softDelete(Long personId) {
    jdbcClient
        .sql("update person set deleted_at = now() where person_id = :id")
        .param("id", personId)
        .update();
  }

  /** Revive a soft-deleted person (set deleted_at to NULL). */
  public Person revive(Long personId) {
    return jdbcClient
        .sql("update person set deleted_at = null where person_id = :id returning *")
        .param("id", personId)
        .query(Person.class)
        .single();
  }
}
