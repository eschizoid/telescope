package io.github.eschizoid.telescope.collections;

import java.io.Serial;
import java.io.Serializable;

/** A line of a {@link ZOrder}, which is {@link Serializable}. */
public class ZLine implements Serializable {

  @Serial
  private static final long serialVersionUID = 1L;

  private String sku;

  public ZLine() {}

  public String getSku() {
    return sku;
  }

  public void setSku(final String sku) {
    this.sku = sku;
  }

  @Override
  public String toString() {
    return sku;
  }
}
