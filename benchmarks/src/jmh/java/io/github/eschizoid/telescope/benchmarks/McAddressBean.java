package io.github.eschizoid.telescope.benchmarks;

/**
 * Nested-tier leaf POJO. Companion of {@link McAddressRec}. Plain getter/setter bean; no
 * annotations needed — the codegen {@code @Bridge} on {@link McNestedBean} recurses through
 * inner-component types by name bijection, so this class just needs to exist in the right shape.
 */
public class McAddressBean {

  private String street;
  private String city;
  private String zip;

  public McAddressBean() {}

  public McAddressBean(final String street, final String city, final String zip) {
    this.street = street;
    this.city = city;
    this.zip = zip;
  }

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

  public String getZip() {
    return zip;
  }

  public void setZip(final String zip) {
    this.zip = zip;
  }
}
