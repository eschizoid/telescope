package io.github.eschizoid.telescope;

/** A builder bean whose {@code next} and {@code label} come from an abstract generic base. */
public final class InheritBuiltNode extends InheritBuiltBase<InheritBuiltNode> {

  private final String tag;

  private InheritBuiltNode(final InheritBuiltNode next, final String label, final String tag) {
    super(next, label);
    this.tag = tag;
  }

  public String getTag() {
    return tag;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {

    private InheritBuiltNode next;
    private String label;
    private String tag;

    public Builder next(final InheritBuiltNode next) {
      this.next = next;
      return this;
    }

    public Builder label(final String label) {
      this.label = label;
      return this;
    }

    public Builder tag(final String tag) {
      this.tag = tag;
      return this;
    }

    public InheritBuiltNode build() {
      return new InheritBuiltNode(next, label, tag);
    }
  }
}
