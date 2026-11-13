package io.github.eschizoid.telescope.benchmarks;

import java.util.ArrayList;
import java.util.List;

/**
 * The bean {@link PatchIntoBenchmark} updates in place: six scalars and a list, each with a setter,
 * as a managed entity holds them.
 */
public class PatchIntoEntity {

  private String number;
  private String status;
  private String delivery;
  private String discountCode;
  private String trackingNumber;
  private int quantity;
  private List<String> tags = new ArrayList<>();

  public PatchIntoEntity() {}

  public String getNumber() {
    return number;
  }

  public void setNumber(final String number) {
    this.number = number;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(final String status) {
    this.status = status;
  }

  public String getDelivery() {
    return delivery;
  }

  public void setDelivery(final String delivery) {
    this.delivery = delivery;
  }

  public String getDiscountCode() {
    return discountCode;
  }

  public void setDiscountCode(final String discountCode) {
    this.discountCode = discountCode;
  }

  public String getTrackingNumber() {
    return trackingNumber;
  }

  public void setTrackingNumber(final String trackingNumber) {
    this.trackingNumber = trackingNumber;
  }

  public int getQuantity() {
    return quantity;
  }

  public void setQuantity(final int quantity) {
    this.quantity = quantity;
  }

  public List<String> getTags() {
    return tags;
  }

  public void setTags(final List<String> tags) {
    this.tags = tags;
  }
}
