package io.github.eschizoid.telescope.demo.spring;

import io.github.eschizoid.telescope.demo.order.Customer;
import io.github.eschizoid.telescope.demo.order.Order;
import io.github.eschizoid.telescope.demo.order.Orders;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** {@link Orders} kept in the database through Spring Data JPA. */
@Component
class JpaOrders implements Orders {

  private final OrderRepository orders;
  private final CustomerRepository customers;

  JpaOrders(final OrderRepository orders, final CustomerRepository customers) {
    this.orders = orders;
    this.customers = customers;
  }

  @Override
  public Optional<Order> byNumber(final String number) {
    return orders.findByNumber(number);
  }

  @Override
  public List<Order> all() {
    return orders.findAllByOrderByNumber();
  }

  @Override
  public Order save(final Order order) {
    return orders.save(order);
  }

  @Override
  public Optional<Customer> customerByEmail(final String email) {
    return customers.findByEmail(email);
  }

  @Override
  public Customer saveCustomer(final Customer customer) {
    return customers.save(customer);
  }
}
