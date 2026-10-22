package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.conversion.ForwardMapper;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.mapping.MapStep;
import io.github.eschizoid.telescope.mapping.Mapping;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A runtime row typed by a class's type arguments reads a property declared with a type variable as
 * that argument, which nothing checked when the property was read. The value read is checked
 * against that class before the row's function is handed it, and a value of another class is
 * refused by name, as a generated bridge refuses it. The suite also runs on the accessor substrate
 * a native image uses.
 */
class RowArgumentReadTest {

  public record Box<X>(X value) {}

  public record Out<Y>(Y value) {}

  public record Numbered<T extends Number>(T value) {}

  public record Plain(Integer value) {}

  /** The runtime mapper for two generic classes, typed by the arguments its rows read them with. */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static <A, B> Mapper<A, B> typedMapper(final Class source, final Class target, final MapStep... rows) {
    return Telescope.mapper(source, target, rows);
  }

  /** As {@link #typedMapper}, forward only. */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static <A, B> ForwardMapper<A, B> typedForward(
    final Class source,
    final Class target,
    final MapStep... rows
  ) {
    return Telescope.mapperForward(source, target, rows);
  }

  /** A box holding {@code value}, typed as a box of strings whatever {@code value} is. */
  @SuppressWarnings("unchecked")
  private static Box<String> polluted(final Object value) {
    return (Box<String>) (Box<?>) new Box<>(value);
  }

  private static Mapper<Box<String>, Out<String>> strings() {
    return typedMapper(
      Box.class,
      Out.class,
      Mapping.<Box<String>, Out<String>, String, String>to(Box::value, Out::value, s -> s + "!", s -> s + "?")
    );
  }

  @Test
  @DisplayName("a value of the class the row reads is converted in either direction")
  void aValueOfTheReadClassIsConverted() {
    final var mapper = strings();
    assertEquals(new Out<>("a!"), mapper.forward(new Box<>("a")));
    assertEquals(new Box<>("q?"), mapper.backward(new Out<>("q")));
  }

  @Test
  @DisplayName("a value of another class is refused naming the property, its class and the class the row reads")
  @SuppressWarnings("unchecked")
  void aValueOfAnotherClassIsRefusedByName() {
    final var mapper = strings();
    final var forward = assertThrows(ClassCastException.class, () -> mapper.forward(polluted(5)));
    assertEquals(
      PairingMessages.argumentClassMismatch(Box.class.getTypeName(), "value", "java.lang.Integer", "java.lang.String"),
      forward.getMessage()
    );
    final var backward = assertThrows(ClassCastException.class, () ->
      mapper.backward((Out<String>) (Out<?>) new Out<>(6))
    );
    assertEquals(
      PairingMessages.argumentClassMismatch(Out.class.getTypeName(), "value", "java.lang.Integer", "java.lang.String"),
      backward.getMessage()
    );
  }

  @Test
  @DisplayName("a row reading a bounded variable as a narrower class checks the side it narrows")
  @SuppressWarnings("unchecked")
  void aBoundedVariableIsCheckedAgainstTheNarrowerClass() {
    final Mapper<Numbered<Integer>, Plain> mapper = typedMapper(
      Numbered.class,
      Plain.class,
      Mapping.<Numbered<Integer>, Plain, Integer, Integer>to(Numbered::value, Plain::value, n -> n + 1, n -> n - 1)
    );
    assertEquals(new Plain(8), mapper.forward(new Numbered<>(7)));
    assertEquals(new Numbered<>(40), mapper.backward(new Plain(41)));
    final var refused = assertThrows(ClassCastException.class, () ->
      mapper.forward((Numbered<Integer>) (Numbered<?>) new Numbered<>(7L))
    );
    assertEquals(
      PairingMessages.argumentClassMismatch(
        Numbered.class.getTypeName(),
        "value",
        "java.lang.Long",
        "java.lang.Integer"
      ),
      refused.getMessage()
    );
  }

  @Test
  @DisplayName("a forward-only row checks the value it reads as a forward row does")
  void aForwardOnlyRowChecksItsRead() {
    final ForwardMapper<Box<String>, Out<String>> mapper = typedForward(
      Box.class,
      Out.class,
      Mapping.<Box<String>, Out<String>, String, String>toOneWay(Box::value, Out::value, s -> s + "!")
    );
    assertEquals(new Out<>("a!"), mapper.forward(new Box<>("a")));
    final var refused = assertThrows(ClassCastException.class, () -> mapper.forward(polluted(5)));
    assertEquals(
      PairingMessages.argumentClassMismatch(Box.class.getTypeName(), "value", "java.lang.Integer", "java.lang.String"),
      refused.getMessage()
    );
  }

  /** A bean whose one property is declared with its type variable. */
  public static class GenericBean<X> {

    private X value;

    public X getValue() {
      return value;
    }

    public void setValue(final X value) {
      this.value = value;
    }
  }

  /** Fixes the superclass's variable to {@code String}. */
  public static class StringBean extends GenericBean<String> {}

  public record Words(String value) {}

  @Test
  @DisplayName("a row reading an inherited getter through a subclass that fixes its variable checks the value by name")
  @SuppressWarnings({ "unchecked", "rawtypes" })
  void aSubclassFixingTheVariableIsCheckedByName() {
    final Mapper<StringBean, Words> mapper = typedMapper(
      StringBean.class,
      Words.class,
      Mapping.<StringBean, Words, String, String>to(StringBean::getValue, Words::value, s -> s + "!", s -> s + "?")
    );
    final var fitting = new StringBean();
    fitting.setValue("a");
    assertEquals(new Words("a!"), mapper.forward(fitting));
    final var polluted = new StringBean();
    ((GenericBean) polluted).setValue(5);
    final var refused = assertThrows(ClassCastException.class, () -> mapper.forward(polluted));
    assertEquals(
      PairingMessages.argumentClassMismatch(
        StringBean.class.getTypeName(),
        "value",
        "java.lang.Integer",
        "java.lang.String"
      ),
      refused.getMessage()
    );
  }

  @Test
  @DisplayName("a row reading a property as the class it is declared hands every value on, as it did")
  void aRowReadingTheDeclaredClassChecksNothing() {
    final Mapper<Box<Object>, Out<Object>> mapper = typedMapper(
      Box.class,
      Out.class,
      Mapping.<Box<Object>, Out<Object>, Object, Object>to(Box::value, Out::value, v -> "<" + v + ">", v -> v)
    );
    assertEquals(new Out<>("<5>"), mapper.forward(new Box<>(5)));
  }
}
