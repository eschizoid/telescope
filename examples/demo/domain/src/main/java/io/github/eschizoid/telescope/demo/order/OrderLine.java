package io.github.eschizoid.telescope.demo.order;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * One line of an order. The line knows its order, and {@link Order#addLine(OrderLine)} is what sets
 * that back-reference, so the database row gets its foreign key.
 *
 * <p>{@code costPrice} is what the shop paid for the item. It stays inside the service: no public
 * DTO has a property of that name.
 */
@Entity
@Table(name = "order_lines")
public class OrderLine {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String sku;
  private String name;
  private int quantity;
  private BigDecimal unitPrice;
  private BigDecimal costPrice;

  @ManyToOne
  @JoinColumn(name = "order_id")
  private Order order;

  public OrderLine() {}

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

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public int getQuantity() {
    return quantity;
  }

  public void setQuantity(final int quantity) {
    this.quantity = quantity;
  }

  public BigDecimal getUnitPrice() {
    return unitPrice;
  }

  public void setUnitPrice(final BigDecimal unitPrice) {
    this.unitPrice = unitPrice;
  }

  public BigDecimal getCostPrice() {
    return costPrice;
  }

  public void setCostPrice(final BigDecimal costPrice) {
    this.costPrice = costPrice;
  }

  public Order getOrder() {
    return order;
  }

  public void setOrder(final Order order) {
    this.order = order;
  }
}
