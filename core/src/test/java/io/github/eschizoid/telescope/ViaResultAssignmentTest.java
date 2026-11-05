package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.via;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.mapping.Mapping;
import java.io.Serializable;
import java.lang.constant.Constable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@code via} row's method result written into the member its property's type chooses, as Java's
 * assignment converts it: a primitive widened, a wrapper unboxed and widened, and a result no
 * assignment conversion or check of its class lets the member take refused when the mapper is
 * built, naming the row.
 */
class ViaResultAssignmentTest {

  public record Boxed(Integer v) {}

  public record Wide(long v) {}

  public record Floating(float v) {}

  public record Lettered(int v) {}

  public record Narrow(short v) {}

  public record WideBox(Long v) {}

  public record Text(String v) {}

  public static final class IntResult {

    private IntResult() {}

    public static int forward(final Integer w) {
      return w + 1;
    }

    public static int backward(final long x) {
      return (int) (x - 1);
    }
  }

  public static final class IntoFloat {

    private IntoFloat() {}

    public static int forward(final Integer w) {
      return w + 1;
    }

    public static Integer backward(final float x) {
      return (int) x - 1;
    }
  }

  public static final class CharResult {

    private CharResult() {}

    public static char forward(final Integer w) {
      return (char) ('a' + w);
    }

    public static Integer backward(final int x) {
      return x - 'a';
    }
  }

  public static final class ShortInto {

    private ShortInto() {}

    public static int forward(final Integer w) {
      return w;
    }

    public static Integer backward(final short x) {
      return (int) x;
    }
  }

  public static final class LongInto {

    private LongInto() {}

    public static int forward(final Integer w) {
      return w;
    }

    public static Integer backward(final Long x) {
      return x.intValue();
    }
  }

  public static final class StringInto {

    private StringInto() {}

    public static Integer forward(final Integer w) {
      return w;
    }

    public static Integer backward(final String x) {
      return x.length();
    }
  }

  @Test
  @DisplayName("an int result widens into a long component forward, and a long result back into an Integer one")
  void anIntResultWidensIntoALong() {
    final var mapper = Telescope.mapper(Boxed.class, Wide.class, via(Boxed::v, Wide::v, IntResult.class));
    assertEquals(new Wide(6), mapper.forward(new Boxed(5)));
    assertEquals(new Boxed(5), mapper.backward(new Wide(6)));
    assertEquals(new Wide(0), mapper.forward(new Boxed(null)));
  }

  @Test
  @DisplayName("a row of two method references widens an int result into a long component")
  void aRowOfMethodsWidensItsResult() {
    final var mapper = Telescope.mapper(
      Boxed.class,
      Floating.class,
      via(Boxed::v, Floating::v, IntoFloat::forward, IntoFloat::backward)
    );
    assertEquals(new Floating(6f), mapper.forward(new Boxed(5)));
    assertEquals(new Boxed(5), mapper.backward(new Floating(6f)));
  }

  @Test
  @DisplayName("a char result widens into an int component")
  void aCharResultWidensIntoAnInt() {
    final var mapper = Telescope.mapper(Boxed.class, Lettered.class, via(Boxed::v, Lettered::v, CharResult.class));
    assertEquals(new Lettered('f'), mapper.forward(new Boxed(5)));
  }

