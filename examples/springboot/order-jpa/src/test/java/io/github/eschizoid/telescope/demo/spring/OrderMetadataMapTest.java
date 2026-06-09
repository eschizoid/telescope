package io.github.eschizoid.telescope.demo.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.demo.spring.domain.Order;
import io.github.eschizoid.telescope.demo.spring.persistence.OrderEntity;
import io.github.eschizoid.telescope.demo.spring.persistence.OrderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies that {@code Order.metadata: Map<String, String>} round-trips through both the
 * telescope-driven record↔entity bridge and Hibernate's {@code @ElementCollection} persistence.
 *
 * <p>Three angles:
 *
 * <ol>
 *   <li>In-memory same-typed {@code Map<String, String>} auto-lifts identically — {@code
 *       Iso.identity()} on the value side under {@code liftMapValues}.
 *   <li>{@code @ElementCollection}-backed metadata survives a save → flush → clear → load
 *       round-trip without dropping entries or reshuffling insertion order beyond what {@code
 *       LinkedHashMap} guarantees.
 *   <li>An empty metadata map round-trips as empty (not null) — the {@code HashMap}-initialised
 *       entity field is the right backing for "no entries yet."
 * </ol>
 */
@SpringBootTest
@Transactional
class OrderMetadataMapTest {

  @Autowired
  private Mapper<Order, OrderEntity> orderMapper;

  @Autowired
  private OrderRepository repository;

  @PersistenceContext
  private EntityManager em;

  @Test
  void inMemoryMetadataRoundTripUsesIdentityIso() {
    final var src = OrderFixtures.sampleOrder();
    final var entity = orderMapper.forward(src);
    final var rebuilt = orderMapper.backward(entity);

    assertThat(entity.getMetadata()).containsExactlyEntriesOf(src.metadata());
    assertThat(rebuilt.metadata()).containsExactlyEntriesOf(src.metadata());
  }

  @Test
  void persistedMetadataSurvivesSaveLoadCycle() {
    final var src = OrderFixtures.sampleOrder();
    final var entity = orderMapper.forward(src);
    repository.save(entity);
    final var id = entity.getId();

    em.flush();
    em.clear();

    final var reloaded = repository.findById(id).orElseThrow();
    final var rebuilt = orderMapper.backward(reloaded);

    assertThat(reloaded.getMetadata()).containsAllEntriesOf(src.metadata());
    assertThat(rebuilt.metadata()).containsAllEntriesOf(src.metadata());
  }

  @Test
  void emptyMetadataMapRoundTrips() {
    final var src = OrderFixtures.patchOrderNumberOnly("ORD-EMPTY-META");
    final var entity = orderMapper.forward(src);
    final var rebuilt = orderMapper.backward(entity);

    assertThat(entity.getMetadata()).isEmpty();
    assertThat(rebuilt.metadata()).isEmpty();
  }

  @Test
  void insertionOrderPreservedAcrossRoundTrip() {
    final var ordered = new LinkedHashMap<String, String>();
    ordered.put("first", "1");
    ordered.put("second", "2");
    ordered.put("third", "3");

    final var src = new Order(
      null,
      "ORD-ORDERED",
      OrderFixtures.sampleOrder().customer(),
      OrderFixtures.sampleOrder().shippingAddress(),
      OrderFixtures.sampleOrder().billingAddress(),
      OrderFixtures.sampleOrder().lineItems(),
      OrderFixtures.sampleOrder().giftWrap(),
      ordered,
      OrderFixtures.samplePayment()
    );
    final var entity = orderMapper.forward(src);
    final var rebuilt = orderMapper.backward(entity);

    assertThat(entity.getMetadata().keySet()).containsExactly("first", "second", "third");
    assertThat(rebuilt.metadata().keySet()).containsExactly("first", "second", "third");
  }
}
