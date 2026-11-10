package io.github.telescopeorganization.telescope.frommap;

import io.github.telescopeorganization.telescope.annotations.FromMap;

/** Bean target: no-arg constructor + setters. */
@FromMap
public class FmBean {

  private String label;
  private int count;

  public String getLabel() {
    return label;
  }

  public void setLabel(final String label) {
    this.label = label;
  }

  public int getCount() {
    return count;
  }

  public void setCount(final int count) {
    this.count = count;
  }
}
