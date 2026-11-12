package io.github.eschizoid.telescope.demo.order;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.Telescope.mapperForward;
import static io.github.eschizoid.telescope.Telescope.path;
import static io.github.eschizoid.telescope.mapping.Mapping.compute;
import static io.github.eschizoid.telescope.mapping.Mapping.drop;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.toFormatted;
import static io.github.eschizoid.telescope.mapping.Mapping.toFormattedNumber;
import static io.github.eschizoid.telescope.mapping.Mapping.toOneWay;
import static io.github.eschizoid.telescope.mapping.Mapping.toOrElse;
import static io.github.eschizoid.telescope.mapping.Mapping.via;
import static io.github.eschizoid.telescope.mapping.Mapping.when;
import static io.github.eschizoid.telescope.mapping.MergeStep.auto;
import static io.github.eschizoid.telescope.mapping.MergeStep.from;
import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.ADDERS;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBean;

import io.github.eschizoid.telescope.Sources;
import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.ForwardMapper;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.demo.api.BankTransferDto;
import io.github.eschizoid.telescope.demo.api.CardDto;
import io.github.eschizoid.telescope.demo.api.InvoiceDto;
import io.github.eschizoid.telescope.demo.api.OrderChanges;
import io.github.eschizoid.telescope.demo.api.OrderDto;
import io.github.eschizoid.telescope.demo.api.OrderDtoTelescope;
import io.github.eschizoid.telescope.demo.api.OrderLineDto;
import io.github.eschizoid.telescope.demo.api.OrderLineV2Dto;
import io.github.eschizoid.telescope.demo.api.OrderSummary;
import io.github.eschizoid.telescope.demo.api.OrderV2Dto;
import io.github.eschizoid.telescope.demo.api.PlaceOrder;

/**
 * Every mapper the order service uses whose source is one of its own types, each a {@code static
 * final} field whose rows name only what differs between the two types. Fields of the same name map
 * without a row, nested objects and lists included.
 *
 * <p>With {@code telescope-codegen} on the processor path, {@code @Bridge} compiles each field's
 * rows into a plain Java class, {@code <Source>To<Target>Bridge}, and the field runs it. Without
 * the processor the same field runs the same rows at run time.
 */
public final class OrderMappers {

  private OrderMappers() {}

  /**
   * The order as the public API shows it.
   *
   * <ul>
   *   <li>The customer's name is read through the customer.
   *   <li>The total is computed from the lines, so it cannot drift from them.
   *   <li>The tracking number is shown only once the order has shipped, and it lands inside the
   *       nested {@code shipping} object, named by the navigator {@code @Focus} generates.
   *   <li>The internal notes stay behind, even if the DTO gains a field of that name one day.
   * </ul>
   *
   * Lines map through {@link #LINE}, and the payment through {@link #CARD} or {@link #BANK} by its
   * case.
   */
  @Bridge
  public static final ForwardMapper<Order, OrderDto> DTO = mapperForward(
    Order.class,
    OrderDto.class,
    to(path(Order::getCustomer, Customer::getName), OrderDto::customerName),
    compute(OrderDto::total, Pricing::total),
    when(OrderRules::shipped, to(Order::getTrackingNumber, OrderDtoTelescope.of().shipping().trackingNumber())),
    drop(Order::getInternalNotes)
  );

  /** A line as the public API shows it, without what the shop paid for the item. */
  @Bridge
  public static final ForwardMapper<OrderLine, OrderLineDto> LINE = mapperForward(
    OrderLine.class,
    OrderLineDto.class,
    drop(OrderLine::getCostPrice)
  );

  /** A card payment without the provider's token. */
  @Bridge
  public static final ForwardMapper<Card, CardDto> CARD = mapperForward(Card.class, CardDto.class, drop(Card::token));

  /** A bank transfer shown by the last four characters of the account. */
  @Bridge
  public static final ForwardMapper<BankTransfer, BankTransferDto> BANK = mapperForward(
    BankTransfer.class,
    BankTransferDto.class,
    toOneWay(BankTransfer::iban, BankTransferDto::accountEnding, Masks::lastFour)
  );

