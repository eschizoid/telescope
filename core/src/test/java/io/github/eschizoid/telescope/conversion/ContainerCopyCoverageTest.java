package io.github.eschizoid.telescope.conversion;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.pairing.Allocation;
import io.github.eschizoid.telescope.internal.pairing.ContainerView;
import io.github.eschizoid.telescope.internal.pairing.PairingRules;
import io.github.eschizoid.telescope.internal.pairing.ReflectionProps;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A same-typed container whose own class has no copy is copied into the class the shared allocation
 * table rebuilds its declared type as, through that class's copy constructor. A table row naming a
 * class without one is not a compile error: the copy throws the first time a container reaches it.
 * So the table is walked here, and every class it builds must have a copy constructor in {@link
 * ContainerCopy}.
 */
class ContainerCopyCoverageTest {

  private static final PairingRules<Type> RULES = new PairingRules<>(new ReflectionProps());

  @Test
  @DisplayName("every class the shared allocation table builds has a copy constructor in ContainerCopy")
  void everyTableClassHasACopyConstructor() throws ReflectiveOperationException {
    final var field = ContainerCopy.class.getDeclaredField("COPY_CONSTRUCTORS");
    field.setAccessible(true);
    final var copies = (Map<?, ?>) field.get(null);

    final var missing = new ArrayList<String>();
    for (final var entry : PairingRules.declaredTypes().entrySet()) {
      check(copies, Class.forName(entry.getKey()), entry.getValue(), missing);
    }
    // Collection has no entry of its own; it is rebuilt as a list or a set, by the kind its pair
    // settled on, so it is walked under both.
    for (final var kind : List.of(ContainerView.Kind.LIST, ContainerView.Kind.SET)) {
      check(copies, Collection.class, kind, missing);
    }
    assertTrue(missing.isEmpty(), () -> "built by the table with no copy constructor: " + missing);
  }

  private static void check(
    final Map<?, ?> copies,
    final Class<?> declared,
    final ContainerView.Kind kind,
    final List<String> missing
  ) throws ClassNotFoundException {
    if (!(RULES.allocationFor(declared, kind) instanceof Allocation.Build build)) return;
    final var impl = Class.forName(build.implName());
    if (!copies.containsKey(impl)) missing.add(declared.getName() + " as " + kind + " -> " + impl.getName());
  }
}
