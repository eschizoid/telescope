package io.github.eschizoid.telescope.demo.spring;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.demo.order.Customer;

/**
 * A customer's card and the customer entity, both ways: {@code forward} builds the entity a sign-up
 * creates, and {@code backward} reads an entity back into a card.
 *
 * <p>The field carries no {@code @Bridge}, so the mapper is built from its rows when the class
 * loads and runs on the runtime path; the processor still verifies the call when it compiles. That
 * is where every mapper can start, and a sign-up or a support lookup does not need generated code.
 * The runtime path reads the entity through its getters, so it reads a lazy Hibernate proxy as the
 * {@link Customer} it stands for and fetches its row once.
 */
final class CustomerCards {

  static final Mapper<CustomerCard, Customer> CARDS = Telescope.mapper(CustomerCard.class, Customer.class);

  private CustomerCards() {}
}
