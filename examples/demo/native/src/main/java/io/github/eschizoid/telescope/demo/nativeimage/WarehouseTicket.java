package io.github.eschizoid.telescope.demo.nativeimage;

import io.github.eschizoid.telescope.demo.order.Delivery;

/** The warehouse system's packing ticket: a bean with a no-argument constructor and setters. */
public class WarehouseTicket {

  private String orderNumber;
  private String recipient;
  private Address address;
  private Delivery delivery;

  public WarehouseTicket() {}

  public String getOrderNumber() {
    return orderNumber;
  }

  public void setOrderNumber(final String orderNumber) {
    this.orderNumber = orderNumber;
  }

  public String getRecipient() {
    return recipient;
  }

  public void setRecipient(final String recipient) {
    this.recipient = recipient;
  }

  public Address getAddress() {
    return address;
  }

  public void setAddress(final Address address) {
    this.address = address;
  }

  public Delivery getDelivery() {
    return delivery;
  }

  public void setDelivery(final Delivery delivery) {
    this.delivery = delivery;
  }
}
