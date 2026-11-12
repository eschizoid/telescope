package io.github.eschizoid.telescope.demo.nativeimage;

import io.github.eschizoid.telescope.demo.order.Delivery;

/**
 * A shipping label in the carrier's client library, which can only be built through its builder: no
 * public constructor and no setters.
 */
public final class CarrierLabel {

  private final String orderNumber;
  private final String recipient;
  private final Address address;
  private final Delivery delivery;

  private CarrierLabel(final Builder builder) {
    this.orderNumber = builder.orderNumber;
    this.recipient = builder.recipient;
    this.address = builder.address;
    this.delivery = builder.delivery;
  }

  public static Builder builder() {
    return new Builder();
  }

  public String getOrderNumber() {
    return orderNumber;
  }

  public String getRecipient() {
    return recipient;
  }

  public Address getAddress() {
    return address;
  }

  public Delivery getDelivery() {
    return delivery;
  }

  /** Builds a {@link CarrierLabel} one field at a time. */
  public static final class Builder {

    private String orderNumber;
    private String recipient;
    private Address address;
    private Delivery delivery;

    private Builder() {}

    public Builder orderNumber(final String orderNumber) {
      this.orderNumber = orderNumber;
      return this;
    }

    public Builder recipient(final String recipient) {
      this.recipient = recipient;
      return this;
    }

    public Builder address(final Address address) {
      this.address = address;
      return this;
    }

    public Builder delivery(final Delivery delivery) {
      this.delivery = delivery;
      return this;
    }

    public CarrierLabel build() {
      return new CarrierLabel(this);
    }
  }
}
