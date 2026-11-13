package io.github.eschizoid.telescope.demo.spring;

import io.github.eschizoid.telescope.demo.order.Customer;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customers: a sign-up, and the support team's view of who placed an order. Both handlers take and
 * return the {@link Customer} entity. Spring's JSON mapper reads and writes it as a {@link
 * CustomerCard}, through the mapper {@code OrderServiceApplication} gives telescope-jackson's
 * module.
 */
@RestController
class CustomerController {

  private final CustomerRepository customers;
  private final OrderService orders;

  CustomerController(final CustomerRepository customers, final OrderService orders) {
    this.customers = customers;
    this.orders = orders;
  }

  @PostMapping("/customers")
  @Transactional
  ResponseEntity<Customer> signUp(@RequestBody final Customer customer) {
    if (customers.findByEmail(customer.getEmail()).isPresent()) {
      throw new IllegalStateException("a customer with email " + customer.getEmail() + " already exists");
    }
    // A sign-up creates a customer. An id in the body would make the save update that customer.
    customer.setId(null);
    final var saved = customers.save(customer);
    return ResponseEntity.created(URI.create("/customers/" + saved.getId())).body(saved);
  }

  /**
   * The customer behind an order. The order holds a lazy Hibernate proxy, which is written as the
   * {@code Customer} it stands for and loads its row once.
   */
  @GetMapping("/orders/{number}/customer")
  @Transactional(readOnly = true)
  Customer customerOf(@PathVariable final String number) {
    return orders.get(number).getCustomer();
  }
}
