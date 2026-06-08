package io.github.eschizoid.telescope.demo.spring.mapping;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.via;
import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.SETTERS;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBeans;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.demo.spring.domain.LineItem;
import io.github.eschizoid.telescope.demo.spring.domain.Order;
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
 * <p>What this configuration demonstrates:
 *
 * <ul>
 *   <li><b>Same-name auto-mapping.</b> Most components — Address fields, Customer fields,
 *       LineItem.id/sku/quantity — line up by name and type. The factory infers them.
 *   <li><b>Typed transforms.</b> {@code LineItem.unitPrice} (BigDecimal) ↔ {@code
 *       LineItemEntity.unitPriceCents} (long-cents). Demonstrates
 *       {@code Mapping.to(srcAcc, tgtAcc, forwardFn, backwardFn)} for non-bijective scalar pairs.
 *   <li><b>Nested mapper composition with auto-lift.</b> A standalone {@code Mapper<LineItem,
 *       LineItemEntity>} is built first (carrying its own typed-transform row), then dropped into
 *       the top-level mapper via {@code via(Order::lineItems, OrderEntity::getLineItems,
 *       lineItemMapper)}. Telescope detects that the accessors return {@code List<LineItem>} /
 *       {@code List<LineItemEntity>} and the element-level mapper matches the element type, so it
 *       auto-lifts the mapper through {@link
 *       io.github.eschizoid.telescope.internal.optics.Iso#liftList Iso.liftList} — no manual list
 *       lifting at the call site.
 *   <li><b>Write-strategy default.</b> One {@code writeBeans(SETTERS)} row pins every bean target
 *       in the recursive walk (OrderEntity, CustomerEntity, LineItemEntity, AddressEmbeddable) to
 *       no-arg-ctor + setters — required so Hibernate's identity assignment fires on every level.
 *   <li><b>Auto-optional recursion.</b> {@code Order.giftWrap} (Optional&lt;Address&gt;) ↔
 *       {@code OrderEntity.giftWrap} (AddressEmbeddable, nullable) — telescope handles the
 *       Optional⇄nullable bridge implicitly when both sides line up.
 * </ul>
 */
@Configuration
public class OrderMappers {

  @Bean
  public Mapper<Order, OrderEntity> orderMapper() {
    // A reusable LineItem ↔ LineItemEntity mapper that owns its own BigDecimal ↔ long-cents
    // transform. Build it once, hand it to the parent mapper via `via(...)` — telescope auto-lifts
    // through the List<LineItem> ↔ List<LineItemEntity> accessor pair, so no list lifting ceremony
    // at the call site.
    final Mapper<LineItem, LineItemEntity> lineItemMapper = Telescope.mapper(
      LineItem.class,
      LineItemEntity.class,
      to(LineItem::unitPrice, LineItemEntity::getUnitPriceCents, OrderMappers::toCents, OrderMappers::fromCents),
      writeBeans(SETTERS)
    );
    return Telescope.mapper(
      Order.class,
      OrderEntity.class,
      // Drop the pre-built element mapper at the List<LineItem> ↔ List<LineItemEntity> slot —
      // telescope detects the matching container shape and lifts the element mapper through it.
      via(Order::lineItems, OrderEntity::getLineItems, lineItemMapper),
      // Single default write strategy applies to every other bean target the recursion touches
      // (OrderEntity, CustomerEntity, AddressEmbeddable). Pinning SETTERS keeps Hibernate happy.
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
