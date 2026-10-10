package io.github.eschizoid.telescope;

/** A setter bean that implements {@link SuperRowBeanNamed} and declares {@code n} itself. */
public class SuperRowNamedBean implements SuperRowBeanNamed {

  private String name;
  private int n;

  public SuperRowNamedBean() {}

  @Override
  public String getName() {
    return name;
  }

  @Override
  public void setName(final String name) {
    this.name = name;
  }

  public int getN() {
    return n;
  }

  public void setN(final int n) {
    this.n = n;
  }
}
