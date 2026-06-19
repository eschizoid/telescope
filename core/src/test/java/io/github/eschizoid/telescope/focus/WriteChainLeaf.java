package io.github.eschizoid.telescope.focus;

import io.github.eschizoid.telescope.annotations.BeanFocus;

/**
 * Leaf of a {@code root → outer → mid → leaf} {@code @BeanFocus} write chain. Used to pin
 * auto-construction of null intermediates when a multi-hop write path descends through fields that
 * are null at construction time.
 */
@BeanFocus
public class WriteChainLeaf {

  private String value;

  public WriteChainLeaf() {}

  public String getValue() {
    return value;
  }

  public void setValue(final String value) {
    this.value = value;
  }
}
