package io.github.eschizoid.telescope.demo.spring;

import io.github.eschizoid.telescope.demo.spring.domain.Address;
import io.github.eschizoid.telescope.demo.spring.domain.Customer;
import io.github.eschizoid.telescope.demo.spring.domain.LineItem;
import io.github.eschizoid.telescope.demo.spring.domain.Order;
import io.github.eschizoid.telescope.demo.spring.domain.payment.CreditCard;
import io.github.eschizoid.telescope.demo.spring.domain.payment.Payment;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Shared fixture builder for the two integration tests. Mirrors the JSON shape consumers would POST
 * to either controller, keeps {@code Customer#id} and {@code Order#id} null on the way in
 * (Hibernate assigns ids on save), and pins enough nesting (deep Address, Optional gift-wrap,
 * non-empty line-item list) to exercise every shape telescope's deep-mapping factory recurses
 * through.
 */
public final class OrderFixtures {

  private OrderFixtures() {}

  public static Order sampleOrder() {
    return new Order(
      null,
      "ORD-2026-0001",
      new Customer(null, "Alice Example", "ALICE@example.com", sampleTags()), // mixed case — gets lowercased
      new Address("100 Main St", "Brooklyn", "NY", "11201"),
      new Address("200 Billing Ave", "Brooklyn", "NY", "11201"),
      List.of(
        new LineItem(null, "SKU-A", 2, new BigDecimal("19.99")),
        new LineItem(null, "SKU-B", 1, new BigDecimal("49.50"))
      ),
      Optional.of(new Address("300 Gift Rd", "Brooklyn", "NY", "11201")),
      Map.of("source", "mobile", "campaign", "summer-sale"),
      samplePayment()
    );
  }

  /**
   * Patch fixture — only fields that should change. Used by the runtime controller's PATCH test to
   * demonstrate that {@code mapper.patch(existing, partial)} preserves non-null fields and lays
   * partial ones on top.
   */
  public static Order patchOrderNumberOnly(final String newOrderNumber) {
    return new Order(null, newOrderNumber, null, null, null, List.of(), Optional.empty(), Map.of(), null);
  }

  /**
   * The sealed {@link Payment} fixture — a {@link CreditCard} variant. Drives the sealed-narrow
   * paradigm-hop chain in {@code SealedNarrowAfterParadigmHopTest}.
   */
  public static Payment samplePayment() {
    return new CreditCard("4111111111111111", "Alice Doe", 2030);
  }

  /**
   * The Customer tag set fixture — non-empty, insertion-order-preserving so the {@code Iso.liftSet}
   * order-preservation contract has something concrete to assert against.
   */
  public static Set<String> sampleTags() {
    return new LinkedHashSet<>(List.of("vip", "newsletter", "wholesale"));
  }
}
