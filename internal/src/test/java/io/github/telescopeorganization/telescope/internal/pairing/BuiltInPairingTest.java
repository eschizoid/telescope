package io.github.telescopeorganization.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Which pairs of scalars the shared spec converts without a row, in which directions, and how it
 * words the refusals and the value failures both paths give.
 */
class BuiltInPairingTest {

  private final PairingRules<Type> rules = new PairingRules<>(new ReflectionProps());

  /** An enum nested in a class, which a message names by its enclosing class and its own name. */
  enum Status {
    OPEN,
  }

  /** A subclass of an arbitrary-precision class, which is no scalar. */
  static final class Money extends BigDecimal {

    private static final long serialVersionUID = 1L;

    Money() {
      super(0);
    }
  }

  private PairDecision.BuiltIn<?> builtIn(final Type source, final Type target, final boolean forwardOnly) {
    return assertInstanceOf(PairDecision.BuiltIn.class, rules.decidePair(source, target, "p", forwardOnly));
  }

  private String refusal(final Type source, final Type target, final boolean forwardOnly) {
    return assertInstanceOf(
      PairDecision.Incompatible.class,
      rules.decidePair(source, target, "p", forwardOnly)
    ).message();
  }

  @Nested
  @DisplayName("scalarOf")
  class ScalarOf {

    @Test
    @DisplayName("names a primitive and its wrapper alike, the two arbitrary-precision classes, String and any enum")
    void namesEachScalar() {
      assertEquals(Scalar.INT, rules.scalarOf(int.class));
      assertEquals(Scalar.INT, rules.scalarOf(Integer.class));
      assertEquals(Scalar.CHAR, rules.scalarOf(Character.class));
      assertEquals(Scalar.BOOLEAN, rules.scalarOf(boolean.class));
      assertEquals(Scalar.BIG_INTEGER, rules.scalarOf(BigInteger.class));
      assertEquals(Scalar.BIG_DECIMAL, rules.scalarOf(BigDecimal.class));
      assertEquals(Scalar.STRING, rules.scalarOf(String.class));
      assertEquals(Scalar.ENUM, rules.scalarOf(DayOfWeek.class));
    }

    @Test
    @DisplayName("names no scalar for a subclass of one, any other class, or a parameterized type")
    void namesNoOther() {
      assertNull(rules.scalarOf(Money.class));
      assertNull(rules.scalarOf(Number.class));
      assertNull(rules.scalarOf(UUID.class));
      assertNull(rules.scalarOf(void.class));
    }
  }

  @Nested
  @DisplayName("text")
  class Text {

    @Test
    @DisplayName("a String converts to and from every number but char, an enum and a boolean, both ways")
    void stringConvertsBothWays() {
      for (final var other : List.<Type>of(
        byte.class,
        Short.class,
        int.class,
        Long.class,
        float.class,
        Double.class,
        BigInteger.class,
        BigDecimal.class,
        Status.class,
        boolean.class
      )) {
        final var forward = builtIn(String.class, other, false);
        assertEquals(Scalar.STRING, forward.source());
        assertEquals(rules.scalarOf(other), forward.target());
        final var backward = builtIn(other, String.class, false);
        assertEquals(rules.scalarOf(other), backward.source());
        assertEquals(Scalar.STRING, backward.target());
      }
    }

    @Test
    @DisplayName("a String and a char are left to the shape refusal")
    void charIsNotText() {
      assertEquals(
        PairingMessages.incompatibleShapes("p", "java.lang.String", "char"),
        refusal(String.class, char.class, true)
      );
    }

    @Test
    @DisplayName("a String and a date or time are refused either way round, naming the rows that take a format")
    void datesNeedAFormat() {
      assertEquals(
        PairingMessages.textNeedsFormat("p", "java.lang.String", "java.time.Instant"),
        refusal(String.class, Instant.class, true)
      );
      assertEquals(
        PairingMessages.textNeedsFormat("p", "java.time.LocalDate", "java.lang.String"),
        refusal(LocalDate.class, String.class, false)
      );
      assertTrue(refusal(String.class, Instant.class, false).contains("to(source, target, forward, backward)"));
    }

    @Test
    @DisplayName("a String and any other class are left to the shape refusal")
    void otherClassesAreShapes() {
      assertEquals(
        PairingMessages.incompatibleShapes("p", "java.lang.String", "java.util.UUID"),
        refusal(String.class, UUID.class, true)
      );
    }
  }

  @Nested
  @DisplayName("numbers")
  class Numbers {

