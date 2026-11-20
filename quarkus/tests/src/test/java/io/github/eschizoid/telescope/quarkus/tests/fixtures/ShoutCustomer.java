package io.github.eschizoid.telescope.quarkus.tests.fixtures;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.annotations.TelescopeTransformer;
import io.github.eschizoid.telescope.inject.TelescopeTransformation;
import io.github.eschizoid.telescope.inject.Transformation;
import java.util.Locale;

/** Upper-cases an order's customer, so a body mapped through {@link SummaryProjection} shows it. */
@TelescopeTransformer
public interface ShoutCustomer extends TelescopeTransformation<Order, String> {
  @Override
  default Telescope<Order, String> path() {
    return Telescope.of(Order.class).field(Order::customer);
  }

  @Override
  default Transformation<String> transform() {
    return new Transformation<>(null, customer -> customer.toUpperCase(Locale.ROOT));
  }
}
