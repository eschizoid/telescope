package io.github.eschizoid.telescope.codegen.genericpass;

import java.util.List;

/**
 * A superclass whose properties are typed by its own variable.
 *
 * @param <T> what the subclass passes through
 */
public class GpBase<T> {

  private T first;
  private List<T> items;

  /**
   * The first value.
   *
   * @return the first value
   */
  public T getFirst() {
    return first;
  }

  /**
   * Sets the first value.
   *
   * @param first the first value
   */
  public void setFirst(final T first) {
    this.first = first;
  }

  /**
   * The values.
   *
   * @return the values
   */
  public List<T> getItems() {
    return items;
  }

  /**
   * Sets the values.
   *
   * @param items the values
   */
  public void setItems(final List<T> items) {
    this.items = items;
  }
}
