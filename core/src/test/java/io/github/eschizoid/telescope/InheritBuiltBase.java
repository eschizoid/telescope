package io.github.eschizoid.telescope;

/** An abstract generic base with read-only properties, built only through a subclass's builder. */
public abstract class InheritBuiltBase<T extends InheritBuiltBase<T>> {

  private final T next;
  private final String label;

  protected InheritBuiltBase(final T next, final String label) {
    this.next = next;
    this.label = label;
  }

  public T getNext() {
    return next;
  }

  public String getLabel() {
    return label;
  }
}
