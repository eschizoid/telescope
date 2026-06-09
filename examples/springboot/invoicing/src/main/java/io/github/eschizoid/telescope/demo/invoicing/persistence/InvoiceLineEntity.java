package io.github.eschizoid.telescope.demo.invoicing.persistence;

import io.github.eschizoid.telescope.annotations.BeanFocus;
import java.math.BigDecimal;

/**
 * JPA-style mutable bean side of the {@code @Bridge} pair. {@code @BeanFocus} emits {@code
 * InvoiceLineEntityPath<R>} so the bridge hop on {@code domain.InvoiceLine}'s navigator can return
 * a typed Path on the entity side (continued navigation after {@code asInvoiceLineEntity()}).
 *
 * <p>Same field types as {@code domain.InvoiceLine} on purpose — this submodule exercises the
 * codegen IDENTITY field branch end-to-end on a real Spring app. Typed transforms (e.g.
 * BigDecimal↔long-cents) live in {@code order-jpa}'s LineItem pair, which uses the runtime factory;
 * {@code @Bridge}'s same-name bijection has no surface for that.
 */
@BeanFocus
public class InvoiceLineEntity {

  private String sku;
  private int qty;
  private BigDecimal unitPrice;

  public InvoiceLineEntity() {}

  public String getSku() {
    return sku;
  }

  public void setSku(final String sku) {
    this.sku = sku;
  }

  public int getQty() {
    return qty;
  }

  public void setQty(final int qty) {
    this.qty = qty;
  }

  public BigDecimal getUnitPrice() {
    return unitPrice;
  }

  public void setUnitPrice(final BigDecimal unitPrice) {
    this.unitPrice = unitPrice;
  }
}
