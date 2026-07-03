package io.github.eschizoid.telescope.example.mapstruct.dto;

import java.util.Objects;

/**
 * Target-side customer — a mutable JavaBean (no-arg constructor plus getters/setters), the DTO
 * shape JPA and serialization frameworks impose. {@code contactEmail} is deliberately named
 * differently from the source {@code Customer.email} — this is the field whose correspondence must
 * be spelled explicitly, and the one that exposes the string-vs-method-reference gap when the
 * source field is renamed.
 */
public final class CustomerDto {

  private String name;
  private String contactEmail;

  public CustomerDto() {}

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public String getContactEmail() {
    return contactEmail;
  }

  public void setContactEmail(final String contactEmail) {
    this.contactEmail = contactEmail;
  }

  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof CustomerDto that)) {
      return false;
    }
    return Objects.equals(name, that.name) && Objects.equals(contactEmail, that.contactEmail);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, contactEmail);
  }

  @Override
  public String toString() {
    return "CustomerDto[name=" + name + ", contactEmail=" + contactEmail + "]";
  }
}
