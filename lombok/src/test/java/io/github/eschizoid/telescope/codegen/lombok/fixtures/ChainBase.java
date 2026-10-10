package io.github.eschizoid.telescope.codegen.lombok.fixtures;

/**
 * Fixture: a superclass whose property is typed by its own self-bound variable, written by hand so
 * the property reaches the subclass only through inheritance.
 *
 * @param <T> the subclass
 */
public abstract class ChainBase<T extends ChainBase<T>> {

  private T next;

  /**
   * The next link.
   *
   * @return the next link
   */
  public T getNext() {
    return next;
  }

  /**
   * Sets the next link.
   *
   * @param next the next link
   */
  public void setNext(final T next) {
    this.next = next;
  }
}
