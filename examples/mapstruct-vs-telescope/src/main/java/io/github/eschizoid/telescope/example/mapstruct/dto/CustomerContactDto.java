package io.github.eschizoid.telescope.example.mapstruct.dto;

import java.util.Objects;

/**
 * A target with one property — {@code region} — that has no source counterpart on {@code Customer}.
 * A mutable JavaBean like the rest of the DTO side. It exists to pin the silent-drop footgun: a
 * newly added or drifted target field with no source compiles clean and lands {@code null} at
 * runtime under MapStruct's default policy. (A source rename is the separate, louder case — a
 * compile error.)
 */
public final class CustomerContactDto {

  private String name;
  private String contactEmail;
  private String region;

  public CustomerContactDto() {}

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

  public String getRegion() {
    return region;
  }

  public void setRegion(final String region) {
    this.region = region;
  }

  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof CustomerContactDto that)) {
      return false;
    }
    return (
      Objects.equals(name, that.name) &&
      Objects.equals(contactEmail, that.contactEmail) &&
      Objects.equals(region, that.region)
    );
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, contactEmail, region);
  }

  @Override
  public String toString() {
    return "CustomerContactDto[name=" + name + ", contactEmail=" + contactEmail + ", region=" + region + "]";
  }
}
