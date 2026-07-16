package io.github.eschizoid.telescope.nativesmoke;

import io.github.eschizoid.telescope.annotations.Bridge;

/**
 * A classic mutable JavaBean source for the bean-mapper and {@code @Bridge} smoke capabilities.
 * {@code @Bridge(SmokeBeanB.class)} drives the {@code :codegen} processor to emit {@code
 * SmokeBeanABridge.BRIDGE : Telescope<SmokeBeanA, SmokeBeanB>}. The no-arg constructor + public
 * setters are exactly the shape {@code Beans}' LMF getter/setter substrate rebuilds through.
 */
@Bridge(SmokeBeanB.class)
public final class SmokeBeanA {

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
