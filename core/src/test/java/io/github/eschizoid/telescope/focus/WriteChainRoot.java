package io.github.eschizoid.telescope.focus;

import io.github.eschizoid.telescope.annotations.BeanFocus;

/**
 * Root of a 4-hop {@code @BeanFocus} write chain: {@code root → outer → mid → leaf}. Used as a
 * sanity guard that auto-construction of null intermediates generalises beyond 3 hops — N-hop
 * semantics, not 3-hop semantics.
 */
@BeanFocus
public class WriteChainRoot {

  private WriteChainOuter outer;

  public WriteChainRoot() {}

  public WriteChainOuter getOuter() {
    return outer;
  }

  public void setOuter(final WriteChainOuter outer) {
    this.outer = outer;
  }
}