  /** One row of the order list. */
  @Bridge
  public static final ForwardMapper<Order, OrderSummary> SUMMARY = mapperForward(
    Order.class,
    OrderSummary.class,
    to(path(Order::getCustomer, Customer::getName), OrderSummary::customerName),
    compute(OrderSummary::total, Pricing::total)
  );

  /**
   * The order as version 2 shows it. The renames are rows; the status converts from the enum to its
   * name and the tags from a list to a set without one.
   */
  @Bridge
  public static final ForwardMapper<Order, OrderV2Dto> V2 = mapperForward(
    Order.class,
    OrderV2Dto.class,
    to(path(Order::getCustomer, Customer::getName), OrderV2Dto::buyer),
    toOrElse(Order::getDiscountCode, OrderV2Dto::coupon, "NONE"),
    toFormatted(Order::getPlacedOn, OrderV2Dto::placedOn, "dd.MM.yyyy"),
    compute(OrderV2Dto::total, Pricing::totalText),
    drop(Order::getInternalNotes)
  );

  /** A line as version 2 shows it. The quantity widens from {@code int} to {@code long} by name. */
  @Bridge
  public static final ForwardMapper<OrderLine, OrderLineV2Dto> V2_LINE = mapperForward(
    OrderLine.class,
    OrderLineV2Dto.class,
    to(OrderLine::getSku, OrderLineV2Dto::product),
    toFormattedNumber(OrderLine::getUnitPrice, OrderLineV2Dto::price, "0.00"),
    drop(OrderLine::getCostPrice)
  );

  /**
   * A new order, built from the request that places it. The lines are priced from the catalog by
   * {@link CatalogLines} and added through {@link Order#addLine(OrderLine)}, which points each line
   * back at its order. The service looks the customer up by email itself.
   */
  @Bridge
  public static final ForwardMapper<PlaceOrder, Order> PLACE = mapperForward(
    PlaceOrder.class,
    Order.class,
    writeBean(Order.class, ADDERS),
    via(PlaceOrder::lines, Order::getLines, CatalogLines.class),
    drop(PlaceOrder::customerEmail)
  );

  /**
   * An order and its state as a value, both ways. {@code order.toOrderState()} reads the state out
   * of the entity; {@code STATE.into(order, state)} writes it back into the entity the persistence
   * context manages, refilling the lists it already holds through {@link Order#addLine(OrderLine)}
   * and {@link Order#addTag(String)} instead of replacing them.
   */
  @Bridge
  public static final Mapper<OrderState, Order> STATE = mapper(
    OrderState.class,
    Order.class,
    writeBean(Order.class, ADDERS),
    via(OrderState::lines, Order::getLines, CatalogLines.class)
  );

  /**
   * The body of a PATCH against the order's state. {@code CHANGES.patch(state, changes)} keeps
   * every field the client left null and takes every field it sent. The fields a client may never
   * change are dropped by name, so a field added to the state later fails the build until someone
   * decides whether a PATCH may change it.
   */
  @Bridge
  public static final Mapper<OrderState, OrderChanges> CHANGES = mapper(
    OrderState.class,
    OrderChanges.class,
    drop(OrderState::id),
    drop(OrderState::number),
    drop(OrderState::customer),
    drop(OrderState::status),
    drop(OrderState::placedOn),
    drop(OrderState::trackingNumber),
    drop(OrderState::internalNotes),
    drop(OrderState::payment)
  );

  /**
   * An invoice, merged from four sources: the order, the customer, the seller and the totals.
   * Fields of the seller and the totals map by name; the rest are named.
   */
  @Bridge
  public static final Mapper<Sources, InvoiceDto> INVOICE = Telescope.merge(
    InvoiceDto.class,
    from(Order::getNumber, InvoiceDto::number),
    from(Order::getPlacedOn, InvoiceDto::issuedOn),
    from(Customer::getName, InvoiceDto::billTo),
    from(Customer::getEmail, InvoiceDto::email),
    auto(Seller.class),
    auto(Totals.class)
  );
}
