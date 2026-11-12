package io.github.eschizoid.telescope.demo.order;

import java.util.List;
import java.util.Optional;

/**
 * Where orders and customers are kept. The Spring app keeps them in a database through JPA; the
 * Quarkus app and the native image keep them in memory with {@link InMemoryOrders}.
 */
public interface Orders {
  Optional<Order> byNumber(String number);

  List<Order> all();

  Order save(Order order);

  Optional<Customer> customerByEmail(String email);

  Customer saveCustomer(Customer customer);
}
