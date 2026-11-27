package io.github.eschizoid.telescope.jpa.fixtures;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

/**
 * A line of a {@link Cart}, whose unit price comes from {@link CartPrices}, never from a client.
 */
@Entity
public class CartLine {

  @Id
  @GeneratedValue
  private Long id;

  private String sku;
  private int quantity;
  private int unitPrice;

  @ManyToOne
  private Cart cart;

  public CartLine() {}

  public CartLine(final String sku, final int quantity, final int unitPrice) {
    this.sku = sku;
    this.quantity = quantity;
    this.unitPrice = unitPrice;
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

  public int getUnitPrice() {
    return unitPrice;
  }

  public void setUnitPrice(final int unitPrice) {
    this.unitPrice = unitPrice;
  }

  public Cart getCart() {
    return cart;
  }

  public void setCart(final Cart cart) {
    this.cart = cart;
  }
}
