package io.github.eschizoid.telescope.demo.spring.domain;

import io.github.eschizoid.telescope.annotations.Focus;
import java.util.List;
import java.util.Optional;

/**
 * The top-level domain record — exactly what the API accepts and returns as JSON. Jackson
 * deserialises POST request bodies straight into this shape; telescope then converts the whole
 * graph into a {@code persistence.OrderEntity} hierarchy for Hibernate to save.
 *
 * <p>Demonstrates the deep-nesting case telescope was designed for:
 *
 * <ul>
 *   <li>Scalar field: {@code orderNumber}
 *   <li>Nested record: {@code customer} → {@link Customer}
 *   <li>Nested record (×2 with the same type): {@code shippingAddress} / {@code billingAddress}
 *   <li>List of nested records: {@code lineItems} → {@code List<LineItem>}
 *   <li>Optional nested record: {@code giftWrap} → {@code Optional<Address>}
 * </ul>
 *
 * <p>The order id is nullable on the way in (the client doesn't know the database id when POSTing
 * a new order) and populated on the way back out. Mirrors the {@link Customer#id} pattern.
 */
@Focus
public record Order(
  Long id,
  String orderNumber,
  Customer customer,
  Address shippingAddress,
  Address billingAddress,
  List<LineItem> lineItems,
  Optional<Address> giftWrap
) {}
