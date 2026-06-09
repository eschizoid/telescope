package io.github.eschizoid.telescope.demo.spring.bughunt.bridge;

import io.github.eschizoid.telescope.annotations.BeanFocus;
import java.math.BigDecimal;

/**
 * JPA-style mutable bean side of the {@code @Bridge} pair. {@code @BeanFocus} emits {@code
 * InvoiceLineEntityPath<R>} so the bridge hop on {@link InvoiceLine}'s navigator can return a typed
 * Path on the entity side (continued navigation after {@code asInvoiceLineEntity()}).
 *
 * <p>Same field types as {@link InvoiceLine} on purpose — this slice exercises the codegen IDENTITY
 * field branch end-to-end on a real Spring demo. The cents-vs-BigDecimal mismatch already lives on
 * {@code LineItem} / {@code LineItemEntity} and is handled by a runtime typed-transform mapping;
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
