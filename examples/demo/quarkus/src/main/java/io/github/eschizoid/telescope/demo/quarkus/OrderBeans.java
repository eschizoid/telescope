package io.github.eschizoid.telescope.demo.quarkus;

import io.github.eschizoid.telescope.demo.order.InMemoryOrders;
import io.github.eschizoid.telescope.demo.order.OrderDesk;
import io.github.eschizoid.telescope.demo.order.Orders;
import io.github.eschizoid.telescope.demo.order.SampleData;
import io.github.eschizoid.telescope.demo.order.Seller;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Clock;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** The order service's beans. This app keeps its orders in memory. */
@ApplicationScoped
public class OrderBeans {

  @Produces
  @Singleton
  Orders orders() {
    return new InMemoryOrders();
  }

  @Produces
  @Singleton
  Seller seller(
    @ConfigProperty(name = "demo.seller.name") final String name,
    @ConfigProperty(name = "demo.seller.vat-id") final String vatId
  ) {
    return new Seller(name, vatId);
  }

  @Produces
  @Singleton
  OrderDesk orderDesk(final Orders orders, final Seller seller) {
    return new OrderDesk(orders, seller, Clock.systemDefaultZone());
  }

  void loadSampleData(@Observes final StartupEvent started, final OrderDesk desk, final Orders orders) {
    SampleData.load(desk, orders);
  }
}
