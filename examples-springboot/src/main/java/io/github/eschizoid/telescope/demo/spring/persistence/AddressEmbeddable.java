package io.github.eschizoid.telescope.demo.spring.persistence;

import io.github.eschizoid.telescope.annotations.BeanFocus;
import jakarta.persistence.Embeddable;

/**
 * The JPA-embedded form of {@code domain.Address}. {@code @Embeddable} means the columns live
 * on the parent entity's row (no join). Telescope's bean-side support handles {@code @Embeddable}
 * the same way it handles a plain POJO — no JPA awareness required.
 *
 * <p>Has both a no-arg constructor (required by JPA) and per-property setters. {@link BeanFocus}
 * triggers the codegen processor to emit {@code AddressEmbeddablePath<R>} navigator + {@code
 * AddressEmbeddableTelescope} metadata holder, matching the record-side {@code AddressPath} /
 * {@code AddressTelescope} pair.
 */
@Embeddable
@BeanFocus
public class AddressEmbeddable {

  private String street;
  private String city;
  private String state;
  private String zip;

  public AddressEmbeddable() {}

  public String getStreet() {
    return street;
  }

  public void setStreet(final String street) {
    this.street = street;
  }

  public String getCity() {
    return city;
  }

  public void setCity(final String city) {
    this.city = city;
  }

  public String getState() {
    return state;
  }

  public void setState(final String state) {
    this.state = state;
  }

  public String getZip() {
    return zip;
  }

  public void setZip(final String zip) {
    this.zip = zip;
  }
}
