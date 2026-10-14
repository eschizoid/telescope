package io.github.eschizoid.telescope.ownbridge;

/** One side of a pair of beans that refer to each other. */
public class ObCa {

  private String name;
  private ObCb b;

  public ObCa() {}

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public ObCb getB() {
    return b;
  }

  public void setB(final ObCb b) {
    this.b = b;
  }
}
