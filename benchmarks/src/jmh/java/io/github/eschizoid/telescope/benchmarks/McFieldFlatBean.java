package io.github.eschizoid.telescope.benchmarks;

/**
 * The flat-tier bean of {@link MapStructComparisonBenchmark} again, for the generated mapper {@link
 * McFieldMappers#FLAT} declares on its field: the same five properties, so the field form and
 * {@code @Bridge} on {@link McFlatBean} generate the same conversion for the same work.
 */
public class McFieldFlatBean {

  private Long id;
  private String email;
  private String name;
  private int age;
  private boolean active;

  public McFieldFlatBean() {}

  public McFieldFlatBean(final Long id, final String email, final String name, final int age, final boolean active) {
    this.id = id;
    this.email = email;
    this.name = name;
    this.age = age;
    this.active = active;
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

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public int getAge() {
    return age;
  }

  public void setAge(final int age) {
    this.age = age;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(final boolean active) {
    this.active = active;
  }
}
