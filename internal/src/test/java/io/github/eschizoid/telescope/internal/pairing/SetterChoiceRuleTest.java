package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rule every path applies to a bean property's setter: how its read type reaches the setter's
 * write type, and which of several methods named after the setter writes it.
 */
class SetterChoiceRuleTest {

  private static final PairingRules<Type> RULES = new PairingRules<>(new ReflectionProps());

  @Test
  @DisplayName("each read and write type pair has the fit Java's conversions give it")
  void eachPairHasTheFitJavaGivesIt() {
    final var cases = List.<Object[]>of(
      new Object[] { int.class, int.class, SetterFit.SAME },
      new Object[] { Integer.class, int.class, SetterFit.SAME },
      new Object[] { int.class, Integer.class, SetterFit.SAME },
      new Object[] { int.class, long.class, SetterFit.CONVERTS },
      new Object[] { Integer.class, long.class, SetterFit.CONVERTS },
      new Object[] { char.class, int.class, SetterFit.CONVERTS },
      new Object[] { int.class, Number.class, SetterFit.CONVERTS },
      new Object[] { Integer.class, Number.class, SetterFit.CONVERTS },
      new Object[] { List.class, Collection.class, SetterFit.CONVERTS },
      new Object[] { Number.class, Integer.class, SetterFit.NARROWS },
      new Object[] { Number.class, int.class, SetterFit.NARROWS },
      new Object[] { Collection.class, ArrayList.class, SetterFit.NARROWS },
      new Object[] { long.class, int.class, SetterFit.DISJOINT },
      new Object[] { int.class, Long.class, SetterFit.DISJOINT },
      new Object[] { int.class, String.class, SetterFit.DISJOINT },
      new Object[] { String.class, int.class, SetterFit.DISJOINT },
      new Object[] { String.class, Date.class, SetterFit.DISJOINT }
    );
    final var failures = new ArrayList<String>();
    for (final var c : cases) {
      final var fit = RULES.setterFit((Type) c[0], (Type) c[1]);
      if (fit != c[2]) failures.add(c[0] + " -> " + c[1] + ": " + fit + ", owed " + c[2]);
    }
    assertEquals(List.of(), failures, () -> String.join("\n", failures));
  }

  @Test
  @DisplayName("the setter chosen among overloads is the one Java binds, whatever order they come in")
  void theChoiceIsJavasBindingInAnyOrder() {
    final var cases = List.<Object[]>of(
      new Object[] { int.class, List.of(String.class, int.class), int.class },
      new Object[] { int.class, List.of(double.class, long.class), long.class },
      new Object[] { int.class, List.of(Integer.class, long.class), long.class },
      new Object[] { Integer.class, List.of(long.class, int.class), int.class },
      new Object[] { int.class, List.of(Object.class, Number.class), Number.class },
      new Object[] { int.class, List.of(String.class, Date.class), null },
      new Object[] { int.class, List.of(String.class), String.class }
    );
    final var failures = new ArrayList<String>();
    for (final var c : cases) {
      @SuppressWarnings("unchecked")
      final var parameters = (List<Type>) c[1];
      for (final var order : List.of(parameters, parameters.reversed())) {
        final var index = RULES.chooseSetter((Type) c[0], order);
        final var chosen = index < 0 ? null : order.get(index);
        if (chosen != c[2]) failures.add(c[0] + " over " + order + ": " + chosen + ", owed " + c[2]);
      }
    }
    assertEquals(List.of(), failures, () -> String.join("\n", failures));
  }
}
