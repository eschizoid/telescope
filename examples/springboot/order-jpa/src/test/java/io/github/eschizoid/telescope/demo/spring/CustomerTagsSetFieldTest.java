package io.github.eschizoid.telescope.demo.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.Mapper;
import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.demo.spring.domain.Customer;
import io.github.eschizoid.telescope.demo.spring.domain.Order;
import io.github.eschizoid.telescope.demo.spring.persistence.OrderEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies that {@code Customer.tags: Set<String>} round-trips end-to-end and that the typed {@code
 * SetPath.each()} navigation works on the live domain.
 *
 * <p>Four angles:
 *
 * <ol>
 *   <li>In-memory same-typed {@code Set<String>} auto-lifts identically — {@code Iso.identity()} on
 *       the value side under {@code liftSet}.
 *   <li>{@code @ElementCollection}-backed tags survive save → flush → clear → load, preserving
 *       insertion order through the {@code LinkedHashSet} backing.
 *   <li>{@code Telescope.of(Order.class).field(Order::customer).setField(Customer::tags).each()} —
 *       the typed terminal updates every tag without container-shape dispatch at runtime.
 *   <li>Empty tag set round-trips as empty (not null).
 * </ol>
 */
@SpringBootTest
@Transactional
class CustomerTagsSetFieldTest {

  @Autowired
  private Mapper<Order, OrderEntity> orderMapper;

  @PersistenceContext
  private EntityManager em;

  @Test
  void inMemoryTagsRoundTripUsesIdentityIso() {
    final var src = OrderFixtures.sampleOrder();
    final var entity = orderMapper.forward(src);
    final var rebuilt = orderMapper.backward(entity);

    assertThat(entity.getCustomer().getTags()).containsExactly("vip", "newsletter", "wholesale");
    assertThat(rebuilt.customer().tags()).containsExactly("vip", "newsletter", "wholesale");
  }

  @Test
  void persistedTagsSurviveSaveLoadCycleWithOrderPreservation() {
    final var src = OrderFixtures.sampleOrder();
    final var entity = orderMapper.forward(src);
    em.persist(entity);
    em.flush();
    em.clear();

    final var reloaded = em.find(OrderEntity.class, entity.getId());
    final var rebuilt = orderMapper.backward(reloaded);

    assertThat(reloaded.getCustomer().getTags()).containsExactlyInAnyOrder("vip", "newsletter", "wholesale");
    assertThat(rebuilt.customer().tags()).containsExactlyInAnyOrder("vip", "newsletter", "wholesale");
  }

  @Test
  void setPathEachUpdatesEveryTagInPlace() {
    final var src = OrderFixtures.sampleOrder();

    final var shouty = Telescope.of(Order.class)
      .field(Order::customer)
      .setField(Customer::tags)
      .each()
      .update(src, String::toUpperCase);

    assertThat(shouty.customer().tags()).containsExactly("VIP", "NEWSLETTER", "WHOLESALE");
    assertThat(shouty.orderNumber()).isEqualTo(src.orderNumber());
    assertThat(shouty.customer().email()).isEqualTo(src.customer().email());
  }

  @Test
  void emptyTagSetRoundTrips() {
    final var emptyCustomer = new Customer(null, "Anon", "anon@example.com", Set.of());
    final var src = new Order(
      null,
      "ORD-EMPTY-TAGS",
      emptyCustomer,
      OrderFixtures.sampleOrder().shippingAddress(),
      OrderFixtures.sampleOrder().billingAddress(),
      OrderFixtures.sampleOrder().lineItems(),
      OrderFixtures.sampleOrder().giftWrap(),
      OrderFixtures.sampleOrder().metadata(),
      OrderFixtures.samplePayment()
    );

    final var entity = orderMapper.forward(src);
    final var rebuilt = orderMapper.backward(entity);

    assertThat(entity.getCustomer().getTags()).isEmpty();
    assertThat(rebuilt.customer().tags()).isEmpty();
  }

  @Test
  void insertionOrderPreservedThroughTheLiftedSetIso() {
    final var ordered = new LinkedHashSet<String>();
    ordered.add("first");
    ordered.add("second");
    ordered.add("third");
    final var customer = new Customer(null, "Bea", "bea@example.com", ordered);
    final var src = new Order(
      null,
      "ORD-ORDER-TAGS",
      customer,
      OrderFixtures.sampleOrder().shippingAddress(),
      OrderFixtures.sampleOrder().billingAddress(),
      OrderFixtures.sampleOrder().lineItems(),
      OrderFixtures.sampleOrder().giftWrap(),
      OrderFixtures.sampleOrder().metadata(),
      OrderFixtures.samplePayment()
    );
    final var entity = orderMapper.forward(src);
    final var rebuilt = orderMapper.backward(entity);

    assertThat(entity.getCustomer().getTags()).containsExactly("first", "second", "third");
    assertThat(rebuilt.customer().tags()).containsExactly("first", "second", "third");
  }
}
