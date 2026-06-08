package io.github.eschizoid.telescope.demo.spring.persistence;

import io.github.eschizoid.telescope.annotations.BeanFocus;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

/**
 * The top-level Hibernate entity. Exercises the full cross-paradigm mapping surface:
 *
 * <ul>
 *   <li>Scalar fields (id, orderNumber)
 *   <li>{@code @ManyToOne} relation to {@link CustomerEntity}
 *   <li>Two {@code @Embedded} {@link AddressEmbeddable} fields (shipping + billing) with column
 *       prefixes
 *   <li>{@code @OneToMany} {@code List<LineItemEntity>}
 *   <li>Optional embedded — a third {@code @Embedded} address that may be {@code null} when no
 *       gift wrap is requested; mirrors the record-side {@code Optional<Address>}
 * </ul>
 *
 * <p>{@link BeanFocus} drives the codegen processor; the runtime mapper consults the same
 * properties via reflective method-reference resolution.
 */
@Entity
@Table(name = "orders")
@BeanFocus
public class OrderEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String orderNumber;

  @ManyToOne(cascade = CascadeType.PERSIST, fetch = FetchType.EAGER)
  @JoinColumn(name = "customer_id")
  private CustomerEntity customer;

  @Embedded
  @AttributeOverrides(
    {
      @AttributeOverride(name = "street", column = @Column(name = "ship_street")),
      @AttributeOverride(name = "city", column = @Column(name = "ship_city")),
      @AttributeOverride(name = "state", column = @Column(name = "ship_state")),
      @AttributeOverride(name = "zip", column = @Column(name = "ship_zip")),
    }
  )
  private AddressEmbeddable shippingAddress;

  @Embedded
  @AttributeOverrides(
    {
      @AttributeOverride(name = "street", column = @Column(name = "bill_street")),
      @AttributeOverride(name = "city", column = @Column(name = "bill_city")),
      @AttributeOverride(name = "state", column = @Column(name = "bill_state")),
      @AttributeOverride(name = "zip", column = @Column(name = "bill_zip")),
    }
  )
  private AddressEmbeddable billingAddress;

  @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
  @JoinColumn(name = "order_id")
  private List<LineItemEntity> lineItems = new ArrayList<>();

  @Embedded
  @AttributeOverrides(
    {
      @AttributeOverride(name = "street", column = @Column(name = "gift_street")),
      @AttributeOverride(name = "city", column = @Column(name = "gift_city")),
      @AttributeOverride(name = "state", column = @Column(name = "gift_state")),
      @AttributeOverride(name = "zip", column = @Column(name = "gift_zip")),
    }
  )
  private AddressEmbeddable giftWrap;

  public OrderEntity() {}

  public Long getId() {
    return id;
  }

  public void setId(final Long id) {
    this.id = id;
  }

  public String getOrderNumber() {
    return orderNumber;
  }

  public void setOrderNumber(final String orderNumber) {
    this.orderNumber = orderNumber;
  }

  public CustomerEntity getCustomer() {
    return customer;
  }

  public void setCustomer(final CustomerEntity customer) {
    this.customer = customer;
  }

  public AddressEmbeddable getShippingAddress() {
    return shippingAddress;
  }

  public void setShippingAddress(final AddressEmbeddable shippingAddress) {
    this.shippingAddress = shippingAddress;
  }

  public AddressEmbeddable getBillingAddress() {
    return billingAddress;
  }

  public void setBillingAddress(final AddressEmbeddable billingAddress) {
    this.billingAddress = billingAddress;
  }

  public List<LineItemEntity> getLineItems() {
    return lineItems;
  }

  public void setLineItems(final List<LineItemEntity> lineItems) {
    this.lineItems = lineItems;
  }

  public AddressEmbeddable getGiftWrap() {
    return giftWrap;
  }

  public void setGiftWrap(final AddressEmbeddable giftWrap) {
    this.giftWrap = giftWrap;
  }
}
