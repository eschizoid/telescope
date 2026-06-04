package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Bridge;

/**
 * Top-level POJO source for the generated {@code @Bridge} benchmark (a mirror of the nested {@code
 * UserBeanA}/{@code UserBeanB} mapBean fixtures, hoisted to top level because {@code @Bridge}
 * generates a sibling class and so requires a top-level type). The processor emits {@code
 * BenchUserABridge.BRIDGE : Telescope<BenchUserA, BenchUserB>}.
 */
@Bridge(BenchUserB.class)
public final class BenchUserA {

  private String id;
  private String email;
  private String name;

  public String getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getName() {
    return name;
  }

  public void setId(final String id) {
    this.id = id;
  }

  public void setEmail(final String email) {
    this.email = email;
  }

  public void setName(final String name) {
    this.name = name;
  }
}
