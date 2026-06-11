package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Bridge;

/**
 * Nested-tier outer POJO. One level of nesting: an {@code id} / {@code email} pair plus a nested
 * {@link McAddressBean}. {@code @Bridge(McNestedRec.class)} drives the codegen path — the
 * {@code @Bridge} processor recurses through the {@code address} component and emits a paired
 * {@code McAddressBean ↔ McAddressRec} bridge automatically.
 */
@Bridge(McNestedRec.class)
public class McNestedBean {

  private Long id;
  private String email;
  private McAddressBean address;

  public McNestedBean() {}

  public McNestedBean(final Long id, final String email, final McAddressBean address) {
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
