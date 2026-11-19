package io.github.eschizoid.telescope.benchmarks;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

/** One line of {@link MbOrder}, an entity whose identifier the provider generates. */
@Entity
public class MbLine {

  @Id
  @GeneratedValue
  private Long id;

  private String sku;
  private int quantity;

  public MbLine() {}

  public MbLine(final Long id, final String sku, final int quantity) {
    this.id = id;
    this.sku = sku;
    this.quantity = quantity;
  }

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
}
