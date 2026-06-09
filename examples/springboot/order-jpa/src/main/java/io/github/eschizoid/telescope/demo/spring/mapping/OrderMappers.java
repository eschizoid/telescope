package io.github.eschizoid.telescope.demo.spring.mapping;

import static io.github.eschizoid.telescope.mapping.Mapping.drop;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.via;
import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.SETTERS;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBeans;

import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.demo.spring.domain.Customer;
import io.github.eschizoid.telescope.demo.spring.domain.LineItem;
import io.github.eschizoid.telescope.demo.spring.domain.Order;
import io.github.eschizoid.telescope.demo.spring.partner.PartnerCustomer;
import io.github.eschizoid.telescope.demo.spring.partner.PartnerLineItem;
import io.github.eschizoid.telescope.demo.spring.partner.PartnerShippingLabel;
import io.github.eschizoid.telescope.demo.spring.persistence.CustomerEntity;
import io.github.eschizoid.telescope.demo.spring.persistence.LineItemEntity;
import io.github.eschizoid.telescope.demo.spring.persistence.OrderEntity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the top-level {@code Order ↔ OrderEntity} mapper. All four record↔entity type pairs (Order,
 * Customer, LineItem, Address) live under one {@link Telescope#mapper(Class, Class,
 * io.github.eschizoid.telescope.mapping.MapStep...)} call — the deep-mapping engine groups rows by {@code
 * (sourceClass, targetClass)} pair, so a row written here applies wherever that pair shows up in
 * the recursive walk (top-level for Order, depth-1 for Customer, depth-1 for the shipping and
 * billing addresses, depth-2 inside the list of line items).
 *
 * <p>The configuration is consumed by both controllers. Underlying dispatch is transparent: when
 * {@code @Focus} / {@code @BeanFocus} are present on the involved types (they are, for this demo),
 * the runtime probe routes through the codegen-emitted holder constants automatically. Without the
 * annotations, the same code falls back to {@code SerializedLambda} decode plus the cached
 * MethodHandle substrate.
 *
 * <h2>Public-row cheat sheet — when to reach for each one</h2>
 *
 * <pre>{@code
 * // ── Mapping rows (io.github.eschizoid.telescope.mapping.Mapping) ────────────────
 * //
 * //   (no row)                         same-name + same-type pair → auto-inferred,
 * //                                    every Address/Customer same-name field below.
 * //
 * //   to(src, tgt)                     plain rename — Order.orderNumber ↔
 * //                                    OrderEntity.referenceCode (different names,
 * //                                    same type).
 * //
 * //   to(src, tgt, fwd, bwd)           typed transform — LineItem.unitPrice (BigDecimal)
 * //                                    ↔ LineItemEntity.unitPriceCents (long), bidirectional.
 * //
 * //   via(src, tgt, mapper)            drop a pre-built nested Mapper. Two flavours:
 * //                                    • scalar — Customer ↔ CustomerEntity via
 * //                                      customerMapper (one-to-one record-pair slot).
 * //                                    • auto-lifted — List<LineItem> ↔ List<LineItemEntity>
 * //                                      via lineItemMapper. Telescope detects the matching
 * //                                      container shape (List / Set / Optional / Map values)
 * //                                      and lifts the element mapper through it.
 * //
 * // ── WriteHint rows (io.github.eschizoid.telescope.mapping.WriteHint) ────────────
 * //
 * //   writeBeans(STRATEGY)             default write strategy applied to every bean target
 * //                                    the recursion touches that lacks a more specific hint.
 * //                                    SETTERS keeps Hibernate's identity assignment happy.
 * //
 * //   writeBean(Class, STRATEGY)       per-class override — wins over writeBeans(…) for that
 * //                                    one class only. Use when a single target needs a
 * //                                    different rebuild strategy (e.g., an immutable
 * //                                    all-args-only POJO that needs CONSTRUCTOR).
 * }</pre>
 *
 * <p><b>Mapper API used downstream</b> (see {@code OrderController} / {@code OrderPathController}):
 *
 * <ul>
 *   <li>{@code mapper.forward(a)} / {@code mapper.read(a)} — A → B
 *   <li>{@code mapper.backward(b)} — B → A
 *   <li>{@code mapper.patch(base, partial)} — sparse overlay (used by {@code
 *       OrderController.patch})
 *   <li>{@code mapper.asTelescope()} — expose as {@code Telescope<A, B>} so it composes via {@code
 *       .then(...)} into a longer typed path; lets a single fluent chain bridge between record-side
 *       and entity-side leaf types (used by {@code OrderPathController.applyDiscount} — typed
 *       {@code OrderPath} walks down to each {@code LineItem}, then {@code
 *       .then(lineItemMapper.asTelescope())} hops into {@code LineItemEntity} so the leaf operation
 *       runs on entity-side {@code unitPriceCents} (long))
 *   <li>{@code mapper.liftList()} / {@code liftSet} / {@code liftOptional} / {@code liftMapValues}
 *       — promote an element-level mapper to a container-level mapper without going through {@code
 *       via(...)} (used by {@code OrderController.bulkCreate})
 * </ul>
 */
