package com.github.eschizoid.telescope.beans;

import com.github.eschizoid.telescope.annotations.Bridge;

/** Codegen fixture: POJO&harr;POJO bridge source (no-arg constructor + setters on both sides). */
@Bridge(BridgePojoB.class)
public final class BridgePojoA {

  private String id;
  private String email;

  public BridgePojoA() {}

  public String getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public void setId(final String id) {
    this.id = id;
  }

  public void setEmail(final String email) {
    this.email = email;
  }
}
