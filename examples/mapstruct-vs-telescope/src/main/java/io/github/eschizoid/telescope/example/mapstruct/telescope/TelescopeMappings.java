package io.github.eschizoid.telescope.example.mapstruct.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.example.mapstruct.domain.Customer;
import io.github.eschizoid.telescope.example.mapstruct.domain.LineItem;
import io.github.eschizoid.telescope.example.mapstruct.domain.Order;
import io.github.eschizoid.telescope.example.mapstruct.dto.CustomerDto;
import io.github.eschizoid.telescope.example.mapstruct.dto.OrderDto;
import java.math.BigDecimal;

/** The telescope side of the head-to-head: one typed path for the whole lifecycle. */
public final class TelescopeMappings {

  private TelescopeMappings() {}

  /**
   * Act 1 — the entire {@code Order -> OrderDto} mapping as one declarative value,
   * <em>bidirectional for free</em> ({@code forward} / {@code backward}). Recursion handles the
   * nested {@code Customer}, the {@code LineItem} list, and every same-named field; the single
   * {@code to(...)} override spells the one difference with method references — {@code
   * Customer::email} and {@code CustomerDto::contactEmail} — that the compiler checks and the IDE
   * refactors. Rename {@code Customer.email()} and this reference moves with the rename; nothing
   * goes stale.
   */
  public static final Mapper<Order, OrderDto> ORDER_MAPPER = Telescope.mapper(
    Order.class,
    OrderDto.class,
    to(Customer::email, CustomerDto::contactEmail)
  );

  /**
   * Act 2 — deep immutable update, kept as a reusable <em>path value</em> that mirrors {@code
   * ORDER_MAPPER}: a path is a thing you store, not a call you re-spell. The same {@code Telescope}
   * vocabulary that mapped {@code Order -> OrderDto} above navigates to every line item's price
   * here.
   */
  public static final Telescope<Order, BigDecimal> LINE_PRICES = Telescope.of(Order.class)
    .each(Order::lines)
    .field(LineItem::price);

  /**
   * Multiply every line item's price by {@code rate} and rebuild the whole immutable {@code Order}
   * graph in one typed pass, the original untouched. The clean structural gap: MapStruct maps
   * {@code A -> B}; it does not read, write, or update a value's interior.
   */
  public static Order applyRate(final Order order, final BigDecimal rate) {
    return LINE_PRICES.update(order, price -> price.multiply(rate));
  }
}
