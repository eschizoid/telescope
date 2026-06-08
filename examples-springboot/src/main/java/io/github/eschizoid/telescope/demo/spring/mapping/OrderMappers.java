package io.github.eschizoid.telescope.demo.spring.mapping;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.via;
import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.SETTERS;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBeans;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.demo.spring.domain.Customer;
import io.github.eschizoid.telescope.demo.spring.domain.LineItem;
import io.github.eschizoid.telescope.demo.spring.domain.Order;
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
 * io.github.eschizoid.telescope.mapping.MapStep...)} call — the deep-mapping engine groups rows by
 * {@code (sourceClass, targetClass)} pair, so a row written here applies wherever that pair shows
 * up in the recursive walk (top-level for Order, depth-1 for Customer, depth-1 for the shipping
 * and billing addresses, depth-2 inside the list of line items).
 *
 * <p>The configuration is consumed by both controllers. Underlying dispatch is transparent: when
 * {@code @Focus} / {@code @BeanFocus} are present on the involved types (they are, for this demo),
 * the runtime probe routes through the codegen-emitted holder constants automatically. Without
 * the annotations, the same code falls back to {@code SerializedLambda} decode plus the cached
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
 * <p><b>Mapper API used downstream</b> (see {@code RuntimeOrderController} / {@code
 * CodegenOrderController}):
 *
 * <ul>
 *   <li>{@code mapper.forward(a)} / {@code mapper.read(a)} — A → B
 *   <li>{@code mapper.backward(b)} — B → A
 *   <li>{@code mapper.patch(base, partial)} — sparse overlay
 *   <li>{@code mapper.asTelescope()} — expose as {@code Telescope<A, B>} for {@code .then(...)}
 *   <li>{@code mapper.liftList()} / {@code liftSet} / {@code liftOptional} / {@code liftMapValues}
 *       — promote an element-level mapper to a container-level mapper without going through {@code
 *       via(...)}
 * </ul>
 */
@Configuration
public class OrderMappers {

  /**
   * A reusable {@code Customer ↔ CustomerEntity} mapper, broken out so {@link #orderMapper(Mapper, Mapper) orderMapper} can
   * drop it in via {@link io.github.eschizoid.telescope.mapping.Mapping#via(
   * io.github.eschizoid.telescope.Telescope.Accessor,
   * io.github.eschizoid.telescope.Telescope.Accessor, Mapper) via} as a <em>scalar</em> nested
   * mapper (one-to-one record-pair slot, no container lift). Same shape as the {@link
   * #lineItemMapper()} bean below — the difference is the parent's accessor returns a scalar
   * record here vs. a {@code List<LineItem>} there, so telescope skips the auto-lift.
   *
   * <p>Splitting reusable nested mappers into their own beans is a real-world pattern:
   * other parts of the app can {@code @Autowired Mapper<Customer, CustomerEntity>} without
   * having to rebuild the same correspondence twice.
   */
  @Bean
  public Mapper<Customer, CustomerEntity> customerMapper() {
    return Telescope.mapper(Customer.class, CustomerEntity.class, writeBeans(SETTERS));
  }

  /**
   * A reusable {@code LineItem ↔ LineItemEntity} mapper that owns its own {@code BigDecimal ↔
   * long-cents} transform row. Built once, handed to {@link #orderMapper(Mapper, Mapper) orderMapper} via {@code via(...)} —
   * telescope auto-lifts through the {@code List<LineItem> ↔ List<LineItemEntity>} accessor pair.
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
      // (4) Default writer — every bean target the recursion touches (OrderEntity,
      //     AddressEmbeddable) uses SETTERS. customerMapper and lineItemMapper carry their own
      //     writeBeans(SETTERS) so their targets are covered there too.
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
