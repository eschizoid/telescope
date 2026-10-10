package io.github.eschizoid.telescope;

/** A setter bean that fixes its generic base's {@code name} to a string and declares {@code n}. */
public class SuperRowGenericSub extends SuperRowGenericBase<String> {

  private int n;

  public SuperRowGenericSub() {}

  public int getN() {
    return n;
  }

  public void setN(final int n) {
    this.n = n;
  }
}
