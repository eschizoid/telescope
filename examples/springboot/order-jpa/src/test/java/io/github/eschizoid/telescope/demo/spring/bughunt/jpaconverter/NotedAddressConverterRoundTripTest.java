package io.github.eschizoid.telescope.demo.spring.bughunt.jpaconverter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.conversion.Mapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Slice question: when a JPA {@code @Convert} on the bean side rewrites the value asymmetrically,
 * which transformation wins the round-trip?
 *
 * <p>Set up: {@link UppercaseConverter} uppercases on write, passes through on read. We persist a
 * {@link NotedOrder} with {@code city = "Brooklyn"}, flush + clear so Hibernate re-loads from the
 * DB (rather than handing back the entity it already has in its first-level cache), then map back
 * to a {@link NotedOrder} via telescope.
 *
 * <p>Expected: telescope's view of the bean is the post-converter value Hibernate parked into the
 * field on hydration (i.e., {@code "BROOKLYN"}, because the DB column holds the uppercased value
 * and {@code convertToEntityAttribute} is a pass-through). So the rebuilt {@link NotedOrder} should
 * carry {@code "BROOKLYN"} — Hibernate's view wins.
 */
@SpringBootTest
@Transactional
class NotedAddressConverterRoundTripTest {

  @Autowired
  private NotedOrderRepository repository;

  @Autowired
  private Mapper<NotedOrder, NotedOrderEntity> mapper;

  @PersistenceContext
  private EntityManager em;

  @Test
  void roundTripReflectsHibernateView() {
    final var original = new NotedOrder(null, new NotedAddress("Brooklyn"));
    // Forward: record → entity → save → Hibernate writes "BROOKLYN" via the converter.
    final var entity = mapper.forward(original);
    repository.save(entity);
    final var id = entity.getId();

    em.flush();
    em.clear();

    // Re-load: Hibernate reads the column ("BROOKLYN") and pass-through converter populates the
    // bean field with "BROOKLYN". Telescope then reads the bean and rebuilds NotedOrder.
    final var reloaded = repository.findById(id).orElseThrow();
    final var rebuilt = mapper.backward(reloaded);

    assertThat(reloaded.getAddress().getCity())
      .as("Hibernate-managed bean field after load reflects converter output (DB stored BROOKLYN)")
      .isEqualTo("BROOKLYN");
    assertThat(rebuilt.address().city())
      .as("Telescope rebuilds from the bean field — same value Hibernate exposed")
      .isEqualTo("BROOKLYN");
    assertThat(rebuilt.id()).isEqualTo(id);
  }

  @Test
  void mapperRoundTripWithoutPersistencePreservesOriginalCase() {
    // Sanity: without a persistence step, telescope just round-trips the record/bean pair. No
    // converter fires (it only runs on JDBC binds/extracts), so "Brooklyn" stays "Brooklyn".
    final var original = new NotedOrder(null, new NotedAddress("Brooklyn"));
    final var entity = mapper.forward(original);
    final var rebuilt = mapper.backward(entity);
    assertThat(entity.getAddress().getCity()).isEqualTo("Brooklyn");
    assertThat(rebuilt.address().city()).isEqualTo("Brooklyn");
  }
}