  @Test
  @DisplayName(
    "an int result into a short or a Long, and an Integer into a String, is refused when the mapper is built"
  )
  void aResultNoAssignmentTakesIsRefused() {
    final var narrow = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(Boxed.class, Narrow.class, via(Boxed::v, Narrow::v, ShortInto.class))
    );
    assertTrue(
      narrow.getMessage().contains("an Integer, into the target's 'v', which takes Short"),
      narrow::getMessage
    );
    final var boxed = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(Boxed.class, WideBox.class, via(Boxed::v, WideBox::v, LongInto.class))
    );
    assertTrue(boxed.getMessage().contains("no Integer is a Long"), boxed::getMessage);
    final var text = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(Boxed.class, Text.class, via(Boxed::v, Text::v, StringInto.class))
    );
    assertTrue(text.getMessage().contains("no Integer is a String"), text::getMessage);
  }

  public record Strings(List<String> v) {}

  public record Integers(List<Integer> v) {}

  public record Keyed(Map<String, String> v) {}

  public record Supplied(Supplier<String> v) {}

  public record Present(Optional<Integer> v) {}

  public record Numbers(List<Integer> v) {}

  @SuppressWarnings("rawtypes")
  public record Raw(List v) {}

  public static final class SameList {

    private SameList() {}

    public static <T> List<T> forward(final List<T> a) {
      return a;
    }

    public static List<String> backward(final List<Integer> b) {
      return List.of();
    }
  }

  public static final class FromMap {

    private FromMap() {}

    public static <T> List<T> forward(final Map<String, T> a) {
      return List.copyOf(a.values());
    }

    public static Map<String, String> backward(final List<Integer> b) {
      return Map.of();
    }
  }

  public static final class FromSupplier {

    private FromSupplier() {}

    public static <T> Optional<T> forward(final Supplier<T> a) {
      return Optional.ofNullable(a.get());
    }

    public static Supplier<String> backward(final Optional<Integer> b) {
      return () -> "";
    }
  }

  public static final class FirstOf {

    private FirstOf() {}

    public static <T> T forward(final List<T> a) {
      return a.getFirst();
    }

    public static List<Integer> backward(final long b) {
      return List.of((int) b);
    }
  }

  public static final class FirstOfRaw {

    private FirstOfRaw() {}

    public static <T> T forward(final List<T> a) {
      return a.getFirst();
    }

    @SuppressWarnings("rawtypes")
    public static List backward(final String b) {
      return List.of(b);
    }
  }

  @Test
  @DisplayName(
    "a generic result its parameter fixes to a type the target's property does not take is refused when the mapper" +
      " is built"
  )
  void aResultItsParameterFixesIsHeldToTheProperty() {
    for (final var built : List.<Runnable>of(
      () -> Telescope.mapper(Strings.class, Integers.class, via(Strings::v, Integers::v, SameList.class)),
      () -> Telescope.mapper(Keyed.class, Integers.class, via(Keyed::v, Integers::v, FromMap.class)),
      () -> Telescope.mapper(Supplied.class, Present.class, via(Supplied::v, Present::v, FromSupplier.class))
    )) {
      final var refused = assertThrows(IllegalArgumentException.class, built::run);
      assertTrue(refused.getMessage().contains("which no instantiation of its type variables"), refused::getMessage);
      assertTrue(refused.getMessage().contains("via(...) of 'v'"), refused::getMessage);
    }
  }

  @Test
  @DisplayName("a generic result its parameter fixes to Integer widens into a long component")
  void aResultItsParameterFixesWidens() {
    final var mapper = Telescope.mapper(Numbers.class, Wide.class, via(Numbers::v, Wide::v, FirstOf.class));
    assertEquals(new Wide(7), mapper.forward(new Numbers(List.of(7))));
  }

  @Test
  @DisplayName("a raw argument erases a generic result, which a String component does not take")
  void aRawArgumentErasesTheResult() {
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(Raw.class, Text.class, via(Raw::v, Text::v, FirstOfRaw.class))
    );
    assertTrue(refused.getMessage().contains("erases to java.lang.Object"), refused::getMessage);
  }

  public record Doubled(double v) {}

  public record Objects(Object v) {}

  public record Elements(List<? extends String> v) {}

  public record Nested(List<List<String>> v) {}

  public record Mixed(Map<String, Integer> v) {}

  public record Words(String[] v) {}

  public record Counts(Integer[] v) {}

  public record Chars(List<CharSequence> v) {}

  public record Same(int v) {}

  /**
   * The refusal of a row naming {@code using}'s {@code forward} on property {@code v}, finished by
   * {@code why}.
   */
  private static String refusedForward(
    final String source,
    final String target,
    final Class<?> using,
    final String why
  ) {
    return PairingMessages.viaClassRefused(source, target, "v", using.getName(), "forward", why);
  }

  public static final class ByteResult {

    private ByteResult() {}

    public static byte forward(final Integer w) {
      return (byte) (w + 1);
    }

    public static Integer backward(final short x) {
      return x - 1;
    }
  }

  public static final class IntoDouble {

    private IntoDouble() {}

    public static int forward(final Integer w) {
      return w + 1;
    }

    public static Integer backward(final double x) {
      return (int) x - 1;
    }
  }

  public static final class SameInt {

    private SameInt() {}

    public static int forward(final Integer w) {
      return w + 1;
    }

    public static Integer backward(final int x) {
      return x - 1;
    }
  }

  public static final class NullResult {

    private NullResult() {}

    public static Integer forward(final Integer w) {
      return null;
    }

    public static Integer backward(final long x) {
      return (int) x;
    }
  }

  public static final class NumberResult {

    private NumberResult() {}

    public static Number forward(final Integer w) {
      return w > 0 ? (Number) Long.valueOf(w) : (Number) w;
    }

    public static Integer backward(final long x) {
      return (int) x;
    }
  }

  @Test
  @DisplayName("a byte result widens into a short, an int into a double, and an int into an int is written as it is")
  void eachPrimitiveWidening() {
    assertEquals(
      new Narrow((short) 6),
      Telescope.mapper(Boxed.class, Narrow.class, via(Boxed::v, Narrow::v, ByteResult.class)).forward(new Boxed(5))
    );
    final var doubled = Telescope.mapper(Boxed.class, Doubled.class, via(Boxed::v, Doubled::v, IntoDouble.class));
    assertEquals(new Doubled(6d), doubled.forward(new Boxed(5)));
    assertEquals(new Boxed(5), doubled.backward(new Doubled(6d)));
    assertEquals(
      new Same(6),
      Telescope.mapper(Boxed.class, Same.class, via(Boxed::v, Same::v, SameInt.class)).forward(new Boxed(5))
    );
  }

  @Test
  @DisplayName("a null wrapper result widened into a long component is the component's default")
  void aNullResultIsTheDefault() {
    final var mapper = Telescope.mapper(Boxed.class, Wide.class, via(Boxed::v, Wide::v, NullResult.class));
    assertEquals(new Wide(0), mapper.forward(new Boxed(5)));
  }

  @Test
  @DisplayName("a Number result into a long component is checked against Long on each call, as a cast would check it")
  void aWiderReferenceResultIsCheckedPerCall() {
    final var mapper = Telescope.mapper(Boxed.class, Wide.class, via(Boxed::v, Wide::v, NumberResult.class));
    assertEquals(new Wide(5), mapper.forward(new Boxed(5)));
    final var thrown = assertThrows(ClassCastException.class, () -> mapper.forward(new Boxed(-5)));
    assertTrue(thrown.getMessage().contains("'v'"), thrown::getMessage);
  }

  @Test
  @DisplayName("a forward-only mapper writes no source, so only the forward result is held to its member")
  void aForwardOnlyMapperChecksForwardAlone() {
    final var mapper = Telescope.mapperForward(Boxed.class, Wide.class, via(Boxed::v, Wide::v, IntResult.class));
    assertEquals(new Wide(6), mapper.forward(new Boxed(5)));
  }

  public static final class StringsOut {

    private StringsOut() {}

    public static List<String> forward(final List<String> a) {
      return a;
    }

    public static List<String> backward(final List<Integer> b) {
      return List.of();
    }
  }

  public static final class FirstText {

    private FirstText() {}

    public static <T> T forward(final List<T> a) {
      return a.getFirst();
    }

    public static List<Integer> backward(final String b) {
      return List.of(b.length());
    }
  }

  public static final class ToArray {

    private ToArray() {}

    @SuppressWarnings("unchecked")
    public static <T> T[] forward(final List<T> a) {
      return (T[]) a.toArray(new String[0]);
    }

    public static List<String> backward(final Object[] b) {
      return List.of();
    }
  }

  @Test
  @DisplayName(
    "a non-generic result whose type arguments the member does not take, and a generic one fixed to a class it never" +
      " holds, are refused by name"
  )
  void resultsTheMemberNeverTakesAreRefusedByName() {
    final var arguments = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(Strings.class, Integers.class, via(Strings::v, Integers::v, StringsOut.class))
    );
    assertEquals(
      refusedForward(
        "Strings",
        "Integers",
        StringsOut.class,
        PairingMessages.viaResultNotTaken(
          "java.util.List<java.lang.String>",
          "Integers",
          "v",
          "java.util.List<java.lang.Integer>"
        )
      ),
      arguments.getMessage()
    );
    final var fixed = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(Numbers.class, Text.class, via(Numbers::v, Text::v, FirstText.class))
    );
    assertEquals(
      refusedForward(
        "Numbers",
        "Text",
        FirstText.class,
        PairingMessages.viaResultNoInstantiation("T", "Text", "v", "java.lang.String")
      ),
      fixed.getMessage()
    );
    final var array = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(Strings.class, Counts.class, via(Strings::v, Counts::v, ToArray.class))
    );
    assertEquals(
      refusedForward(
        "Strings",
        "Counts",
        ToArray.class,
        PairingMessages.viaResultNoInstantiation("T[]", "Counts", "v", "java.lang.Integer[]")
      ),
      array.getMessage()
    );
  }

  @Test
  @DisplayName("a generic array result its parameter fixes to String is written into a String[] component")
  void aGenericArrayResultIsFixed() {
    final var mapper = Telescope.mapperForward(Strings.class, Words.class, via(Strings::v, Words::v, ToArray.class));
    assertEquals(List.of("x"), List.of(mapper.forward(new Strings(List.of("x"))).v()));
  }

  public static final class Describe {

    private Describe() {}

    public static <T> String forward(final T a) {
      return String.valueOf(a);
    }

    public static Integer backward(final String b) {
      return b.length();
    }
  }

  public static final class Same2 {

    private Same2() {}

    public static <T> T forward(final T a) {
      return a;
    }

    @SuppressWarnings("rawtypes")
    public static List backward(final List<String> b) {
      return b;
    }
  }

  public static final class Wrap {

    private Wrap() {}

    public static <T> List<T> forward(final T a) {
      return List.of(a);
    }

    public static String backward(final List<CharSequence> b) {
      return b.getFirst().toString();
    }
  }

  public static final class KeyOf {

    private KeyOf() {}

    public static <T> T forward(final Map<T, T> a) {
      return a.keySet().iterator().next();
    }

    public static Map<String, Integer> backward(final Object b) {
      return Map.of(String.valueOf(b), 0);
    }
  }

  public static final class Unfixed {

    private Unfixed() {}

    @SuppressWarnings("unchecked")
    public static <T, U> U forward(final List<T> a) {
      return (U) String.valueOf(a.getFirst());
    }

    public static List<String> backward(final String b) {
      return List.of(b);
    }
  }

  public static final class Bounded {

    private Bounded() {}

    public static <T extends Comparable<T>> T forward(final T a) {
      return a;
    }

    public static <T extends Comparable<T>> T backward(final T b) {
      return b;
    }
  }

  public static final class FirstElement {

    private FirstElement() {}

    public static <T> T forward(final List<T> a) {
      return a.getFirst();
    }

    public static List<String> backward(final String b) {
      return List.of(b);
    }
  }

  public static final class Deep {

    private Deep() {}

    public static <T> T forward(final List<List<T>> a) {
      return a.getFirst().getFirst();
    }

    public static List<List<String>> backward(final Object b) {
      return List.of(List.of(String.valueOf(b)));
    }
  }

  public static final class Untyped {

    private Untyped() {}

    @SuppressWarnings("unchecked")
    public static <T> T forward(final Object a) {
      return (T) String.valueOf(a);
    }

    public static Integer backward(final String b) {
      return b.length();
    }
  }

  @Test
  @DisplayName(
    "a generic result its parameter leaves open, or fixes to a type the member takes, converts as Java's call would"
  )
  void genericResultsTheMemberTakesConvert() {
    assertEquals(
      new Text("5"),
      Telescope.mapper(Boxed.class, Text.class, via(Boxed::v, Text::v, Describe.class)).forward(new Boxed(5))
    );
    assertEquals(
      new Strings(List.of("x")),
      Telescope.mapper(Strings.class, Strings.class, via(Strings::v, Strings::v, Same2.class)).forward(
        new Strings(List.of("x"))
      )
    );
    assertEquals(
      new Strings(List.of("x")),
      Telescope.mapper(Raw.class, Strings.class, via(Raw::v, Strings::v, Same2.class)).forward(new Raw(List.of("x")))
    );
    assertEquals(
      new Chars(List.of("x")),
      Telescope.mapper(Text.class, Chars.class, via(Text::v, Chars::v, Wrap.class)).forward(new Text("x"))
    );
    assertEquals(
      new Objects("k"),
      Telescope.mapper(Mixed.class, Objects.class, via(Mixed::v, Objects::v, KeyOf.class)).forward(
        new Mixed(Map.of("k", 1))
      )
    );
    assertEquals(
      new Text("x"),
      Telescope.mapper(Strings.class, Text.class, via(Strings::v, Text::v, Unfixed.class)).forward(
        new Strings(List.of("x"))
      )
    );
    assertEquals(
      new Text("x"),
      Telescope.mapper(Text.class, Text.class, via(Text::v, Text::v, Bounded.class)).forward(new Text("x"))
    );
    assertEquals(
      new Text("x"),
      Telescope.mapper(Elements.class, Text.class, via(Elements::v, Text::v, FirstElement.class)).forward(
        new Elements(List.of("x"))
      )
    );
    assertEquals(
      new Text("x"),
      Telescope.mapper(Nested.class, Text.class, via(Nested::v, Text::v, Deep.class)).forward(
        new Nested(List.of(List.of("x")))
      )
    );
    assertEquals(
      new Text("5"),
      Telescope.mapper(Boxed.class, Text.class, via(Boxed::v, Text::v, Untyped.class)).forward(new Boxed(5))
    );
  }

  @Test
  @DisplayName("a result a nested type argument fixes to String is refused for an Integer component")
  void aNestedArgumentFixesTheResult() {
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(Nested.class, Boxed.class, via(Nested::v, Boxed::v, Deep.class))
    );
    assertEquals(
      refusedForward(
        "Nested",
        "Boxed",
        Deep.class,
        PairingMessages.viaResultNoInstantiation("T", "Boxed", "v", "java.lang.Integer")
      ),
      refused.getMessage()
    );
  }

  public static final class FirstOfNumber {

    private FirstOfNumber() {}

    public static <T extends Number> T forward(final List<T> a) {
      return a.getFirst();
    }

    @SuppressWarnings("rawtypes")
    public static List backward(final long b) {
      return List.of((int) b);
    }
  }

  public static final class FirstOfInteger {

    private FirstOfInteger() {}

    public static <T extends Integer> T forward(final List<T> a) {
      return a.getFirst();
    }

    @SuppressWarnings("rawtypes")
    public static List backward(final long b) {
      return List.of((int) b);
    }
  }

  public static final class WholeRaw {

    private WholeRaw() {}

    public static <T> List<T> forward(final List<T> a) {
      return a;
    }

    @SuppressWarnings("rawtypes")
    public static List backward(final List<String> b) {
      return b;
    }
  }

  @Test
  @DisplayName(
    "a raw argument erases a generic result, written as a result of the erased class is where the member takes it"
  )
  void anErasedResultIsWrittenAsItsErasure() {
    assertEquals(
      new Strings(List.of("x")),
      Telescope.mapper(Raw.class, Strings.class, via(Raw::v, Strings::v, WholeRaw.class)).forward(new Raw(List.of("x")))
    );
    assertEquals(
      new Wide(5),
      Telescope.mapper(Raw.class, Wide.class, via(Raw::v, Wide::v, FirstOfInteger.class)).forward(new Raw(List.of(5)))
    );
    final var erased = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(Raw.class, Wide.class, via(Raw::v, Wide::v, FirstOfNumber.class))
    );
    assertEquals(
      refusedForward(
        "Raw",
        "Wide",
        FirstOfNumber.class,
        PairingMessages.viaResultErased("T", "java.util.List", "java.lang.Number", "Wide", "v", "long")
      ),
      erased.getMessage()
    );
  }

  public static final class Identity {

    private Identity() {}

    public static <T> T forward(final T a) {
      return a;
    }

    public static <T> T backward(final T b) {
      return b;
    }
  }

  @Test
  @DisplayName("a row of two method references returning their own type variable leaves the result to each call")
  void anInferredReferenceResultIsLeftToTheCall() {
    final var mapper = Telescope.mapper(
      Text.class,
      Text.class,
      via(Text::v, Text::v, Identity::forward, Identity::backward)
    );
    assertEquals(new Text("x"), mapper.forward(new Text("x")));
  }

  public static final class Lengths {

    private Lengths() {}

    public static int forward(final String s) {
      return s.length();
    }

    public static Object backward(final int x) {
      return "#" + x;
    }
  }

  public record Held<X>(X v) {}

  /** {@code raw}, a generic class, as the class of one of its parameterizations. */
  @SuppressWarnings("unchecked")
  private static <T> Class<T> classOf(final Class<?> raw) {
    return (Class<T>) raw;
  }

  public record HeldList<X>(List<X> v) {}

  public record Listed(ArrayList<String> v) {}

  public record Grouped(Map<String, List<Integer>> v) {}

  public record Seq(CharSequence v) {}

  public record Box<Y>(Y v) {}

  public static final class AnyValue {

    private AnyValue() {}

    public static <T> T forward(final T a) {
      return a;
    }

    public static Object backward(final String b) {
      return b;
    }
  }

  public static final class FirstAny {

    private FirstAny() {}

    public static <T> T forward(final List<T> a) {
      return a.getFirst();
    }

    public static List<Object> backward(final String b) {
      return List.of(b);
    }
  }

  public static final class FirstListed {

    private FirstListed() {}

    public static <T> T forward(final List<T> a) {
      return a.getFirst();
    }

    public static ArrayList<String> backward(final Object b) {
      return new ArrayList<>();
    }
  }

  public static final class FirstKey {

    private FirstKey() {}

    public static <T> T forward(final Map<T, List<T>> a) {
      return a.keySet().iterator().next();
    }

    public static Map<String, List<Integer>> backward(final Object b) {
      return Map.of();
    }
  }

  public static final class Bounded2 {

    private Bounded2() {}

    public static <T> List<? extends T> forward(final List<T> a) {
      return a;
    }

    public static List<String> backward(final List<? extends String> b) {
      return List.copyOf(b);
    }
  }

  public static final class FirstSequence {

    private FirstSequence() {}

    public static <T extends CharSequence> T forward(final List<T> a) {
      return a.getFirst();
    }

    @SuppressWarnings("rawtypes")
    public static List backward(final CharSequence b) {
      return List.of(b);
    }
  }

  public static final class IntoAny {

    private IntoAny() {}

    public static int forward(final Integer w) {
      return w + 1;
    }

    public static Integer backward(final Object x) {
      return (Integer) x - 1;
    }
  }

  @Test
  @DisplayName(
    "a property or a type argument a class's own variable types fixes no method variable, which each call then checks"
  )
  void aPropertyTypedByAClassVariableFixesNothing() {
    assertEquals(
      new Text("x"),
      Telescope.<Held<String>, Text>mapper(
        classOf(Held.class),
        Text.class,
        Mapping.<Held<String>, Text>via(Held::v, Text::v, AnyValue.class)
      ).forward(new Held<>("x"))
    );
    assertEquals(
      new Text("x"),
      Telescope.<HeldList<String>, Text>mapper(
        classOf(HeldList.class),
        Text.class,
        Mapping.<HeldList<String>, Text>via(HeldList::v, Text::v, FirstAny.class)
      ).forward(new HeldList<>(List.of("x")))
    );
  }

  @Test
  @DisplayName("a parameter of a supertype reads the type argument a subclass property gives it")
  void aSubclassPropertyFixesTheVariable() {
    final var listed = new Listed(new ArrayList<>(List.of("x")));
    assertEquals(
      new Text("x"),
      Telescope.mapperForward(Listed.class, Text.class, via(Listed::v, Text::v, FirstListed.class)).forward(listed)
    );
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapperForward(Listed.class, Boxed.class, via(Listed::v, Boxed::v, FirstListed.class))
    );
    assertEquals(
      refusedForward(
        "Listed",
        "Boxed",
        FirstListed.class,
        PairingMessages.viaResultNoInstantiation("T", "Boxed", "v", "java.lang.Integer")
      ),
      refused.getMessage()
    );
  }

  @Test
  @DisplayName("two type arguments giving one variable two classes fix nothing, which each call then checks")
  void twoArgumentsGivingOneVariableTwoClassesFixNothing() {
    assertEquals(
      new Objects("k"),
      Telescope.mapper(Grouped.class, Objects.class, via(Grouped::v, Objects::v, FirstKey.class)).forward(
        new Grouped(Map.of("k", List.of(1)))
      )
    );
  }

  @Test
  @DisplayName("a result whose wildcard names the variable is fixed through it and written where the member takes it")
  void aResultWildcardIsFixed() {
    assertEquals(
      new Elements(List.of("x")),
      Telescope.mapper(Strings.class, Elements.class, via(Strings::v, Elements::v, Bounded2.class)).forward(
        new Strings(List.of("x"))
      )
    );
  }

  @Test
  @DisplayName("an erased result of a class the member takes as it is converts")
  void anErasedResultTheMemberTakesConverts() {
    assertEquals(
      new Seq("x"),
      Telescope.mapper(Raw.class, Seq.class, via(Raw::v, Seq::v, FirstSequence.class)).forward(new Raw(List.of("x")))
    );
  }

  @Test
  @DisplayName("a member a class's own variable types is left to the checks each call makes")
  void aMemberTypedByAClassVariableIsLeftToTheCall() {
    final Box<Integer> boxed = Telescope.<Boxed, Box<Integer>>mapper(
      Boxed.class,
      classOf(Box.class),
      Mapping.<Boxed, Box<Integer>>via(Boxed::v, Box::v, IntoAny.class)
    ).forward(new Boxed(5));
    assertEquals(6, boxed.v());
  }

  @SuppressWarnings({ "rawtypes", "serial" })
  public static final class RawNames extends ArrayList {}

  public record Named(RawNames v) {}

  public record Supers(List<? super String> v) {}

  public static final class LowerBounded {

    private LowerBounded() {}

    public static <T> List<? super T> forward(final List<T> a) {
      return a;
    }

    public static List<String> backward(final Object b) {
      return List.of();
    }
  }

  public static final class FirstOfStrings {

    private FirstOfStrings() {}

    @SuppressWarnings("unchecked")
    public static <T> T forward(final List<String> a) {
      return (T) a.getFirst();
    }

    @SuppressWarnings("rawtypes")
    public static List backward(final String b) {
      return List.of(b);
    }
  }

  @Test
  @DisplayName("a class extending its parameter's class raw erases a generic result, which a String does not take")
  void aRawSupertypeErasesTheResult() {
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapperForward(Named.class, Text.class, via(Named::v, Text::v, FirstListed.class))
    );
    assertEquals(
      refusedForward(
        "Named",
        "Text",
        FirstListed.class,
        PairingMessages.viaResultErased(
          "T",
          RawNames.class.getTypeName(),
          "java.lang.Object",
          "Text",
          "v",
          "java.lang.String"
        )
      ),
      refused.getMessage()
    );
  }

  @Test
  @DisplayName("a raw argument erases a generic result whose parameter names none of the method's variables too")
  void aRawArgumentErasesAResultItsParameterDoesNotFix() {
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(Raw.class, Text.class, via(Raw::v, Text::v, FirstOfStrings.class))
    );
    assertTrue(refused.getMessage().contains("erases to java.lang.Object"), refused::getMessage);
  }

  @Test
  @DisplayName("a result whose lower-bounded wildcard names the variable is fixed through it")
  void aLowerBoundedResultIsFixed() {
    assertEquals(
      new Supers(List.of("x")),
      Telescope.mapper(Strings.class, Supers.class, via(Strings::v, Supers::v, LowerBounded.class)).forward(
        new Strings(List.of("x"))
      )
    );
  }

  public static final class Narrowing {

    private Narrowing() {}

    public static Integer forward(final long x) {
      return (int) x + 1;
    }

    public static int backward(final Integer w) {
      return w - 1;
    }
  }

  @Test
  @DisplayName("a result written back into a wider source primitive widens there alone")
  void aBackwardResultWidensAlone() {
    final var mapper = Telescope.mapper(Wide.class, Boxed.class, via(Wide::v, Boxed::v, Narrowing.class));
    assertEquals(new Boxed(6), mapper.forward(new Wide(5)));
    assertEquals(new Wide(5), mapper.backward(new Boxed(6)));
  }

  @Test
  @DisplayName("two method references returning their own type variable convert each element of a list")
  void inferredReferencesConvertEachElement() {
    final var mapper = Telescope.mapper(
      Strings.class,
      Strings.class,
      via(Strings::v, Strings::v, Identity::forward, Identity::backward)
    );
    assertEquals(new Strings(List.of("x", "y")), mapper.forward(new Strings(List.of("x", "y"))));
  }

  @Test
  @DisplayName("a method a property held as Object reaches only by its class writes the result it declares")
  void aMethodReachedByItsClassWritesItsDeclaredResult() {
    final var mapper = Telescope.mapper(Objects.class, Same.class, via(Objects::v, Same::v, Lengths.class));
    assertEquals(new Same(4), mapper.forward(new Objects("abcd")));
  }

  public record Count(int v) {}

  public record Longs(Optional<Long> v) {}

  public record Numbered(Optional<Number> v) {}

  public record Pairs(Map<String, CharSequence> v) {}

  public record SameKeyed(Map<CharSequence, CharSequence> v) {}

  /** A class comparable to itself. */
  public static class Earlier implements Comparable<Earlier> {

    private final int at;

    public Earlier(final int at) {
      this.at = at;
    }

    @Override
    public int compareTo(final Earlier other) {
      return Integer.compare(at, other.at);
    }
  }

  /**
   * A subclass of one comparable to itself, which is comparable to its superclass, not to itself.
   */
  public static final class Later extends Earlier {

    public Later(final int at) {
      super(at);
    }
  }

  public record Stamp(Later v) {}

  public record Stamps(List<Later> v) {}

  public record Dated(Earlier v) {}

  public record Dates(List<Earlier> v) {}

  public record ExtendsInteger(List<? extends Integer> v) {}

  public record ExtendsNumber(List<? extends Number> v) {}

  public record Unbounded(List<?> v) {}

  public record Lowered(List<? super Integer> v) {}

  public static final class Singleton {

    private Singleton() {}

    public static <T> List<T> forward(final T a) {
      return List.of(a);
    }

    public static Object backward(final Object b) {
      return null;
    }
  }

  public static final class Optioned {

    private Optioned() {}

    public static <T> Optional<T> forward(final T a) {
      return Optional.of(a);
    }

    public static Object backward(final Object b) {
      return null;
    }
  }

  public static final class Paired {

    private Paired() {}

    public static <T> Map<T, T> forward(final T a) {
      return Map.of(a, a);
    }

    public static Object backward(final Object b) {
      return null;
    }
  }

  public static final class Ordered {

    private Ordered() {}

    public static <T extends Comparable<T>> List<T> forward(final T a) {
      return List.of(a);
    }

    public static Object backward(final Object b) {
      return null;
    }
  }

  public static final class Captures {

    private Captures() {}

    public static <T> List<T> forward(final List<T> a) {
      return a;
    }

    public static List<Integer> backward(final List<Integer> b) {
      return null;
    }
  }

  public static final class Widening {

    private Widening() {}

    public static <T> List<T> forward(final List<? extends T> a) {
      return List.copyOf(a);
    }

    public static List<Integer> backward(final List<Integer> b) {
      return null;
    }
  }

  private static final String NO_INSTANTIATION = "which no instantiation of its type variables";

  /** The refusal building {@code built} throws, which names the row and the member's type. */
  private static void assertNoInstantiation(final Supplier<?> built, final String declared, final String taken) {
    final var refused = assertThrows(IllegalArgumentException.class, built::get);
    final var message = refused.getMessage();
    assertTrue(message.contains("via(...) of 'v'"), message);
    assertTrue(message.contains("returns " + declared + ", " + NO_INSTANTIATION), message);
    assertTrue(message.endsWith("it takes " + taken), message);
  }

  @Test
  @DisplayName(
    "a variable that is the parameter's whole type, in a type argument of the result, is refused where the target's" +
      " type argument there does not admit the property"
  )
  void aVariableTheArgumentLowersIsHeldToTheTargetsArgument() {
    assertNoInstantiation(
      () ->
        Telescope.mapperForward(
          Text.class,
          Integers.class,
          Mapping.<Text, Integers>via(Text::v, Integers::v, Singleton.class)
        ),
      "java.util.List<T>",
      "java.util.List<java.lang.Integer>"
    );
    assertNoInstantiation(
      () ->
        Telescope.mapperForward(
          Count.class,
          Longs.class,
          Mapping.<Count, Longs>via(Count::v, Longs::v, Optioned.class)
        ),
      "java.util.Optional<T>",
      "java.util.Optional<java.lang.Long>"
    );
  }

  @Test
  @DisplayName("a variable the target's type arguments give two different types is refused")
  void aVariableGivenTwoTypesIsRefused() {
    assertNoInstantiation(
      () -> Telescope.mapperForward(Text.class, Pairs.class, Mapping.<Text, Pairs>via(Text::v, Pairs::v, Paired.class)),
      "java.util.Map<T, T>",
      "java.util.Map<java.lang.String, java.lang.CharSequence>"
    );
  }

  @Test
  @DisplayName(
    "a variable the target's type argument fixes to a class its bound, read with that class, does not admit is refused"
  )
  void aVariableFixedOutsideItsBoundIsRefused() {
    assertNoInstantiation(
      () ->
        Telescope.mapperForward(
          Stamp.class,
          Stamps.class,
          Mapping.<Stamp, Stamps>via(Stamp::v, Stamps::v, Ordered.class)
        ),
      "java.util.List<T>",
      "java.util.List<io.github.eschizoid.telescope.ViaResultAssignmentTest$Later>"
    );
    final var date = new Earlier(0);
    final var dates = Telescope.mapperForward(
      Dated.class,
      Dates.class,
      Mapping.<Dated, Dates>via(Dated::v, Dates::v, Ordered.class)
    );
    assertEquals(new Dates(List.of(date)), dates.forward(new Dated(date)));
  }

  @Test
  @DisplayName(
    "a variable a wildcard argument fixes to its capture, or that bounds a wildcard of the parameter above a capture" +
      " the target's argument does not admit, is refused"
  )
  void aVariableAWildcardArgumentForcesIsHeldToTheTargetsArgument() {
    assertNoInstantiation(
      () ->
        Telescope.mapperForward(
          ExtendsInteger.class,
          Integers.class,
          Mapping.<ExtendsInteger, Integers>via(ExtendsInteger::v, Integers::v, Captures.class)
        ),
      "java.util.List<T>",
      "java.util.List<java.lang.Integer>"
    );
    assertNoInstantiation(
      () ->
        Telescope.mapperForward(
          ExtendsNumber.class,
          Integers.class,
          Mapping.<ExtendsNumber, Integers>via(ExtendsNumber::v, Integers::v, Widening.class)
        ),
      "java.util.List<T>",
      "java.util.List<java.lang.Integer>"
    );
    assertNoInstantiation(
      () ->
        Telescope.mapperForward(
          Unbounded.class,
          Integers.class,
          Mapping.<Unbounded, Integers>via(Unbounded::v, Integers::v, Widening.class)
        ),
      "java.util.List<T>",
      "java.util.List<java.lang.Integer>"
    );
    final var widened = Telescope.mapperForward(
      ExtendsInteger.class,
      Integers.class,
      Mapping.<ExtendsInteger, Integers>via(ExtendsInteger::v, Integers::v, Widening.class)
    );
    assertEquals(new Integers(List.of(7)), widened.forward(new ExtendsInteger(List.of(7))));
  }

  @Test
  @DisplayName(
    "a variable in a type argument of the result converts where the target's argument admits the property, or bounds" +
      " it by a wildcard"
  )
  void aVariableTheTargetAdmitsConverts() {
    final var chars = Telescope.mapperForward(
      Text.class,
      Chars.class,
      Mapping.<Text, Chars>via(Text::v, Chars::v, Singleton.class)
    );
    assertEquals(new Chars(List.of("x")), chars.forward(new Text("x")));
    final var lowered = Telescope.mapperForward(
      Text.class,
      Lowered.class,
      Mapping.<Text, Lowered>via(Text::v, Lowered::v, Singleton.class)
    );
    assertEquals(List.of("x"), lowered.forward(new Text("x")).v());
    final var numbered = Telescope.mapperForward(
      Count.class,
      Numbered.class,
      Mapping.<Count, Numbered>via(Count::v, Numbered::v, Optioned.class)
    );
    assertEquals(new Numbered(Optional.of(7)), numbered.forward(new Count(7)));
    final var keyed = Telescope.mapperForward(
      Text.class,
      SameKeyed.class,
      Mapping.<Text, SameKeyed>via(Text::v, SameKeyed::v, Paired.class)
    );
    assertEquals(new SameKeyed(Map.of("x", "x")), keyed.forward(new Text("x")));
  }

  public record Numerals(List<Number> v) {}

  /** A generic class whose variable's bound names the variable. */
  public record Ranked<E extends Comparable<E>>(E e) {}

  public record AnyRanked(Ranked<?> v) {}

  public record Comparables(List<Comparable<?>> v) {}

  public record SuperStrings(List<? super String> v) {}

  public record ListsOfStrings(List<List<String>> v) {}

  public record ExtendsLists(List<? extends List<String>> v) {}

  public record Weights(Map<String, Integer> v) {}

  public record Ranks(Map<CharSequence, Number> v) {}

  public record LongRanks(Map<CharSequence, Long> v) {}

  public record Compared(Comparable<Integer> v) {}

  public record CharLists(List<List<CharSequence>> v) {}

  public record IntLists(List<List<Integer>> v) {}

  public record SetLists(List<Set<String>> v) {}

  @SuppressWarnings("rawtypes")
  public record RawLists(List<List> v) {}

  public record Labelled(Map<String, CharSequence> v) {}

  public record LooseKeys(Map<? extends List<String>, Integer> v) {}

  public record DeepSets(List<List<Set<String>>> v) {}

  public static final class KeyedLists {

    private KeyedLists() {}

    public static <K, V> Map<K, V> forward(final Map<List<K>, V> a) {
      return Map.of();
    }

    public static Map<List<String>, Integer> backward(final Map<?, ?> b) {
      return null;
    }
  }

  public static final class PairSinks {

    private PairSinks() {}

    public static <K, V> Map<K, V> forward(final Map<? super K, ? super V> a) {
      return Map.of();
    }

    public static Map<String, Integer> backward(final Map<?, ?> b) {
      return null;
    }
  }

  public static final class Growing {

    private Growing() {}

    public static <T> ArrayList<T> forward(final T a) {
      return new ArrayList<>(List.of(a));
    }

    public static String backward(final List<?> b) {
      return null;
    }
  }

  public static final class Deeper {

    private Deeper() {}

    public static <T> List<List<List<T>>> forward(final T a) {
      return List.of(List.of(List.of(a)));
    }

    public static String backward(final List<?> b) {
      return null;
    }
  }

  public static final class Sinks {

    private Sinks() {}

    public static <T> List<T> forward(final List<? super T> a) {
      return List.of();
    }

    public static List<String> backward(final List<?> b) {
      return null;
    }
  }

  public static final class Flattened {

    private Flattened() {}

    public static <T> List<T> forward(final List<List<T>> a) {
      return a.getFirst();
    }

    public static List<List<String>> backward(final List<?> b) {
      return null;
    }
  }

  public static final class FlattenedExtending {

    private FlattenedExtending() {}

    public static <T> List<T> forward(final List<? extends List<T>> a) {
      return a.getFirst();
    }

    public static List<List<String>> backward(final List<?> b) {
      return null;
    }
  }

  public static final class FromRanked {

    private FromRanked() {}

    public static <T> List<T> forward(final Ranked<? extends T> a) {
      return List.of(a.e());
    }

    public static Ranked<Integer> backward(final List<?> b) {
      return null;
    }
  }

  public static final class Weighed {

    private Weighed() {}

    public static <T> List<T> forward(final Map<T, ? extends Number> a) {
      return List.copyOf(a.keySet());
    }

    public static Map<String, Integer> backward(final List<?> b) {
      return null;
    }
  }

  public static final class Pairing {

    private Pairing() {}

    public static <K, V> Map<K, V> forward(final Map<? extends K, ? extends V> a) {
      return Map.copyOf(a);
    }

    public static Map<String, Integer> backward(final Map<?, ?> b) {
      return null;
    }
  }

  public static final class Linked {

    private Linked() {}

    public static <T extends Comparable<U>, U> List<T> forward(final T a) {
      return List.of(a);
    }

    public static String backward(final List<?> b) {
      return null;
    }
  }

  public static final class Wrapped {

    private Wrapped() {}

    public static <T> List<List<T>> forward(final T a) {
      return List.of(List.of(a));
    }

    public static String backward(final List<?> b) {
      return null;
    }
  }

  public static final class Labels {

    private Labels() {}

    public static <T> Map<String, T> forward(final T a) {
      return Map.of("k", a);
    }

    public static String backward(final Map<?, ?> b) {
      return null;
    }
  }

  @Test
  @DisplayName(
    "a variable bounding a wildcard of the parameter from above is a supertype of the type argument the property" +
      " gives there, or of the class's bound for it, a bound naming the class's variable read as its erasure"
  )
  void aVariableBoundingAWildcardFromAboveIsASupertypeOfWhatThePropertyHolds() {
    final var numerals = Telescope.mapperForward(
      Integers.class,
      Numerals.class,
      Mapping.<Integers, Numerals>via(Integers::v, Numerals::v, Widening.class)
    );
    assertEquals(new Numerals(List.of(7)), numerals.forward(new Integers(List.of(7))));
    assertNoInstantiation(
      () ->
        Telescope.mapperForward(
          Integers.class,
          Strings.class,
          Mapping.<Integers, Strings>via(Integers::v, Strings::v, Widening.class)
        ),
      "java.util.List<T>",
      "java.util.List<java.lang.String>"
    );
    final var comparables = Telescope.mapperForward(
      AnyRanked.class,
      Comparables.class,
      Mapping.<AnyRanked, Comparables>via(AnyRanked::v, Comparables::v, FromRanked.class)
    );
    assertEquals(new Comparables(List.of(5)), comparables.forward(new AnyRanked(new Ranked<>(5))));
    assertNoInstantiation(
      () ->
        Telescope.mapperForward(
          AnyRanked.class,
          Integers.class,
          Mapping.<AnyRanked, Integers>via(AnyRanked::v, Integers::v, FromRanked.class)
        ),
      "java.util.List<T>",
      "java.util.List<java.lang.Integer>"
    );
  }

  @Test
  @DisplayName(
    "a variable bounding a wildcard of the parameter from below is a subtype of the type argument the property gives" +
      " there, or of its wildcard's lower bound, and no type under a wildcard bounded from above"
  )
  void aVariableBoundingAWildcardFromBelowIsASubtypeOfWhatThePropertyGives() {
    final var strings = Telescope.mapperForward(
      Strings.class,
      Strings.class,
      Mapping.<Strings, Strings>via(Strings::v, Strings::v, Sinks.class)
    );
    assertEquals(new Strings(List.of()), strings.forward(new Strings(List.of("x"))));
    final var supers = Telescope.mapperForward(
      SuperStrings.class,
      Strings.class,
      Mapping.<SuperStrings, Strings>via(SuperStrings::v, Strings::v, Sinks.class)
    );
    assertEquals(new Strings(List.of()), supers.forward(new SuperStrings(List.of("x"))));
    final var pairs = Telescope.mapperForward(
      Weights.class,
      Weights.class,
      Mapping.<Weights, Weights>via(Weights::v, Weights::v, PairSinks.class)
    );
    assertEquals(new Weights(Map.of()), pairs.forward(new Weights(Map.of("a", 1))));
    assertNoInstantiation(
      () ->
        Telescope.mapperForward(
          Strings.class,
          Chars.class,
          Mapping.<Strings, Chars>via(Strings::v, Chars::v, Sinks.class)
        ),
      "java.util.List<T>",
      "java.util.List<java.lang.CharSequence>"
    );
    assertNoInstantiation(
      () ->
        Telescope.mapperForward(
          ExtendsNumber.class,
          Integers.class,
          Mapping.<ExtendsNumber, Integers>via(ExtendsNumber::v, Integers::v, Sinks.class)
        ),
      "java.util.List<T>",
      "java.util.List<java.lang.Integer>"
    );
  }

  @Test
  @DisplayName(
    "a parameter's type argument naming a variable where the property has a wildcard is met by the capture alone," +
      " while a wildcard of the parameter bounded above by it is not"
  )
  void aWildcardWhereTheParameterNamesAVariableIsMetByTheCaptureAlone() {
    assertNoInstantiation(
      () ->
        Telescope.mapperForward(
          ExtendsLists.class,
          Strings.class,
          Mapping.<ExtendsLists, Strings>via(ExtendsLists::v, Strings::v, Flattened.class)
        ),
      "java.util.List<T>",
      "java.util.List<java.lang.String>"
    );
    final var lists = Telescope.mapperForward(
      ListsOfStrings.class,
      Strings.class,
      Mapping.<ListsOfStrings, Strings>via(ListsOfStrings::v, Strings::v, Flattened.class)
    );
    assertEquals(new Strings(List.of("x")), lists.forward(new ListsOfStrings(List.of(List.of("x")))));
    final var extending = Telescope.mapperForward(
      ExtendsLists.class,
      Strings.class,
      Mapping.<ExtendsLists, Strings>via(ExtendsLists::v, Strings::v, FlattenedExtending.class)
    );
    assertEquals(new Strings(List.of("x")), extending.forward(new ExtendsLists(List.of(List.of("x")))));
    assertNoInstantiation(
      () ->
        Telescope.mapperForward(
          LooseKeys.class,
          Weights.class,
          Mapping.<LooseKeys, Weights>via(LooseKeys::v, Weights::v, KeyedLists.class)
        ),
      "java.util.Map<K, V>",
      "java.util.Map<java.lang.String, java.lang.Integer>"
    );
  }

  @Test
  @DisplayName(
    "each of two variables is held to the target's type argument in its place, and a wildcard of the parameter" +
      " bounded by a class fixes nothing"
  )
  void eachVariableIsHeldToItsOwnPlace() {
    final var ranks = Telescope.mapperForward(
      Weights.class,
      Ranks.class,
      Mapping.<Weights, Ranks>via(Weights::v, Ranks::v, Pairing.class)
    );
    assertEquals(new Ranks(Map.of("a", 1)), ranks.forward(new Weights(Map.of("a", 1))));
    assertNoInstantiation(
      () ->
        Telescope.mapperForward(
          Weights.class,
          LongRanks.class,
          Mapping.<Weights, LongRanks>via(Weights::v, LongRanks::v, Pairing.class)
        ),
      "java.util.Map<K, V>",
      "java.util.Map<java.lang.CharSequence, java.lang.Long>"
    );
    final var keys = Telescope.mapperForward(
      Weights.class,
      Strings.class,
      Mapping.<Weights, Strings>via(Weights::v, Strings::v, Weighed.class)
    );
    assertEquals(new Strings(List.of("a")), keys.forward(new Weights(Map.of("a", 1))));
  }

  @Test
  @DisplayName("a variable whose bound names another variable is held to the target alone, as that bound is not read")
  void aBoundNamingAnotherVariableIsNotRead() {
    final var linked = Telescope.mapperForward(
      Text.class,
      Strings.class,
      Mapping.<Text, Strings>via(Text::v, Strings::v, Linked.class)
    );
    assertEquals(new Strings(List.of("x")), linked.forward(new Text("x")));
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  @Test
  @DisplayName(
    "a generic result converts into its class used raw, is seen as a class it extends through the arguments it gives" +
      " that class, and is refused where the target's class is one it is no subtype of"
  )
  void aGenericResultIsHeldToTheTargetsClass() {
    final var raw = Telescope.mapperForward(
      Text.class,
      Raw.class,
      Mapping.<Text, Raw>via(Text::v, Raw::v, Singleton.class)
    );
    assertEquals(new Raw(List.of("x")), raw.forward(new Text("x")));
    assertNoInstantiation(
      () ->
        Telescope.mapperForward(
          Text.class,
          Compared.class,
          Mapping.<Text, Compared>via(Text::v, Compared::v, Singleton.class)
        ),
      "java.util.List<T>",
      "java.lang.Comparable<java.lang.Integer>"
    );
    final var grown = Telescope.mapperForward(
      Text.class,
      Chars.class,
      Mapping.<Text, Chars>via(Text::v, Chars::v, Growing.class)
    );
    assertEquals(new Chars(List.of("x")), grown.forward(new Text("x")));
    assertNoInstantiation(
      () ->
        Telescope.mapperForward(
          Text.class,
          Integers.class,
          Mapping.<Text, Integers>via(Text::v, Integers::v, Growing.class)
        ),
      "java.util.ArrayList<T>",
      "java.util.List<java.lang.Integer>"
    );
  }

  @Test
  @DisplayName(
    "a generic class in a type argument of the result has to be the target's type argument there exactly: the same" +
      " class, parameterized, with its own arguments held the same way"
  )
  void aGenericClassInAnArgumentIsHeldToTheTargetsExactly() {
    final var chars = Telescope.mapperForward(
      Text.class,
      CharLists.class,
      Mapping.<Text, CharLists>via(Text::v, CharLists::v, Wrapped.class)
    );
    assertEquals(new CharLists(List.of(List.of("x"))), chars.forward(new Text("x")));
    final var labelled = Telescope.mapperForward(
      Text.class,
      Labelled.class,
      Mapping.<Text, Labelled>via(Text::v, Labelled::v, Labels.class)
    );
    assertEquals(new Labelled(Map.of("k", "x")), labelled.forward(new Text("x")));
    assertNoInstantiation(
      () ->
        Telescope.mapperForward(
          Text.class,
          IntLists.class,
          Mapping.<Text, IntLists>via(Text::v, IntLists::v, Wrapped.class)
        ),
      "java.util.List<java.util.List<T>>",
      "java.util.List<java.util.List<java.lang.Integer>>"
    );
    assertNoInstantiation(
      () ->
        Telescope.mapperForward(
          Text.class,
          SetLists.class,
          Mapping.<Text, SetLists>via(Text::v, SetLists::v, Wrapped.class)
        ),
      "java.util.List<java.util.List<T>>",
      "java.util.List<java.util.Set<java.lang.String>>"
    );
    assertNoInstantiation(
      () ->
        Telescope.mapperForward(
          Text.class,
          RawLists.class,
          Mapping.<Text, RawLists>via(Text::v, RawLists::v, Wrapped.class)
        ),
      "java.util.List<java.util.List<T>>",
      "java.util.List<java.util.List>"
    );
    assertNoInstantiation(
      () ->
        Telescope.mapperForward(
          Text.class,
          DeepSets.class,
          Mapping.<Text, DeepSets>via(Text::v, DeepSets::v, Deeper.class)
        ),
      "java.util.List<java.util.List<java.util.List<T>>>",
      "java.util.List<java.util.List<java.util.Set<java.lang.String>>>"
    );
  }

  public record StringArrayList(ArrayList<String> v) {}

  public static final class Collected {

    private Collected() {}

    public static <T> Collection<T> forward(final T a) {
      return List.of(a);
    }

    public static String backward(final Collection<?> b) {
      return null;
    }
  }

  @Test
  @DisplayName(
    "a generic result of a class that is no subtype of the target's class is refused, whatever its type arguments"
  )
  void aGenericResultOfAnUnrelatedClassIsRefused() {
    assertNoInstantiation(
      () ->
        Telescope.mapperForward(
          Text.class,
          StringArrayList.class,
          Mapping.<Text, StringArrayList>via(Text::v, StringArrayList::v, Singleton.class)
        ),
      "java.util.List<T>",
      "java.util.ArrayList<java.lang.String>"
    );
    assertNoInstantiation(
      () ->
        Telescope.mapperForward(
          Text.class,
          Strings.class,
          Mapping.<Text, Strings>via(Text::v, Strings::v, Collected.class)
        ),
      "java.util.Collection<T>",
      "java.util.List<java.lang.String>"
    );
  }

  /** A list class whose variable is bounded by its own enum. */
  public static final class EnumList<E extends Enum<E>> extends ArrayList<E> {

    private static final long serialVersionUID = 1L;

    public EnumList(final List<E> items) {
      super(items);
    }
  }

  /** A list class whose variable is comparable to itself. */
  public static final class NodeList<N extends Comparable<N>> extends ArrayList<N> {

    private static final long serialVersionUID = 1L;

    public NodeList(final List<N> items) {
      super(items);
    }
  }

  public record Enums(EnumList<?> v) {}

  public record Nodes(NodeList<?> v) {}

  public record EnumValues(List<Enum<?>> v) {}

  public record SerializableValues(List<Serializable> v) {}

  @SuppressWarnings("rawtypes")
  public record RawEnums(List<Enum> v) {}

  public record Constables(List<Constable> v) {}

  public static final class FromEnums {

    private FromEnums() {}

    public static <T> List<T> forward(final List<? extends T> a) {
      return List.copyOf(a);
    }

    public static EnumList<?> backward(final List<?> b) {
      return null;
    }
  }

  public static final class FromNodes {

    private FromNodes() {}

    public static <T> List<T> forward(final List<? extends T> a) {
      return List.copyOf(a);
    }

    public static NodeList<?> backward(final List<?> b) {
      return null;
    }
  }

  @Test
  @DisplayName(
    "a wildcard argument of a subclass is a capture bounded by the subclass's own variable, so a supertype of that" +
      " variable's bound admits it"
  )
  void aSubclassWildcardIsBoundedByTheSubclassVariable() {
    final var seconds = new Enums(new EnumList<>(List.of(TimeUnit.SECONDS)));
    final var enums = Telescope.mapperForward(
      Enums.class,
      EnumValues.class,
      Mapping.<Enums, EnumValues>via(Enums::v, EnumValues::v, FromEnums.class)
    );
    assertEquals(new EnumValues(List.of(TimeUnit.SECONDS)), enums.forward(seconds));
    final var comparables = Telescope.mapperForward(
      Enums.class,
      Comparables.class,
      Mapping.<Enums, Comparables>via(Enums::v, Comparables::v, FromEnums.class)
    );
    assertEquals(new Comparables(List.of(TimeUnit.SECONDS)), comparables.forward(seconds));
    final var serializable = Telescope.mapperForward(
      Enums.class,
      SerializableValues.class,
      Mapping.<Enums, SerializableValues>via(Enums::v, SerializableValues::v, FromEnums.class)
    );
    assertEquals(new SerializableValues(List.of(TimeUnit.SECONDS)), serializable.forward(seconds));
    final var raw = Telescope.mapperForward(
      Enums.class,
      RawEnums.class,
      Mapping.<Enums, RawEnums>via(Enums::v, RawEnums::v, FromEnums.class)
    );
    assertEquals(new RawEnums(List.of(TimeUnit.SECONDS)), raw.forward(seconds));
    final var constables = Telescope.mapperForward(
      Enums.class,
      Constables.class,
      Mapping.<Enums, Constables>via(Enums::v, Constables::v, FromEnums.class)
    );
    assertEquals(new Constables(List.of(TimeUnit.SECONDS)), constables.forward(seconds));
    final var nodes = Telescope.mapperForward(
      Nodes.class,
      Comparables.class,
      Mapping.<Nodes, Comparables>via(Nodes::v, Comparables::v, FromNodes.class)
    );
    assertEquals(new Comparables(List.of("x")), nodes.forward(new Nodes(new NodeList<>(List.of("x")))));
    assertNoInstantiation(
      () ->
        Telescope.mapperForward(
          Enums.class,
          Integers.class,
          Mapping.<Enums, Integers>via(Enums::v, Integers::v, FromEnums.class)
        ),
      "java.util.List<T>",
      "java.util.List<java.lang.Integer>"
    );
  }

  /** A list class whose element variable is bounded by its other variable. */
  public static final class KvList<K, V extends K> extends ArrayList<V> {

    private static final long serialVersionUID = 1L;

    public KvList(final List<V> items) {
      super(items);
    }
  }

  public record KeyValues(KvList<String, ?> v) {}

  public static final class FromKeyValues {

    private FromKeyValues() {}

    public static <T> List<T> forward(final List<? extends T> a) {
      return List.copyOf(a);
    }

    public static KvList<?, ?> backward(final List<?> b) {
      return null;
    }
  }

  @Test
  @DisplayName(
    "a wildcard argument whose variable is bounded by another of its class's variables is bounded by the type the" +
      " argument gives that variable"
  )
  void aCaptureBoundedByAnotherVariableReadsTheArgumentsType() {
    final var values = new KeyValues(new KvList<String, String>(List.of("x")));
    final var strings = Telescope.mapperForward(
      KeyValues.class,
      Strings.class,
      Mapping.<KeyValues, Strings>via(KeyValues::v, Strings::v, FromKeyValues.class)
    );
    assertEquals(new Strings(List.of("x")), strings.forward(values));
    final var chars = Telescope.mapperForward(
      KeyValues.class,
      Chars.class,
      Mapping.<KeyValues, Chars>via(KeyValues::v, Chars::v, FromKeyValues.class)
    );
    assertEquals(new Chars(List.of("x")), chars.forward(values));
    assertNoInstantiation(
      () ->
        Telescope.mapperForward(
          KeyValues.class,
          Integers.class,
          Mapping.<KeyValues, Integers>via(KeyValues::v, Integers::v, FromKeyValues.class)
        ),
      "java.util.List<T>",
      "java.util.List<java.lang.Integer>"
    );
  }
}
