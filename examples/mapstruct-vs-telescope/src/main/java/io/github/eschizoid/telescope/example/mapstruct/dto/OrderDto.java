package io.github.eschizoid.telescope.example.mapstruct.dto;

import java.util.List;
import java.util.Objects;

/**
 * Target-side order — a mutable JavaBean (no-arg constructor plus getters/setters), the DTO shape
 * JPA and serialization frameworks impose. Mirrors {@code Order}; only {@code Customer.email ->
 * contactEmail} differs.
 */
public final class OrderDto {

  private String id;
  private CustomerDto customer;
  private List<LineItemDto> lines;

  public OrderDto() {}

  public String getId() {
    return id;
  }

  public void setId(final String id) {
    this.id = id;
  }

  public CustomerDto getCustomer() {
    return customer;
  }

  public void setCustomer(final CustomerDto customer) {
    this.customer = customer;
  }

  public List<LineItemDto> getLines() {
    return lines;
  }

  public void setLines(final List<LineItemDto> lines) {
    this.lines = lines;
  }

  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof OrderDto that)) {
      return false;
    }
    return Objects.equals(id, that.id) && Objects.equals(customer, that.customer) && Objects.equals(lines, that.lines);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, customer, lines);
  }

  @Override
  public String toString() {
    return "OrderDto[id=" + id + ", customer=" + customer + ", lines=" + lines + "]";
  }
}
