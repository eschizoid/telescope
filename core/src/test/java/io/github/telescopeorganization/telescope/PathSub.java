package io.github.telescopeorganization.telescope;

/** A bean whose {@code customer} getter its superclass declares, beside a property of its own. */
public class PathSub extends PathBase {

  private String note;

  public PathSub() {}

  public String getNote() {
    return note;
  }

  public void setNote(final String note) {
    this.note = note;
  }
}
