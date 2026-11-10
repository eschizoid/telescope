package io.github.telescopeorganization.telescope;

import static io.github.telescopeorganization.telescope.mapping.Mapping.constant;
import static io.github.telescopeorganization.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.telescopeorganization.telescope.internal.pairing.PairingMessages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A strict two-way mapper and a getter-only property nothing pairs with: a getter no member of its
 * class writes is derived state, which the mapper leaves out, and one a member writes is refused as
 * any property nothing fills is.
 */
class DerivedGetterPairingTest {

  record Name(String first, String last) {}

  record Shown(String first, String full) {}

  record Value(String value) {}

  /** A bean with setters for {@code first} and {@code last}, and {@code full} derived from them. */
  public static class Person {

    private String first;
    private String last;

    public String getFirst() {
      return first;
    }

    public void setFirst(final String first) {
      this.first = first;
    }

    public String getLast() {
      return last;
    }

    public void setLast(final String last) {
      this.last = last;
    }

    public String getFull() {
      return first + " " + last;
    }
  }

  /** Built by its builder, with a getter alone for {@code value} and for {@code extra}. */
  public static final class Tagged {

    private final String value;
    private final String extra;

    private Tagged(final String value, final String extra) {
      this.value = value;
      this.extra = extra;
    }

    public String getValue() {
      return value;
    }

    public String getExtra() {
      return extra;
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      private String value;
      private String extra;

      public Builder value(final String value) {
        this.value = value;
        return this;
      }

      public Builder extra(final String extra) {
        this.extra = extra;
        return this;
      }

      public Tagged build() {
        return new Tagged(value, extra);
      }
    }
  }

  private static Person person(final String first, final String last) {
    final var person = new Person();
    person.setFirst(first);
    person.setLast(last);
    return person;
  }

  @Test
  @DisplayName("a target's derived getter is left out, and the mapper converts both ways and updates in place")
  void derivedTargetGetter() {
    final var mapper = Telescope.mapper(Name.class, Person.class);
    assertEquals("a b", mapper.forward(new Name("a", "b")).getFull());
    assertEquals(new Name("c", "d"), mapper.backward(person("c", "d")));
    final var managed = person("c", "d");
    mapper.into(managed, new Name("a", "b"));
    assertEquals("a b", managed.getFull());
  }

  @Test
  @DisplayName("a target's derived getter is left out beside a constant row")
  void derivedTargetGetterBesideAConstant() {
    final var mapper = Telescope.mapper(
      Value.class,
      Person.class,
      to(Value::value, Person::getFirst),
      constant(Person::getLast, "k")
    );
    assertEquals("v k", mapper.forward(new Value("v")).getFull());
  }

  @Test
  @DisplayName("a source's derived getter is left out, unread forward and unwritten backward")
  void derivedSourceGetter() {
    final var mapper = Telescope.mapper(Person.class, Name.class);
    assertEquals(new Name("c", "d"), mapper.forward(person("c", "d")));
    assertEquals("a b", mapper.backward(new Name("a", "b")).getFull());
  }

  @Test
  @DisplayName("a source's derived getter whose namesake a rename of another property fills is left out")
  void derivedSourceGetterWhoseNamesakeIsTaken() {
    final var mapper = Telescope.mapper(Person.class, Shown.class, to(Person::getLast, Shown::full));
    assertEquals(new Shown("c", "d"), mapper.forward(person("c", "d")));
    assertEquals("a b", mapper.backward(new Shown("a", "b")).getFull());
  }

  @Test
  @DisplayName("a getter-only property a builder member writes is refused on the target and on the source")
  void writableGetterOnlyPropertyIsRefused() {
    final var target = assertThrows(IllegalStateException.class, () -> Telescope.mapper(Value.class, Tagged.class));
    assertEquals(
      PairingMessages.noSameNameSource("Value", "Tagged", "property", "field", "extra"),
      target.getMessage()
    );
    final var source = assertThrows(IllegalStateException.class, () -> Telescope.mapper(Tagged.class, Value.class));
    assertEquals(
      PairingMessages.noSameNameTarget("Tagged", "Value", "property", "field", "extra"),
      source.getMessage()
    );
  }
}
