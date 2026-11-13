package io.github.eschizoid.telescope.demo.order;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** {@link Orders} kept in memory, for the apps that run without a database. */
public final class InMemoryOrders implements Orders {

  private final Map<String, Order> orders = new LinkedHashMap<>();
  private final Map<String, Customer> customers = new LinkedHashMap<>();
  private final AtomicLong ids = new AtomicLong();

  @Override
  public synchronized Optional<Order> byNumber(final String number) {
    return Optional.ofNullable(orders.get(number));
  }

  @Override
  public synchronized List<Order> all() {
    return new ArrayList<>(orders.values());
  }

  @Override
  public synchronized Order save(final Order order) {
    if (order.getId() == null) order.setId(ids.incrementAndGet());
    orders.put(order.getNumber(), order);
    return order;
  }

  @Override
  public synchronized Optional<Customer> customerByEmail(final String email) {
    return Optional.ofNullable(customers.get(email));
  }

  @Override
  public synchronized Customer saveCustomer(final Customer customer) {
    if (customer.getId() == null) customer.setId(ids.incrementAndGet());
    customers.put(customer.getEmail(), customer);
    return customer;
  }
}
