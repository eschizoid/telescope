package io.github.eschizoid.telescope.demo.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;

/**
 * The shop's pricing rules. An order stores its lines and its discount code, never a total, so a
 * total can never disagree with the lines it was computed from.
 */
public final class Pricing {

  /** The discount each code gives, as a fraction of the subtotal. */
  private static final Map<String, BigDecimal> DISCOUNTS = Map.of(
    "WELCOME10",
    new BigDecimal("0.10"),
    "VIP25",
    new BigDecimal("0.25")
  );

  private Pricing() {}

  /** The subtotal, the discount the order's code gives and what the customer pays. */
  public static Totals totals(final Order order) {
    var subtotal = BigDecimal.ZERO;
    for (final var line : order.getLines()) {
      subtotal = subtotal.add(line.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity())));
    }
    final var rate =
      order.getDiscountCode() == null
        ? BigDecimal.ZERO
        : DISCOUNTS.getOrDefault(order.getDiscountCode(), BigDecimal.ZERO);
    final var discount = subtotal.multiply(rate).setScale(2, RoundingMode.HALF_EVEN);
    return new Totals(
      subtotal.setScale(2, RoundingMode.HALF_EVEN),
      discount,
      subtotal.subtract(discount).setScale(2, RoundingMode.HALF_EVEN)
    );
  }

  /** What the customer pays. */
  public static BigDecimal total(final Order order) {
    return totals(order).total();
  }

  /** What the customer pays, as text with two decimals and a comma between thousands. */
  public static String totalText(final Order order) {
    return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(total(order));
  }

  /** Whether a discount code is one the shop honours. */
  public static boolean knownCode(final String code) {
    return code != null && DISCOUNTS.containsKey(code);
  }
}
