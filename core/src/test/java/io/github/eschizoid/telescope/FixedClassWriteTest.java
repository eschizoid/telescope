package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A value handed across unchanged into a side whose class fixes the type variable its property is
 * declared with. The suite also runs on the accessor substrate a native image uses, so both the
 * composed rebuild and its image counterpart check each value.
 */
class FixedClassWriteTest {

  public static class Base<K, V> {

    private K key;
    private V first;

    public K getKey() {
      return key;
    }

    public void setKey(final K key) {
      this.key = key;
    }

    public V getFirst() {
      return first;
    }

    public void setFirst(final V first) {
      this.first = first;
    }
  }

  /** Passes its own variable through. */
  public static class Passing<X> extends Base<String, X> {}

  /** Fixes the variable to {@code String}. */
  public static class Fixed extends Base<String, String> {}

  public static class ListBase<V> {

    private List<V> items;

    public List<V> getItems() {
      return items;
    }

    public void setItems(final List<V> items) {
      this.items = items;
    }
  }

  public static class PassingList<X> extends ListBase<X> {}

  public static class FixedList extends ListBase<String> {}

  private static <X> Passing<X> passing(final X first) {
    final var p = new Passing<X>();
    p.setKey("k");
    p.setFirst(first);
    return p;
  }

  @Test
  @DisplayName("a value of the class the target fixes converts")
  @SuppressWarnings({ "rawtypes", "unchecked" })
  void aFittingValueConverts() {
    final var mapper = Telescope.mapper((Class<Passing>) (Class) Passing.class, Fixed.class);
    final Fixed out = mapper.forward(passing("a"));
    assertEquals("k", out.getKey());
    assertEquals("a", out.getFirst());
  }

  @Test
  @DisplayName("a value of another class is refused naming the property, the class found and the class fixed")
  @SuppressWarnings({ "rawtypes", "unchecked" })
  void aValueOfAnotherClassIsRefusedForward() {
    final var mapper = Telescope.mapper((Class<Passing>) (Class) Passing.class, Fixed.class);
    final var refused = assertThrows(ClassCastException.class, () -> mapper.forward(passing(42)));
    assertEquals(
      PairingMessages.fixedClassMismatch(Fixed.class.getTypeName(), "first", "java.lang.Integer", "java.lang.String"),
      refused.getMessage()
    );
  }

  @Test
  @DisplayName("a value written backward into a source that fixes the variable is checked against the source's class")
  @SuppressWarnings({ "rawtypes", "unchecked" })
  void aValueOfAnotherClassIsRefusedBackward() {
    final var mapper = Telescope.mapper(Fixed.class, (Class<Passing>) (Class) Passing.class);
    final var refused = assertThrows(ClassCastException.class, () -> mapper.backward(passing(42)));
    assertEquals(
      PairingMessages.fixedClassMismatch(Fixed.class.getTypeName(), "first", "java.lang.Integer", "java.lang.String"),
      refused.getMessage()
    );
  }

  @Test
  @DisplayName("a container whose elements cannot be shown to fit is refused when the mapper is built")
  @SuppressWarnings({ "rawtypes", "unchecked" })
  void aContainerIsRefusedWhenBuilt() {
    final var refused = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper((Class<PassingList>) (Class) PassingList.class, FixedList.class)
    );
    assertEquals(
      PairingMessages.fixedArgumentsDoNotFit("items", "java.util.List<X>", "java.util.List<java.lang.String>"),
      refused.getMessage()
    );
  }
}
