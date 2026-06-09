package io.github.eschizoid.telescope.demo.spring.persistence;

import io.github.eschizoid.telescope.annotations.BeanFocus;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The Hibernate-managed twin of {@code domain.LineItem}. Notice {@code unitPriceCents} — a {@code
 * long} of cents, not the record-side {@code BigDecimal}. This is the "money in the DB is an
 * integer, money in the API is a decimal" pattern, and telescope's typed-transform mapping rows
 * (`Mapping.to(srcAcc, tgtAcc, BigDecimal::toLongCents, long::toBigDecimal)`) bridge it cleanly.
 */
@Entity
@Table(name = "line_item")
@BeanFocus
public class LineItemEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String sku;
  private int quantity;
  private long unitPriceCents;

  public LineItemEntity() {}

  public Long getId() {
    return id;
  }

  public void setId(final Long id) {
    this.id = id;
  }

  public String getSku() {
    return sku;
  }

  public void setSku(final String sku) {
    this.sku = sku;
  }

  public int getQuantity() {
    return quantity;
  }

  public void setQuantity(final int quantity) {
    this.quantity = quantity;
  }

  public long getUnitPriceCents() {
    return unitPriceCents;
  }

  public void setUnitPriceCents(final long unitPriceCents) {
    this.unitPriceCents = unitPriceCents;
  }
}
