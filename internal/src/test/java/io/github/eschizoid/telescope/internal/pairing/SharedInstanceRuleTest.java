package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link PairingRules#mayShareInstance} and {@link PairingRules#viaParameterNeverFitting}, the
 * decision a {@code via} row naming a class is refused by on every path, through the reflection
 * world's {@link ReflectionProps}.
 */
class SharedInstanceRuleTest {

  private static final PairingRules<Type> RULES = new PairingRules<>(new ReflectionProps());

  interface Named {}

  sealed interface Shape permits Circle, Square {}

  record Circle() implements Shape {}

  record Square() implements Shape, Named {}

  static class Open {}

  static final class Closed {}

  enum Plain {
    A,
  }

  enum Bodied {
    A {
      @Override
      public String toString() {
        return "a";
      }
    },
  }

  @Test
  @DisplayName("a class and its supertype share an instance either way round")
  void aSubtypeSharesAnInstanceWithItsSupertype() {
    assertTrue(RULES.mayShareInstance(ArrayList.class, List.class));
    assertTrue(RULES.mayShareInstance(List.class, ArrayList.class));
  }

  @Test
  @DisplayName("two unrelated classes share no instance")
  void unrelatedClassesShareNoInstance() {
    assertFalse(RULES.mayShareInstance(String.class, Integer.class));
  }

  @Test
  @DisplayName(
    "an interface shares an instance with a class a subclass of which can implement it, and with no final one"
  )
  void anInterfaceSharesAnInstanceOnlyWithAnExtendableClass() {
    assertTrue(RULES.mayShareInstance(Named.class, Open.class));
    assertTrue(RULES.mayShareInstance(Open.class, Named.class));
    assertTrue(RULES.mayShareInstance(Named.class, Set.class));
    assertFalse(RULES.mayShareInstance(Named.class, Closed.class));
    assertFalse(RULES.mayShareInstance(Named.class, String.class));
    assertFalse(RULES.mayShareInstance(Named.class, Plain.class));
    assertFalse(RULES.mayShareInstance(Named.class, String[].class));
    assertFalse(RULES.mayShareInstance(List.class, String.class));
  }

  @Test
  @DisplayName("a sealed type shares an instance through one of its cases, and with nothing none of them can be")
  void aSealedTypeSharesAnInstanceThroughItsCases() {
    assertTrue(RULES.mayShareInstance(Shape.class, Named.class));
    assertTrue(RULES.mayShareInstance(Named.class, Shape.class));
    assertFalse(RULES.mayShareInstance(Shape.class, Open.class));
    assertFalse(RULES.mayShareInstance(Bodied.class, Named.class));
  }

  @Test
  @DisplayName("a primitive is read as its wrapper")
  void aPrimitiveIsReadAsItsWrapper() {
    assertTrue(RULES.mayShareInstance(int.class, Integer.class));
    assertTrue(RULES.mayShareInstance(int.class, Number.class));
    assertFalse(RULES.mayShareInstance(int.class, Long.class));
  }

  @SuppressWarnings("unused")
  private static void takes(
    final Comparable<Integer> integers,
    final Comparable<String> strings,
    final Comparable<? super String> superStrings,
    final List<Integer> integerList,
    final Comparable<?> any
  ) {}

  /** The declared type of {@link #takes}'s parameter at {@code index}. */
  private static Type taken(final int index) throws NoSuchMethodException {
    return SharedInstanceRuleTest.class.getDeclaredMethod(
      "takes",
      Comparable.class,
      Comparable.class,
      Comparable.class,
      List.class,
      Comparable.class
    ).getGenericParameterTypes()[index];
  }

  @Test
  @DisplayName("a class that gives a supertype other type arguments is never an instance of it with these")
  void aClassGivingOtherArgumentsNeverFits() throws NoSuchMethodException {
    assertTrue(RULES.argumentsNeverFit(String.class, taken(0)));
    assertTrue(RULES.argumentsNeverFit(int.class, taken(1)));
    assertEquals(taken(0), RULES.viaParameterNeverFitting(String.class, List.of(taken(0))));
  }

  @Test
  @DisplayName("arguments the parameter contains, an unbounded wildcard, or a class giving none ask nothing")
  void argumentsThatFitOrAreUnknownDoNotRefuse() throws NoSuchMethodException {
    assertFalse(RULES.argumentsNeverFit(String.class, taken(1)));
    assertFalse(RULES.argumentsNeverFit(String.class, taken(2)));
    assertFalse(RULES.argumentsNeverFit(String.class, taken(4)));
    assertFalse(RULES.argumentsNeverFit(Object.class, taken(0)));
    assertFalse(RULES.argumentsNeverFit(ArrayList.class, taken(3)));
    assertNull(RULES.viaParameterNeverFitting(String.class, List.of(taken(0), taken(1))));
  }

  @Test
  @DisplayName("a refusal names a parameter of another class by its simple name, and one of other arguments in full")
  void theRefusalNamesTheReason() throws NoSuchMethodException {
    assertEquals(
      PairingMessages.viaMapperNeverFits("S", "T", "source", "a", "Integer"),
      RULES.viaNeverFitsRefusal("S", "T", "source", "a", String.class, List.of(Integer.class), true, t -> "Integer")
    );
    assertEquals(
      PairingMessages.viaMapperArgumentsNeverFit(
        "S",
        "T",
        "source",
        "a",
        "java.lang.String",
        "java.lang.Comparable<java.lang.Integer>"
      ),
      RULES.viaNeverFitsRefusal("S", "T", "source", "a", String.class, List.of(taken(0)), true, t -> "Comparable")
    );
    assertNull(RULES.viaNeverFitsRefusal("S", "T", "source", "a", String.class, List.of(taken(1)), true, t -> "x"));
    assertNull(RULES.viaNeverFitsRefusal("S", "T", "target", "a", String.class, List.of(taken(0)), false, t -> "x"));
  }

  @Test
  @DisplayName("a property no parameter can take is refused by the first parameter")
  void aPropertyNoParameterTakesIsRefusedByTheFirst() {
    assertEquals(String.class, RULES.viaParameterNeverFitting(List.class, List.of(String.class, Closed.class)));
  }

  @Test
  @DisplayName("a property one parameter can take, or no parameter at all, is not refused")
  void aPropertyOneParameterTakesIsNotRefused() {
    assertNull(RULES.viaParameterNeverFitting(List.class, List.of(String.class, Named.class)));
    assertNull(RULES.viaParameterNeverFitting(List.class, List.of()));
  }
}
