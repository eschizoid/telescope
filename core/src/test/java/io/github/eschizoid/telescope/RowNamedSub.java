package io.github.eschizoid.telescope;

/** A setter bean whose {@code name} and {@code code} come from an abstract base. */
public class RowNamedSub extends RowNamedBase {

  private String tag;

  public RowNamedSub() {}

  public String getTag() {
    return tag;
  }

  public void setTag(final String tag) {
    this.tag = tag;
  }
}
