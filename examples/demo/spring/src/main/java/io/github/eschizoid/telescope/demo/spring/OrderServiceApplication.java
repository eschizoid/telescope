package io.github.eschizoid.telescope.demo.spring;

import io.github.eschizoid.telescope.demo.order.Order;
import io.github.eschizoid.telescope.demo.order.OrderDesk;
import io.github.eschizoid.telescope.demo.order.Orders;
import io.github.eschizoid.telescope.demo.order.SampleData;
import io.github.eschizoid.telescope.demo.order.Seller;
import io.github.eschizoid.telescope.spring.jackson.TelescopeModuleCustomizer;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.support.TransactionTemplate;

/** The order service on Spring Boot, with its orders in an in-memory H2 database. */
@SpringBootApplication
@EntityScan(basePackageClasses = Order.class)
public class OrderServiceApplication {

  public static void main(final String[] args) {
    SpringApplication.run(OrderServiceApplication.class, args);
  }

  @Bean
  Clock clock() {
    return Clock.systemDefaultZone();
  }

  @Bean
  Seller seller(@Value("${demo.seller.name}") final String name, @Value("${demo.seller.vat-id}") final String vatId) {
    return new Seller(name, vatId);
  }

  /**
   * Spring's JSON mapper reads and writes a {@code Customer} as a {@link CustomerCard}, through the
   * card mapper. A lazy customer proxy is written the same way, as the customer it stands for.
   */
  @Bean
  TelescopeModuleCustomizer customerCards() {
    return module -> module.add(CustomerCards.CARDS);
  }

  @Bean
  OrderDesk orderDesk(final Orders orders, final Seller seller, final Clock clock) {
    return new OrderDesk(orders, seller, clock);
  }

  @Bean
  ApplicationRunner sampleData(final OrderDesk desk, final Orders orders, final TransactionTemplate transaction) {
    return args -> transaction.executeWithoutResult(status -> SampleData.load(desk, orders));
  }
}
