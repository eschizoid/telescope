package io.github.eschizoid.telescope.example.mapstruct.telescope;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.example.mapstruct.domain.Customer;
import io.github.eschizoid.telescope.example.mapstruct.domain.LineItem;
import io.github.eschizoid.telescope.example.mapstruct.domain.Order;
import io.github.eschizoid.telescope.example.mapstruct.dto.CustomerDto;
import io.github.eschizoid.telescope.example.mapstruct.dto.OrderDto;
import java.math.BigDecimal;

/**
 * The telescope side of the head-to-head. Each mapper is a {@code @Bridge} field whose rows name
 * only what differs between the two types, and {@code telescope-codegen} compiles each field's rows
 * into a plain Java class at build time, as MapStruct's processor writes {@code
 * OrderMapStructMapperImpl}.
 */
public final class TelescopeMappings {

  private TelescopeMappings() {}

  /**
   * The {@code Customer -> CustomerDto} pair, with its one rename spelled with method references:
   * {@code Customer::email} and the bean getter {@code CustomerDto::getContactEmail}, which the
   * compiler checks and an IDE rename updates. The name field maps by name. A pair is declared
   * once, so every mapper that reaches a customer, {@link #ORDER_MAPPER} included, converts it
   * through the bridge generated from this field.
   */
  @Bridge
  public static final Mapper<Customer, CustomerDto> CUSTOMER_MAPPER = mapper(
    Customer.class,
    CustomerDto.class,
    to(Customer::email, CustomerDto::getContactEmail)
  );

  /**
   * The whole {@code Order -> OrderDto} mapping, both ways ({@code forward} and {@code backward}).
   * The source side is immutable records and the target side mutable JavaBeans; the generated
   * bridge builds the beans through their no-argument constructors and setters, and reads them back
   * through their getters. Every field has the same name on both sides except the customer's email,
   * which {@link #CUSTOMER_MAPPER} declares, so this field needs no row.
   */
  @Bridge
  public static final Mapper<Order, OrderDto> ORDER_MAPPER = mapper(Order.class, OrderDto.class);

  /**
   * A deep immutable update, kept as a reusable path value: the same {@code Telescope} vocabulary
   * that maps {@code Order -> OrderDto} above navigates to every line item's price here.
   */
  public static final Telescope<Order, BigDecimal> LINE_PRICES = Telescope.of(Order.class)
    .each(Order::lines)
    .field(LineItem::price);

  /**
   * Multiply every line item's price by {@code rate} and rebuild the whole immutable {@code Order}
   * graph in one typed pass, the original untouched. MapStruct maps {@code A -> B}; it does not
   * read, write, or update a value's interior.
   */
  public static Order applyRate(final Order order, final BigDecimal rate) {
    return LINE_PRICES.update(order, price -> price.multiply(rate));
  }
}
