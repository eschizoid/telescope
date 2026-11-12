package io.github.eschizoid.telescope.demo.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.demo.order.Customer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.hibernate.proxy.HibernateProxy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An order's customer is a lazy association, so until something reads it Hibernate hands out a
 * proxy: a generated subclass of {@link Customer} whose fields are empty until it loads its row.
 * The runtime card mapper reads such a proxy as the {@code Customer} it stands for, through getters
 * looked up on {@code Customer} itself, and loads the row once.
 */
@SpringBootTest
class CustomerProxyTest {

  @Autowired
  private OrderRepository orders;

  @Autowired
  private TransactionTemplate transaction;

  @PersistenceContext
  private EntityManager entityManager;

  @Test
  @DisplayName("an order's customer is an uninitialised proxy, and a bean path reads it as a Customer")
  void theLazyCustomerIsAProxyReadAsItsPersistentClass() {
    transaction.executeWithoutResult(status -> {
      entityManager.clear();
      final var customer = orders.findByNumber("SO-1002").orElseThrow().getCustomer();
      assertThat(customer).isInstanceOf(HibernateProxy.class);
      assertThat(customer.getClass()).isNotEqualTo(Customer.class);
      assertThat(Hibernate.isInitialized(customer)).isFalse();
      assertThat(Telescope.ofBean(Customer.class).field(Customer::getEmail).read(customer)).isEqualTo(
        "grace@example.com"
      );
    });
  }

  @Test
  @DisplayName("backward over an uninitialised customer proxy gives the customer's values and loads its row once")
  void backwardOverTheProxyLoadsTheCustomerOnce() {
    final var statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    final var wasEnabled = statistics.isStatisticsEnabled();
    statistics.setStatisticsEnabled(true);
    try {
      final var card = transaction.execute(status -> {
        entityManager.clear();
        final var customer = orders.findByNumber("SO-1002").orElseThrow().getCustomer();
        assertThat(Hibernate.isInitialized(customer)).isFalse();
        statistics.clear();
        final var read = CustomerCards.CARDS.backward(customer);
        assertThat(statistics.getEntityFetchCount()).as("one load of the customer's row").isEqualTo(1L);
        return read;
      });
      assertThat(card.name()).isEqualTo("Grace Hopper");
      assertThat(card.email()).isEqualTo("grace@example.com");
      assertThat(card.id()).isNotNull();
    } finally {
      statistics.setStatisticsEnabled(wasEnabled);
    }
  }
}
