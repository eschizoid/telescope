package io.github.eschizoid.telescope;

/** A setter bean that inherits {@code name} from a concrete base and declares {@code n} itself. */
public class SuperRowConcreteSub extends SuperRowConcreteBase {

  private int n;

  public SuperRowConcreteSub() {}

  public int getN() {
    return n;
  }

  public void setN(final int n) {
    this.n = n;
  }
}
