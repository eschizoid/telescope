package io.github.eschizoid.telescope;

/**
 * A source bean that inherits {@code label} from an abstract base and declares {@code n} itself.
 */
public final class SourceRootAbstractSub extends SourceRootAbstractBase {

  private int n;

  public SourceRootAbstractSub() {}

  public SourceRootAbstractSub(final String label, final int n) {
    setLabel(label);
    this.n = n;
  }

  public int getN() {
    return n;
  }

  public void setN(final int n) {
    this.n = n;
  }
}
