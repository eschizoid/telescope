package io.github.eschizoid.telescope.benchmarks;

/**
 * The nested-tier bean of {@link MapStructComparisonBenchmark} again, for the generated mapper
 * {@link McFieldMappers#NESTED} declares on its field, with the same nested address pair.
 */
public class McFieldNestedBean {

  private Long id;
  private String email;
  private McAddressBean address;

  public McFieldNestedBean() {}

  public McFieldNestedBean(final Long id, final String email, final McAddressBean address) {
    this.id = id;
    this.email = email;
    this.address = address;
  }

  public Long getId() {
    return id;
  }

  public void setId(final Long id) {
    this.id = id;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(final String email) {
    this.email = email;
  }

  public McAddressBean getAddress() {
    return address;
  }

  public void setAddress(final McAddressBean address) {
    this.address = address;
  }
}
