package io.github.eschizoid.telescope.ownbridge;

/** A generic bean base whose one property is typed by its type variable. */
public class ObGBase<T> {

  private T value;

  public ObGBase() {}

  public T getValue() {
    return value;
  }

  public void setValue(final T value) {
    this.value = value;
  }
}
