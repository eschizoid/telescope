package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every name the allocation table answers for is a name the classifier gives a view of, in the same
 * family.
 *
 * <p>The two sides can disagree in a way neither notices. A row decides what to build for a
 * declared name; a view decides whether a declared type is a container at all, and of which kind. A
 * name the table answers for and the classifier declines is a decision nothing can reach — it reads
 * as supported, its own gates pass, and the shape it names is refused at the moment a caller tries
 * it.
 *
 * <p>The rows come from the table itself rather than from a list beside it, so a row added without
 * a classifier that reaches it fails here rather than waiting for someone to try that shape.
 *
 * <p>This checks one direction only. A family the classifier reaches and the table has no row for
 * is not seen here, because the walk starts from the table's rows and a missing row is not one of
 * them. Such a family falls through to the runtime's fallbacks.
 */
class AllocationReachabilityTest {

  private final PairingRules<Type> rules = new PairingRules<>(new ReflectionProps());

  @Test
  @DisplayName("every declared name the table answers for is classified, in the family it answers for")
  void everyDeclaredNameIsReachable() {
    final var unreachable = new ArrayList<String>();
    final var misfamilied = new ArrayList<String>();

    PairingRules.declaredTypes().forEach((name, family) -> {
      final var view = rules.containerViewOf(parameterizedFor(name));
      if (view == null) {
        unreachable.add(name + " answers for " + family + ", and the classifier gives it no view");
        return;
      }
      if (view.kind() != family) {
        misfamilied.add(name + " answers for " + family + ", and the classifier calls it " + view.kind());
      }
    });

    assertTrue(
      unreachable.isEmpty(),
      () -> "the table decides an allocation nothing routes to:\n  " + String.join("\n  ", unreachable)
    );
    assertTrue(
      misfamilied.isEmpty(),
      () -> "the table and the classifier disagree about the family:\n  " + String.join("\n  ", misfamilied)
    );
  }

  /**
   * The declared type a field of this container would have, with its arguments filled by a plain
   * class so nothing but the container's own shape is under test.
   *
   * <p>The arity is the class's own, not the family's. A non-generic class is returned as itself,
   * because a real field of that type is a plain class with no arguments, and the classifier gives
   * such a field no view. Inventing arguments for it instead would let reflection resolve them
   * through the supertypes, and a row nothing can reach would pass.
   */
  private static Type parameterizedFor(final String name) {
    final Class<?> raw;
    try {
      raw = Class.forName(name);
    } catch (final ClassNotFoundException e) {
      throw new IllegalStateException("the table names a class that does not exist: " + name, e);
    }
    final var arity = raw.getTypeParameters().length;
    if (arity == 0) return raw;
    final var arguments = new Type[arity];
    Arrays.fill(arguments, String.class);
    return new ParameterizedType() {
      @Override
      public Type[] getActualTypeArguments() {
        return arguments.clone();
      }

      @Override
      public Type getRawType() {
        return raw;
      }

      @Override
      public Type getOwnerType() {
        return null;
      }

      @Override
      public String toString() {
        return name + List.of(arguments);
      }
    };
  }
}
