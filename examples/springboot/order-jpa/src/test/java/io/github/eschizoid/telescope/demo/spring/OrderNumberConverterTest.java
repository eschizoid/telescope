package io.github.eschizoid.telescope.demo.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.demo.spring.domain.Order;
import io.github.eschizoid.telescope.demo.spring.persistence.OrderEntity;
import io.github.eschizoid.telescope.demo.spring.persistence.OrderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * When a JPA {@code @Convert} on the bean side rewrites the value asymmetrically, which
 * transformation wins the record↔entity round-trip telescope drives?
 *
 * <p>Setup: {@code OrderEntity.referenceCode} is annotated with {@code @Convert(converter =
 * UppercaseConverter.class)} — uppercase-on-write, pass-through-on-read. We forward an order with
 * lowercase {@code orderNumber}, save it, flush+clear so Hibernate re-hydrates the entity from the
 * DB rather than handing back the first-level-cache copy, then backward-map.
 *
 * <p>Expected: telescope reads whatever Hibernate parked into the bean field on hydration. Since
 * the DB column carries the uppercased value and {@code convertToEntityAttribute} is a
 * pass-through, the bean's {@code referenceCode} arrives as {@code "ORD-LOWER-1"}-after-uppercase →
 * {@code "ORD-LOWER-1"}. The rebuilt record's {@code orderNumber} matches.
 */
@SpringBootTest
@Transactional
class OrderNumberConverterTest {

  @Autowired
  private OrderRepository repository;

  @Autowired
  private Mapper<Order, OrderEntity> orderMapper;

  @PersistenceContext
  private EntityManager em;

  @Test
  void converterUppercasesOnPersistAndRecordSeesTheConvertedValue() {
    final var original = withOrderNumber(OrderFixtures.sampleOrder(), "ord-lower-1");

    final var entity = orderMapper.forward(original);
    repository.save(entity);
    final var id = entity.getId();

    em.flush();
    em.clear();

    final var reloaded = repository.findById(id).orElseThrow();
    final var rebuilt = orderMapper.backward(reloaded);

    assertThat(reloaded.getReferenceCode())
      .as("Hibernate-managed bean field after load reflects converter output (DB stored uppercase)")
      .isEqualTo("ORD-LOWER-1");
    assertThat(rebuilt.orderNumber())
      .as("Telescope rebuilds from the bean field — same value Hibernate exposed")
      .isEqualTo("ORD-LOWER-1");
  }

  @Test
  void inMemoryRoundTripPreservesOriginalCaseBecauseConverterOnlyFiresOnJdbc() {
    final var original = withOrderNumber(OrderFixtures.sampleOrder(), "ord-lower-2");
    final var entity = orderMapper.forward(original);
    final var rebuilt = orderMapper.backward(entity);

    assertThat(entity.getReferenceCode()).isEqualTo("ord-lower-2");
    assertThat(rebuilt.orderNumber()).isEqualTo("ord-lower-2");
  }

  private static Order withOrderNumber(final Order source, final String orderNumber) {
    return new Order(
      source.id(),
      orderNumber,
      source.customer(),
      source.shippingAddress(),
      source.billingAddress(),
      source.lineItems(),
      source.giftWrap(),
      source.metadata()
    );
  }
}