@Configuration
public class OrderMappers {

  /**
   * A reusable {@code Customer ↔ CustomerEntity} mapper, broken out so {@link #orderMapper(Mapper,
   * Mapper) orderMapper} can drop it in via {@link io.github.eschizoid.telescope.mapping.Mapping#via(
   * io.github.eschizoid.telescope.Telescope.Accessor,
   * io.github.eschizoid.telescope.Telescope.Accessor, Mapper) via} as a <em>scalar</em> nested
   * mapper (one-to-one record-pair slot, no container lift). Same shape as the {@link
   * #lineItemMapper()} bean below — the difference is the parent's accessor returns a scalar record
   * here vs. a {@code List<LineItem>} there, so telescope skips the auto-lift.
   *
   * <p>Splitting reusable nested mappers into their own beans is a real-world pattern: other parts
   * of the app can {@code @Autowired Mapper<Customer, CustomerEntity>} without having to rebuild
   * the same correspondence twice.
   */
  @Bean
  public Mapper<Customer, CustomerEntity> customerMapper() {
    return Telescope.mapper(Customer.class, CustomerEntity.class, writeBeans(SETTERS));
  }

  /**
   * A reusable {@code LineItem ↔ LineItemEntity} mapper that owns its own {@code BigDecimal ↔
   * long-cents} transform row. Built once, handed to {@link #orderMapper(Mapper, Mapper)
   * orderMapper} via {@code via(...)} — telescope auto-lifts through the {@code List<LineItem> ↔
   * List<LineItemEntity>} accessor pair.
   */
  @Bean
  public Mapper<LineItem, LineItemEntity> lineItemMapper() {
    return Telescope.mapper(
      LineItem.class,
      LineItemEntity.class,
      to(LineItem::unitPrice, LineItemEntity::getUnitPriceCents, OrderMappers::toCents, OrderMappers::fromCents),
      writeBeans(SETTERS)
    );
  }

  /**
   * The top-level mapper, composing {@link #customerMapper()} (scalar via) and {@link
   * #lineItemMapper()} (auto-lifted through {@code List<...>}). Together with the inferred
   * same-name pairs for Address fields, this single mapper covers every field in the {@code Order}
   * graph — addressing both the API-side {@code Order} record and the Hibernate-managed {@code
   * OrderEntity} bean tree in one declarative configuration.
   */
  @Bean
  public Mapper<Order, OrderEntity> orderMapper(
    final Mapper<Customer, CustomerEntity> customerMapper,
    final Mapper<LineItem, LineItemEntity> lineItemMapper
  ) {
    return Telescope.mapper(
      Order.class,
      OrderEntity.class,
      // (1) Plain rename — same type, different name. Order.orderNumber is what the JSON client
      //     sees; OrderEntity.referenceCode is what the schema stores under reference_code.
      to(Order::orderNumber, OrderEntity::getReferenceCode),
      // (2) Scalar via — drop a pre-built customerMapper at the Customer ↔ CustomerEntity slot.
      //     No container around this pair, so telescope uses the mapper as-is (no lift).
      via(Order::customer, OrderEntity::getCustomer, customerMapper),
      // (3) Auto-lifted via — drop the element-level lineItemMapper at a List<LineItem> ↔
      //     List<LineItemEntity> slot. Telescope detects the matching container shape and lifts
      //     the element mapper through Iso.liftList — no manual list ceremony at the call site.
      via(Order::lineItems, OrderEntity::getLineItems, lineItemMapper),
      // (4) Drop — Order.payment is record-side only (sealed Payment lives in domain.payment).
      //     The persistence layer doesn't store it; a separate payment processor owns that.
      drop(Order::payment),
      // (5) Default writer — every bean target the recursion touches (OrderEntity,
      //     AddressEmbeddable) uses SETTERS. customerMapper and lineItemMapper carry their own
      //     writeBeans(SETTERS) so their targets are covered there too.
      writeBeans(SETTERS)
    );
  }

