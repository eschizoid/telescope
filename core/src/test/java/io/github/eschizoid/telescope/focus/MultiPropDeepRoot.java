package io.github.eschizoid.telescope.focus;

import io.github.eschizoid.telescope.annotations.BeanFocus;

/**
 * Extra root level above {@link MultiPropWriteOuter}, pushing the multi-property {@link
 * MultiPropLeafAddress} to hop 3 (reached through two null single-property intermediates). Pins
 * that the off-path null-guard is N-hop, not specific to a single nesting depth: a fix that
 * happened to construct the hop-1 intermediate eagerly would pass the hop-2 test but fail here.
 */
@BeanFocus
public class MultiPropDeepRoot {

  private MultiPropWriteOuter outer;

  public MultiPropDeepRoot() {}

  public MultiPropWriteOuter getOuter() {
    return outer;
  }

  public void setOuter(final MultiPropWriteOuter outer) {
    this.outer = outer;
  }
}
