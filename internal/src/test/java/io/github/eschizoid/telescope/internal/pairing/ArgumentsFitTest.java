package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link PairingRules#argumentsFit}, on the reflection world: whether a value of one type, once a
 * check of its class has passed, can be shown to have the type arguments a member's parameter
 * fixes. Each case relates the two types a different way.
 */
@SuppressWarnings("serial")
class ArgumentsFitTest {

  private final PairingRules<Type> rules = new PairingRules<>(new ReflectionProps());

  /** A map whose variables it passes to its superclass the other way round. */
  static class Flip<A, B> extends HashMap<B, A> {}

  /** A list class declaring no type parameters, fixing its superclass's to String. */
  static class StrList extends ArrayList<String> {}

  /** A list class nested inside a generic class, extending a list of the outer variable. */
  static class Outer<T> {

    class In extends ArrayList<T> {}
  }

  /** The same, two levels deep. */
  static class Outer2<T> {

    class Mid {

      class In extends ArrayList<T> {}
    }
  }

  /** Static nested classes, which no enclosing type gives arguments to. */
  static class Statics {

    static class Nested extends ArrayList<String> {}

    static class GenNested<E> extends ArrayList<E> {}
  }

  @SuppressWarnings({ "rawtypes", "unused" })
  static final class Types {

    List<String> strings;
    List<Object> objects;
    List<Integer> integers;
    ArrayList<String> arrayStrings;
    Flip<Integer, String> flip;
    Map<String, Integer> stringToInteger;
    Map<Integer, String> integerToString;
    Collection<String> stringCollection;
    Collection<Integer> integerCollection;
    StrList strList;
    List rawList;
    Set<String> stringSet;
    Object object;
    Outer<String>.In stringIn;
    Outer<Integer>.In integerIn;
    Outer2<String>.Mid.In stringIn2;
    Outer2<Integer>.Mid.In integerIn2;
    Statics.Nested nested;
    Statics.GenNested<String> stringGenNested;
    Statics.GenNested<Integer> integerGenNested;
    List<? extends CharSequence> charSequences;
    List<? super String> superString;
    List<?> anything;
    List<? extends String> extendsString;
  }

  private static Type type(final String field) {
    try {
      return Types.class.getDeclaredField(field).getGenericType();
    } catch (final NoSuchFieldException e) {
      throw new IllegalStateException(e);
    }
  }

  private boolean fits(final String sending, final String receiving) {
    return rules.argumentsFit(type(sending), type(receiving));
  }

  @Nested
  @DisplayName("relating the two classes")
  class Relations {

    @Test
    @DisplayName("a result of the parameter's class compares its arguments directly")
    void theSameClass() {
      assertTrue(fits("strings", "strings"));
      assertFalse(fits("objects", "strings"));
    }

    @Test
    @DisplayName("a result of a subclass is seen as the parameter's class, its variables in their own places")
    void aSubclassIsSeenAsTheParametersClass() {
      assertTrue(fits("arrayStrings", "strings"));
      assertTrue(fits("flip", "stringToInteger"));
      assertFalse(fits("flip", "integerToString"));
    }

    @Test
    @DisplayName("a result of a superclass is held to the parameter seen as that class")
    void aSuperclassHoldsTheParameterSeenAsIt() {
      assertTrue(fits("stringCollection", "strings"));
      assertFalse(fits("integerCollection", "strings"));
    }

    @Test
    @DisplayName("a class declaring no type parameters gives the arguments it fixes on its supertypes")
    void aNonGenericSubclassGivesWhatItFixes() {
      assertTrue(fits("strList", "strings"));
      assertFalse(fits("strList", "integers"));
    }

    @Test
    @DisplayName("a generic class used raw gives no arguments, and cannot be shown to fit")
    void aRawGenericDoesNotFit() {
      assertFalse(fits("rawList", "strings"));
    }

    @Test
    @DisplayName("a class neither extends, Object included, cannot be shown to fit")
    void anUnrelatedClassDoesNotFit() {
      assertFalse(fits("stringSet", "strings"));
      assertFalse(fits("object", "strings"));
    }
  }

  @Nested
  @DisplayName("nested classes")
  class NestedClasses {

    @Test
    @DisplayName("an inner class reads its outer class's variable as the argument its enclosing type gives it")
    void anInnerClassOfAGenericOuter() {
      assertTrue(fits("stringIn", "strings"));
      assertFalse(fits("integerIn", "strings"));
    }

    @Test
    @DisplayName("so does one nested two levels deep")
    void anInnerClassTwoLevelsDeep() {
      assertTrue(fits("stringIn2", "strings"));
      assertFalse(fits("integerIn2", "strings"));
    }

    @Test
    @DisplayName("a static nested class has no enclosing arguments, and reads only its own")
    void aStaticNestedClass() {
      assertTrue(fits("nested", "strings"));
      assertTrue(fits("stringGenNested", "strings"));
      assertFalse(fits("integerGenNested", "strings"));
    }
  }

  @Nested
  @DisplayName("wildcards")
  class Wildcards {

    @Test
    @DisplayName("an unbounded wildcard asks nothing of the result's arguments")
    void anUnboundedWildcardAsksNothing() {
      assertTrue(fits("rawList", "anything"));
      assertTrue(fits("object", "anything"));
    }

    @Test
    @DisplayName("? extends B contains an argument whose values all fit B")
    void anUpperBoundContainsWhatFitsIt() {
      assertTrue(fits("strings", "charSequences"));
      assertTrue(fits("extendsString", "charSequences"));
      assertFalse(fits("integers", "charSequences"));
    }

    @Test
    @DisplayName("? super B contains an argument every B is a value of")
    void aLowerBoundContainsWhatEveryBIs() {
      assertTrue(fits("objects", "superString"));
      assertTrue(fits("strings", "superString"));
      assertFalse(fits("integers", "superString"));
      assertFalse(fits("extendsString", "superString"));
    }
  }
}
