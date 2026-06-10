package io.github.eschizoid.telescope.demo.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.demo.spring.domain.Order;
import io.github.eschizoid.telescope.demo.spring.persistence.CustomerEntity;
import io.github.eschizoid.telescope.demo.spring.persistence.OrderEntity;
import io.github.eschizoid.telescope.demo.spring.persistence.OrderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.hibernate.proxy.HibernateProxy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pins telescope's behaviour when {@link OrderEntity#getCustomer()} returns a Hibernate-managed
 * {@link HibernateProxy} instead of a real {@link CustomerEntity}. The {@code @ManyToOne(fetch =
 * LAZY)} on {@code OrderEntity.customer} is the source of the proxy; telescope's bean-side
 * reflection has to walk that proxy correctly during {@code Mapper.backward(...)} or downstream
 * code paths silently lose the codegen fast path / re-fetch the entity per probe.
 *
 * <p>Three angles:
 *
 * <ol>
 *   <li>The lazy customer is a {@code HibernateProxy} subclass before any touch — pins the runtime
 *       class identity so a regression that routes through the proxy class (instead of unwrapping
 *       to {@link CustomerEntity}) is visible.
 *   <li>The codegen-emitted {@code CustomerEntityTelescope} holder exists for the configured entity
 *       class but NOT for the proxy class; if any future dispatch site probes by {@code
 *       proxy.getClass()} it would silently miss the holder.
 *   <li>{@code Mapper.backward(loadedEntity)} initialises the lazy customer exactly once — counted
 *       via Hibernate {@link org.hibernate.stat.Statistics#getEntityFetchCount()}.
 * </ol>
 */
@SpringBootTest
class OrderCustomerLazyFetchTest {

  @Autowired
  private Mapper<Order, OrderEntity> orderMapper;

  @Autowired
  private OrderRepository repository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @PersistenceContext
  private EntityManager em;

  @Test
  void lazyCustomerIsHibernateProxyBeforeTouch() {
    final var savedId = seedAndFlushId();

    final var lazyCustomer = transactionTemplate.execute(status -> {
      em.clear();
      final var reloaded = repository.findById(savedId).orElseThrow();
      final var c = reloaded.getCustomer();
      assertThat(c).isInstanceOf(HibernateProxy.class);
      assertThat(c.getClass()).isNotEqualTo(CustomerEntity.class);
      assertThat(Hibernate.isInitialized(c)).isFalse();
      return c;
    });

    final var holderForProxy = classOrNull(lazyCustomer.getClass().getName() + "Telescope");
    final var holderForEntity = classOrNull(CustomerEntity.class.getName() + "Telescope");
    assertThat(holderForProxy).as("no holder is emitted for the runtime proxy class").isNull();
    assertThat(holderForEntity).as("holder IS present for the configured entity class").isNotNull();
  }

  @Test
  void backwardOnProxyInitializesCustomerExactlyOnce() {
    final Long savedId = transactionTemplate.execute(status -> {
      final var entity = orderMapper.forward(OrderFixtures.sampleOrder());
      repository.save(entity);
      em.flush();
      return entity.getId();
    });

    final var stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    final var wasEnabled = stats.isStatisticsEnabled();
    stats.setStatisticsEnabled(true);
    stats.clear();

    final long fetchesBefore = stats.getEntityFetchCount();
    final var result = transactionTemplate.execute(status -> {
      final var loaded = repository.findById(savedId).orElseThrow();
      assertThat(Hibernate.isInitialized(loaded.getCustomer())).isFalse();
      return orderMapper.backward(loaded);
    });
    final long fetchesAfter = stats.getEntityFetchCount();
    stats.setStatisticsEnabled(wasEnabled);

    assertThat(result).isNotNull();
    assertThat(result.customer().name()).isEqualTo("Alice Example");
    assertThat(fetchesAfter - fetchesBefore)
      .as("backward() should initialise the lazy customer exactly once, not per-component probe")
      .isEqualTo(1L);
  }

  private Long seedAndFlushId() {
    return transactionTemplate.execute(status -> {
      final var entity = orderMapper.forward(OrderFixtures.sampleOrder());
      repository.save(entity);
      em.flush();
      return entity.getId();
    });
  }

  private static Class<?> classOrNull(final String fqn) {
    try {
      return Class.forName(fqn);
    } catch (final ClassNotFoundException e) {
      return null;
    }
  }
}
