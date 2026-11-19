package io.github.eschizoid.telescope.jpa.fixtures;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

/**
 * One line of a basket. {@code note} and the back-reference are no bean properties, read through
 * accessors of their own name, so no mapper reads or writes them: writing a form's line into a held
 * one leaves both as they were, and only {@link Basket#addLine} sets the back-reference.
 */
@Entity
public class Line {

  @Id
  @GeneratedValue
  private Long id;

  private String sku;
  private int quantity;
  private String note;

  @ManyToOne
  private Basket basket;

  public Line() {}

  public Line(final String sku, final int quantity, final String note) {
    this.sku = sku;
    this.quantity = quantity;
    this.note = note;
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

  public String note() {
    return note;
  }

  public Basket basket() {
    return basket;
  }

  void attachTo(final Basket basket) {
    this.basket = basket;
  }
}
