package io.github.eschizoid.telescope;

/** An abstract base that declares the nested property {@code entry}. */
public abstract class RowHolderBase {

  private RowNamedSub entry;

  public RowNamedSub getEntry() {
    return entry;
  }

  public void setEntry(final RowNamedSub entry) {
    this.entry = entry;
  }
}
