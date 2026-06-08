package io.github.eschizoid.telescope.demo.spring.mapping;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.SETTERS;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBean;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.demo.spring.domain.LineItem;
import io.github.eschizoid.telescope.demo.spring.domain.Order;
import io.github.eschizoid.telescope.demo.spring.persistence.AddressEmbeddable;
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
 * <p>What this configuration demonstrates:
 *
 * <ul>
 *   <li><b>Same-name auto-mapping.</b> Most components — Address fields, Customer fields,
 *       LineItem.id/sku/quantity — line up by name and type. The factory infers them.
 *   <li><b>Typed transforms.</b> {@code LineItem.unitPrice} (BigDecimal) ↔ {@code
 *       LineItemEntity.unitPriceCents} (long-cents). Demonstrates
 *       {@code Mapping.to(srcAcc, tgtAcc, forwardFn, backwardFn)} for non-bijective scalar pairs.
 *   <li><b>Write-strategy hint.</b> Each entity target carries a {@code writeBean(...,
 *       SETTERS)} row that pins the bean writer to no-arg-ctor + setters (required for
 *       Hibernate-managed identity assignment to fire).
 *   <li><b>Auto-list recursion.</b> {@code Order.lineItems} (List&lt;LineItem&gt;) ↔
 *       {@code OrderEntity.lineItems} (List&lt;LineItemEntity&gt;) needs no explicit row — the
 *       deep-mapping factory detects the matching List shape and recurses into the element pair,
 *       picking up the LineItem rows we declared at the top level.
 *   <li><b>Auto-optional recursion.</b> {@code Order.giftWrap} (Optional&lt;Address&gt;) ↔
 *       {@code OrderEntity.giftWrap} (AddressEmbeddable, nullable) — telescope handles the
 *       Optional⇄nullable bridge implicitly when both sides line up.
 * </ul>
 */
@Configuration
public class OrderMappers {

  @Bean
  public Mapper<Order, OrderEntity> orderMapper() {
    return Telescope.mapper(
      Order.class,
      OrderEntity.class,
      // LineItem → LineItemEntity needs one explicit transform row: BigDecimal unitPrice ↔ long
      // unitPriceCents. Telescope's auto-mapping handles id, sku, quantity at the same depth.
      to(LineItem::unitPrice, LineItemEntity::getUnitPriceCents, OrderMappers::toCents, OrderMappers::fromCents),
      // Bean write strategy hints — one per concrete entity in the recursive walk. Without these,
      // `Beans.autoWriter` picks a strategy by probe (builder → no-arg ctor + setters → field
      // injection). Pinning to SETTERS keeps Hibernate happy on every level.
      writeBean(OrderEntity.class, SETTERS),
      writeBean(CustomerEntity.class, SETTERS),
      writeBean(LineItemEntity.class, SETTERS),
      writeBean(AddressEmbeddable.class, SETTERS)
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
