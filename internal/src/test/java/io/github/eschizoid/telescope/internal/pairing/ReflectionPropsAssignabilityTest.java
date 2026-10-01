package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ReflectionProps#isAssignable} against the answers javac's {@code Types#isAssignable} gives
 * for the same pairs, which is what {@code MirrorProps} delegates to. Each pair is read off a field
 * of {@link Shapes}, so the types are the ones reflection hands the runtime.
 */
class ReflectionPropsAssignabilityTest {

  @SuppressWarnings({ "rawtypes", "unused" })
  static class Shapes<I extends Object & Comparable<I>, E extends Comparable<E>> {

    ArrayList rawArrayList;
    List rawList;
    Collection<String> collectionOfString;
    List<String> listOfString;
    List<Integer> listOfInteger;
    List<String>[] arrayOfListOfString;
    List<?>[] arrayOfListOfAny;
    String[] arrayOfString;
    Object[] arrayOfObject;
    int[] arrayOfInt;
    long[] arrayOfLong;
    I intersection;
    E recursive;
    Comparable<I> comparableOfI;
    Comparable<?> comparableOfAny;
    Comparable<E> comparableOfE;
    int primitiveInt;
    Integer boxedInt;
    Long boxedLong;
    List<? extends CharSequence> listOfCharSequences;
    List<? super String> listOfStringSupers;
    String string;
    CharSequence charSequence;
    Object object;
  }

  private static Type field(final String name) throws NoSuchFieldException {
    return Shapes.class.getDeclaredField(name).getGenericType();
  }

  private record Pair(String from, String to, boolean javac) {}

  private static final List<Pair> PAIRS = List.of(
    new Pair("rawArrayList", "collectionOfString", true),
    new Pair("rawList", "listOfString", true),
    new Pair("listOfString", "collectionOfString", true),
    new Pair("listOfInteger", "collectionOfString", false),
    new Pair("arrayOfListOfString", "arrayOfListOfAny", true),
    new Pair("arrayOfListOfAny", "arrayOfListOfString", false),
    new Pair("arrayOfString", "arrayOfObject", true),
    new Pair("arrayOfInt", "arrayOfLong", false),
    new Pair("arrayOfInt", "arrayOfObject", false),
    new Pair("intersection", "comparableOfI", true),
    new Pair("intersection", "comparableOfAny", true),
    new Pair("recursive", "comparableOfE", true),
    new Pair("primitiveInt", "boxedInt", true),
    new Pair("boxedInt", "primitiveInt", true),
    new Pair("primitiveInt", "boxedLong", false),
    new Pair("listOfString", "listOfCharSequences", true),
    new Pair("listOfCharSequences", "listOfString", false),
    new Pair("listOfString", "listOfStringSupers", true),
    new Pair("listOfInteger", "listOfStringSupers", false),
    new Pair("string", "charSequence", true),
    new Pair("charSequence", "string", false),
    new Pair("primitiveInt", "object", true)
  );

  @Test
  @DisplayName("isAssignable answers each pair the way javac does")
  void answersAsJavacDoes() throws NoSuchFieldException {
    final var props = new ReflectionProps();
    final var wrong = new ArrayList<String>();
    for (final var pair : PAIRS) {
      final var got = props.isAssignable(field(pair.from()), field(pair.to()));
      if (got != pair.javac()) wrong.add(
        pair.from() + " -> " + pair.to() + ": " + got + ", javac says " + pair.javac()
      );
    }
    assertTrue(wrong.isEmpty(), () -> String.join("\n", wrong));
  }
}
