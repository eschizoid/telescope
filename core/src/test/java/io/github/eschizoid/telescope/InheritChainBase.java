package io.github.eschizoid.telescope;

/** An abstract generic base that declares the properties {@code next} and {@code label}. */
public abstract class InheritChainBase<T extends InheritChainBase<T>> {

  private T next;
  private String label;

  public T getNext() {
    return next;
  }

  public void setNext(final T next) {
    this.next = next;
  }

  public String getLabel() {
    return label;
  }

  public void setLabel(final String label) {
    this.label = label;
  }
}
