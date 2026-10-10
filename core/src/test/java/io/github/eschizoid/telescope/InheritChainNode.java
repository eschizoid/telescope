package io.github.eschizoid.telescope;

/** A setter bean whose {@code next} and {@code label} come from an abstract generic base. */
public class InheritChainNode extends InheritChainBase<InheritChainNode> {

  private String tag;

  public InheritChainNode() {}

  public String getTag() {
    return tag;
  }

  public void setTag(final String tag) {
    this.tag = tag;
  }
}
