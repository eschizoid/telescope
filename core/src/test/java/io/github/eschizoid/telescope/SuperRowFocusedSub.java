package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.annotations.BeanFocus;

/**
 * A setter bean that inherits {@code name} from an abstract base, compiled with a generated holder
 * so a path started on it writes through the holder's lens.
 */
@BeanFocus
public class SuperRowFocusedSub extends SuperRowAbstractBase {

  private int n;

  public SuperRowFocusedSub() {}

  public int getN() {
    return n;
  }

  public void setN(final int n) {
    this.n = n;
  }
}
