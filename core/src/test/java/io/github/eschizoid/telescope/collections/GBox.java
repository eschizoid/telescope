package io.github.eschizoid.telescope.collections;

import java.util.ArrayList;
import java.util.List;

/**
 * A box of items of a class its subclass names, with an adder whose parameter is the type variable.
 *
 * @param <T> the item class
 */
public class GBox<T> {

  private List<T> items = new ArrayList<>();

  /** Each adder call. */
  public final List<String> calls = new ArrayList<>();

  public GBox() {}

  public List<T> getItems() {
    return items;
  }

  public void setItems(final List<T> items) {
    this.items = items;
  }

  public void addItem(final T item) {
    calls.add("addItem");
    items.add(item);
  }
}
