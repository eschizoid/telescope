package io.github.eschizoid.telescope.consumer;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import java.util.ArrayList;
import java.util.List;

/** The README's order: a generated identifier, fields a PATCH may never change, and its lines. */
@Entity
public class Order {

  @Id
  @GeneratedValue
  private Long id;

  private String number;
  private String customer;
  private String internalNotes;
  private String delivery;

  @OneToMany(mappedBy = "order")
  private List<OrderLine> lines = new ArrayList<>();

  public Long getId() {
    return id;
  }

  public void setId(final Long id) {
    this.id = id;
  }

  public String getNumber() {
    return number;
  }

  public void setNumber(final String number) {
    this.number = number;
  }

  public String getCustomer() {
    return customer;
  }

  public void setCustomer(final String customer) {
    this.customer = customer;
  }

  public String getInternalNotes() {
    return internalNotes;
  }

  public void setInternalNotes(final String internalNotes) {
    this.internalNotes = internalNotes;
  }

  public String getDelivery() {
    return delivery;
  }

  public void setDelivery(final String delivery) {
    this.delivery = delivery;
  }

  public List<OrderLine> getLines() {
    return lines;
  }

  /** Adds a line and points it back at this order. */
  public void addLine(final OrderLine line) {
    line.setOrder(this);
    lines.add(line);
  }
}