    @Test
    @DisplayName("a number widens forward only, and a two-way mapper is refused naming both remedies")
    void wideningIsForwardOnly() {
      for (final var pair : List.<Type[]>of(
        new Type[] { byte.class, short.class },
        new Type[] { short.class, Integer.class },
        new Type[] { char.class, int.class },
        new Type[] { Integer.class, long.class },
        new Type[] { int.class, float.class },
        new Type[] { long.class, double.class },
        new Type[] { float.class, Double.class },
        new Type[] { long.class, BigInteger.class },
        new Type[] { int.class, BigDecimal.class },
        new Type[] { BigInteger.class, BigDecimal.class }
      )) {
        builtIn(pair[0], pair[1], true);
        final var refused = refusal(pair[0], pair[1], false);
        assertTrue(refused.contains("which converts forward only"), refused);
        assertTrue(refused.contains("Telescope.mapperForward"), refused);
      }
    }

    @Test
    @DisplayName("a number that would narrow is refused in either kind of mapper")
    void narrowingIsRefused() {
      for (final var pair : List.<Type[]>of(
        new Type[] { long.class, int.class },
        new Type[] { double.class, float.class },
        new Type[] { double.class, BigDecimal.class },
        new Type[] { char.class, short.class },
        new Type[] { short.class, char.class },
        new Type[] { char.class, BigInteger.class },
        new Type[] { BigDecimal.class, BigInteger.class },
        new Type[] { BigInteger.class, long.class }
      )) {
        for (final var forwardOnly : List.of(true, false)) {
          assertEquals(
            PairingMessages.narrowing("p", pair[0].getTypeName(), pair[1].getTypeName()),
            refusal(pair[0], pair[1], forwardOnly)
          );
        }
      }
    }

    @Test
    @DisplayName("a boolean and a number, or an enum and a number, are left to the shape refusal")
    void otherScalarsAreShapes() {
      assertEquals(PairingMessages.incompatibleShapes("p", "boolean", "int"), refusal(boolean.class, int.class, true));
      assertEquals(
        PairingMessages.incompatibleShapes("p", Status.class.getTypeName(), "java.lang.Integer"),
        refusal(Status.class, Integer.class, true)
      );
    }

    @Test
    @DisplayName("Java's widening primitive conversions are exactly the primitive part of the table")
    void primitiveWideningIsJavas() {
      assertTrue(Scalar.BYTE.widensTo(Scalar.DOUBLE));
      assertTrue(Scalar.CHAR.widensTo(Scalar.INT));
      assertFalse(Scalar.CHAR.widensTo(Scalar.SHORT));
      assertFalse(Scalar.BYTE.widensTo(Scalar.CHAR));
      assertFalse(Scalar.DOUBLE.widensTo(Scalar.FLOAT));
      assertFalse(Scalar.BOOLEAN.widens());
      assertFalse(Scalar.STRING.widens());
      assertFalse(Scalar.INT.widensTo(null));
    }
  }

  @Nested
  @DisplayName("value failures")
  class ValueFailures {

    @Test
    @DisplayName("name the property, the value, and what the value had to be")
    void nameThePropertyAndValue() {
      assertEquals("'total' got \"12x\", not a BigDecimal", PairingMessages.notAValue("total", "12x", "a BigDecimal"));
    }

    @Test
    @DisplayName("name an element by the property that holds it")
    void nameAnElementByItsProperty() {
      assertEquals("'tags' got \"x\", not a Long", PairingMessages.notAValue("tags[*][*]", "x", "a Long"));
    }

    @Test
    @DisplayName("are the head, the value and the tail, which generated code writes around the value")
    void areHeadValueTail() {
      assertEquals(
        PairingMessages.notAValue("n", "v", "an int"),
        PairingMessages.notAValueHead("n") + "v" + PairingMessages.notAValueTail("an int")
      );
    }

    @Test
    @DisplayName("phrase a primitive, a wrapper, a class and an enum, each with its article")
    void phraseEachScalar() {
      assertEquals("an int", PairingMessages.scalarPhrase(Scalar.INT, true, null));
      assertEquals("an Integer", PairingMessages.scalarPhrase(Scalar.INT, false, null));
      assertEquals("a long", PairingMessages.scalarPhrase(Scalar.LONG, true, null));
      assertEquals("a BigDecimal", PairingMessages.scalarPhrase(Scalar.BIG_DECIMAL, false, null));
      assertEquals("a boolean", PairingMessages.scalarPhrase(Scalar.BOOLEAN, true, null));
      assertEquals("a constant of Outer.Status", PairingMessages.scalarPhrase(Scalar.ENUM, false, "Outer.Status"));
    }

    @Test
    @DisplayName("name an enum by its name in source without its package")
    void nameAnEnumWithoutItsPackage() {
      assertEquals("BuiltInPairingTest.Status", rules.nameInMessage(Status.class));
      assertEquals("DayOfWeek", rules.nameInMessage(DayOfWeek.class));
    }
  }
}
