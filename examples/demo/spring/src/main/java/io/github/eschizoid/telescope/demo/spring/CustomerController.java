package io.github.eschizoid.telescope.demo.spring;

import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Customers: a sign-up, and the support team's view of who placed an order. */
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
  ResponseEntity<CustomerCard> signUp(@RequestBody final CustomerCard card) {
    if (customers.findByEmail(card.email()).isPresent()) {
      throw new IllegalStateException("a customer with email " + card.email() + " already exists");
    }
    final var saved = customers.save(CustomerCards.CARDS.forward(new CustomerCard(null, card.name(), card.email())));
    return ResponseEntity.created(URI.create("/customers/" + saved.getId())).body(CustomerCards.CARDS.backward(saved));
  }

  /** The customer behind an order, read from the lazy association the order holds. */
  @GetMapping("/orders/{number}/customer")
  @Transactional(readOnly = true)
  CustomerCard customerOf(@PathVariable final String number) {
    return CustomerCards.CARDS.backward(orders.get(number).getCustomer());
  }
}
