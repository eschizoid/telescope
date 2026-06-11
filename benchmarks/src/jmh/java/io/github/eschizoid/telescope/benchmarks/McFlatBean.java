package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Bridge;

/**
 * Flat-tier POJO fixture for {@link MapStructComparisonBenchmark}. Five scalar fields, no-arg ctor
 * + getters + setters — the shape both MapStruct's generated impl and telescope's bean writer
 * understand natively.
 *
 * <p>{@code @Bridge(McFlatRec.class)} on this bean drives the codegen path: the {@code @Bridge}
 * processor emits a sibling {@code McFlatBeanBridge.BRIDGE : Telescope<McFlatBean, McFlatRec>}
 * built from direct method-ref + canonical-constructor calls, no reflection.
 */
@Bridge(McFlatRec.class)
public class McFlatBean {

  private Long id;
  private String email;
  private String name;
  private int age;
  private boolean active;

  public McFlatBean() {}

  public McFlatBean(final Long id, final String email, final String name, final int age, final boolean active) {
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
