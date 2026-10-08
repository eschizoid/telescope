package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.eschizoid.telescope.internal.pairing.Allocation;
import io.github.eschizoid.telescope.internal.pairing.ContainerView;
import io.github.eschizoid.telescope.internal.pairing.PairingRules;
import io.github.eschizoid.telescope.internal.pairing.ReflectionProps;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The decision and the rendering are separate on purpose, which means they can part company. An
 * entry nothing renders is not a compile error: the container is quietly built some other way, at a
 * different size, and every assertion about its contents still holds.
 *
 * <p>So the table is walked rather than restated. A row added without a rendering, or a rendering
 * whose name no longer matches its row, fails here instead of becoming a silent allocation change.
 */
class AllocationRenderingCoverageTest {

  private static final PairingRules<Type> RULES = new PairingRules<>(new ReflectionProps());

  @Test
  @DisplayName("every declared type the shared table answers for is rendered by this module")
  void everyTableEntryIsRendered() throws Exception {
    final Method render = ContainerLifts.class.getDeclaredMethod(
      "rendered",
      Allocation.Build.class,
      ContainerView.Kind.class,
      Class.class
    );
    render.setAccessible(true);

    for (final var entry : PairingRules.declaredTypes().entrySet()) {
      assertRendered(render, Class.forName(entry.getKey()), entry.getValue());
    }
    // Collection has no entry of its own: what it is rebuilt as depends on the kind
    // its pair settled on. The spec settles it to a list or a set and nothing else,
    // so it is walked under both. Left out, a rendering broken for either would fall
    // through to the family default and build the same class, which no assertion
    // about the result would notice.
    for (final var kind : List.of(ContainerView.Kind.LIST, ContainerView.Kind.SET)) {
      assertRendered(render, Collection.class, kind);
    }
  }

  private static void assertRendered(final Method render, final Class<?> declared, final ContainerView.Kind kind)
    throws ReflectiveOperationException {
    final var decision = RULES.allocationFor(declared, kind);
    if (decision instanceof Allocation.Refuse) return;
    final var build = (Allocation.Build) decision;
    final var label = declared.getName() + " as " + kind;

    // A container built from its key class is handed one, as the declaration naming it would be.
    final Class<?> keyClass = build.call() == Allocation.Call.KEY_CLASS ? ContainerView.Kind.class : null;
    @SuppressWarnings("unchecked")
    final var rendered = (Function<Object, Object>) render.invoke(null, build, kind, keyClass);
    assertNotNull(rendered, () -> label + " is decided but nothing renders it");

    final Object source = kind == ContainerView.Kind.MAP_VALUES ? Map.of("kind", "value") : List.of("a", "b");
    final var made = rendered.apply(source);
    assertEquals(
      build.implName(),
      made.getClass().getName(),
      () -> label + " is decided as " + build.implName() + " and rendered as something else"
    );
  }
}
