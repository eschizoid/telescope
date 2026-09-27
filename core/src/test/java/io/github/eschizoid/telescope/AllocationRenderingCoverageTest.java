package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.eschizoid.telescope.internal.pairing.Allocation;
import io.github.eschizoid.telescope.internal.pairing.ContainerView;
import io.github.eschizoid.telescope.internal.pairing.PairingRules;
import io.github.eschizoid.telescope.internal.pairing.ReflectionProps;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
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
      "specAllocatorFor",
      Class.class,
      ContainerView.Kind.class
    );
    render.setAccessible(true);

    final var source = Map.of("kind", "value");
    final var listSource = List.of("a", "b");

    for (final var entry : PairingRules.declaredTypes().entrySet()) {
      final var declared = Class.forName(entry.getKey());
      final var kind = entry.getValue();
      final var decision = RULES.allocationFor(declared, kind);

      if (decision instanceof Allocation.Refuse) continue;
      final var build = (Allocation.Build) decision;

      @SuppressWarnings("unchecked")
      final var rendered = (Function<Object, Object>) render.invoke(null, declared, kind);
      assertNotNull(rendered, () -> entry.getKey() + " is decided but nothing renders it");

      final var made = rendered.apply(kind == ContainerView.Kind.MAP_VALUES ? source : listSource);
      assertEquals(
        build.implName(),
        made.getClass().getName(),
        () -> entry.getKey() + " is decided as " + build.implName() + " and rendered as something else"
      );
    }
  }
}
