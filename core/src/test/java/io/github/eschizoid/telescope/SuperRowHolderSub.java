package io.github.eschizoid.telescope;

/** A setter bean that inherits {@code child} and {@code tags} and declares {@code n} itself. */
public class SuperRowHolderSub extends SuperRowHolderBase {

  private int n;

  public SuperRowHolderSub() {}

  public int getN() {
    return n;
  }

  public void setN(final int n) {
    this.n = n;
  }
}
