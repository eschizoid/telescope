package io.github.eschizoid.telescope;

import java.util.List;

/** An abstract base that declares a nested record {@code child} and a list {@code tags}. */
public abstract class SuperRowHolderBase {

  private SuperRowSource child;
  private List<String> tags;

  public SuperRowSource getChild() {
    return child;
  }

  public void setChild(final SuperRowSource child) {
    this.child = child;
  }

  public List<String> getTags() {
    return tags;
  }

  public void setTags(final List<String> tags) {
    this.tags = tags;
  }
}
