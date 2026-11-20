package io.github.eschizoid.telescope.quarkus.tests.fixtures;

import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/** The {@code Mapper} bean for {@code Order -> Receipt}, which renames the customer. */
@ApplicationScoped
public class ReceiptMappers {

  @Produces
  Mapper<Order, Receipt> receipts() {
    return Telescope.mapper(Order.class, Receipt.class, to(Order::customer, Receipt::buyer));
  }
}
