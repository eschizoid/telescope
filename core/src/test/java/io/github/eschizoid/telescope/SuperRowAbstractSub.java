package io.github.eschizoid.telescope;

/** A setter bean that inherits {@code name} from an abstract base and declares {@code n} itself. */
public class SuperRowAbstractSub extends SuperRowAbstractBase {

  private int n;

  public SuperRowAbstractSub() {}

  public int getN() {
    return n;
  }

  public void setN(final int n) {
    this.n = n;
  }
}