  /**
   * Element-level mapper between {@code Order.LineItem} (record) and {@code PartnerLineItem}
   * (Lombok bean). Same-name fields (id / sku / quantity / unitPrice) auto-infer; {@code
   * writeBeans(SETTERS)} routes the Lombok-emitted setters during reconstruction. Used by {@link
   * #partnerLabelMapper(Mapper)} as the auto-lifted element mapper for the {@code List<LineItem> ↔
   * List<PartnerLineItem>} slot.
   */
  @Bean
  public Mapper<LineItem, PartnerLineItem> partnerItemMapper() {
    return Telescope.mapper(LineItem.class, PartnerLineItem.class, writeBeans(SETTERS));
  }

  /**
   * Top-level mapper between the {@code Order} record graph and the partner SDK's Lombok-shaped
   * {@code PartnerShippingLabel}. Demonstrates that telescope's deep-mapping factory handles
   * record↔Lombok-bean conversions with the same primitives that handle record↔JPA-entity:
   *
   * <ul>
   *   <li>{@code to(Order::orderNumber, PartnerShippingLabel::getTrackingReference)} — rename.
   *   <li>{@code via(Order::lineItems, PartnerShippingLabel::getItems, partnerItemMapper)} —
   *       different field names + container shape; the element-level mapper auto-lifts through the
   *       {@code List<...>}.
   *   <li>{@code drop(Order::metadata)} — internal-only field that the partner DTO does not (and
   *       should not) carry. Without this row the strict deep-mapper rejects the unmapped source.
   *   <li>{@code writeBeans(SETTERS)} — Lombok's {@code @Data}-synthesised setters are the right
   *       construction strategy for every nested bean target ({@code PartnerShippingLabel}, {@code
   *       Customer}, {@code Address}). Telescope-lombok's emitted Path is used implicitly via the
   *       holder-probe fast path; no explicit Path navigation needed at this call site.
   *   <li>Same-name {@code Order.customer ↔ PartnerShippingLabel.customer}, {@code
   *       shippingAddress}, {@code billingAddress}, and {@code giftWrap} all auto-recurse. The
   *       {@code Optional<Address> ↔ nullable PartnerShippingLabel.Address} bridge fires for {@code
   *       giftWrap} (see v0.4.1 CHANGELOG — {@code Iso.liftOptionalToNullable}).
   * </ul>
   */
  @Bean
  public Mapper<Order, PartnerShippingLabel> partnerLabelMapper(
    final Mapper<LineItem, PartnerLineItem> partnerItemMapper
  ) {
    return Telescope.mapper(
      Order.class,
      PartnerShippingLabel.class,
      to(Order::orderNumber, PartnerShippingLabel::getTrackingReference),
      via(Order::lineItems, PartnerShippingLabel::getItems, partnerItemMapper),
      drop(Order::metadata),
      drop(Order::payment),
      drop(Customer::tags, PartnerCustomer.class),
      writeBeans(SETTERS)
    );
  }

  // ---------- typed-transform helpers ----------

  private static long toCents(final BigDecimal amount) {
    return amount == null ? 0L : amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
  }

  private static BigDecimal fromCents(final long cents) {
    return BigDecimal.valueOf(cents).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY);
  }
}
