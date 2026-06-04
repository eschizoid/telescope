package com.github.eschizoid.telescope.benchmarks;

/** The {@code @Bridge} target for {@link BenchUserA} — same field names (a bijection). */
public final class BenchUserB {

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
