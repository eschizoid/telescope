package io.github.eschizoid.telescope;

/** A source bean that inherits {@code label} from a concrete base and declares {@code n} itself. */
public final class SourceRootSub extends SourceRootBase {

  private int n;

  public SourceRootSub() {}

  public SourceRootSub(final String label, final int n) {
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
