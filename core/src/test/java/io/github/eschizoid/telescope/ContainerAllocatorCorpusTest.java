package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.optics.Iso;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The generated path and the reflective path have to accept the same programs, because swapping one
 * for the other is the whole point of having both. They decide what to allocate for a container
 * field separately, and a type one of them handles and the other does not is a bean that compiles
 * under {@code @Bridge} and throws under {@code mapper(...)}.
 *
 * <p>A test that names the container types it checks can only cover the ones somebody thought of.
 * This enumerates them from {@code java.base} itself, so a type nobody remembered is still checked,
 * and restates the generated path's rule — allocate the declared class where it can be
 * instantiated, otherwise the family default — as the thing the reflective path has to agree with.
 *
 * <p>Restating that rule here rather than importing it is the cost of this shape: the two could
 * drift. It is two lines guarding thirty-odd types, which is a trade worth making until the rule
 * has one home.
 */
class ContainerAllocatorCorpusTest {

  /** A container the reflective path is expected to handle, and the lift that would handle it. */
  private record Family(
    String label,
    Class<?> iface,
    Class<?> fallback,
    BiFunction<Class<?>, Class<?>, Iso<?, ?>> lift
  ) {}

  private static final List<Family> FAMILIES = List.of(
    new Family("List", List.class, ArrayList.class, (s, t) ->
      ContainerLifts.liftListIntoTargetRaw(Iso.identity(), s, t)
    ),
    new Family("Set", Set.class, LinkedHashSet.class, (s, t) ->
      ContainerLifts.liftSetIntoTargetRaw(Iso.identity(), s, t)
    ),
    new Family("Map", Map.class, LinkedHashMap.class, (s, t) ->
      ContainerLifts.liftMapIntoTargetRaw(Iso.identity(), s, t)
    )
  );

  /** Every public container class {@code java.base} ships, read from the running image. */
  private static List<Class<?>> javaBaseContainers() throws IOException {
    // The running image is its own catalogue. FileSystems.getFileSystem returns the shared jrt
    // instance rather than opening one, so it is not this method's to close.
    final var jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
    final var roots = List.of(
      jrt.getPath("/modules/java.base/java/util"),
      jrt.getPath("/modules/java.base/java/util/concurrent")
    );
    final var out = new ArrayList<Class<?>>();
    for (final var root : roots) {
      if (!Files.isDirectory(root)) continue;
      try (Stream<Path> files = Files.list(root)) {
        files
          .map(p -> p.getFileName().toString())
          .filter(n -> n.endsWith(".class") && !n.contains("$"))
          .map(
            n ->
              (root.toString().contains("concurrent") ? "java.util.concurrent." : "java.util.") +
              n.substring(0, n.length() - ".class".length())
          )
          .forEach(fqn -> {
            try {
              final var c = Class.forName(fqn);
              if (isContainer(c)) out.add(c);
            } catch (final Throwable ignored) {
              // A class the current image does not expose is simply not part of the corpus.
            }
          });
      }
    }
    return out;
  }

  private static boolean isContainer(final Class<?> c) {
    return (
      Modifier.isPublic(c.getModifiers()) && (Collection.class.isAssignableFrom(c) || Map.class.isAssignableFrom(c))
    );
  }

  /**
   * The implementation the generated path picks for an uninstantiable declared type. Three families
   * name a contract no hash container keeps, so each takes the implementation that keeps it; every
   * other declaration falls to its family's plain default.
   */
  private static Class<?> generatedDefaultFor(final Class<?> c, final Class<?> plainDefault) {
    if (c == SortedSet.class || c == NavigableSet.class) return TreeSet.class;
    if (c == SortedMap.class || c == NavigableMap.class) return TreeMap.class;
    if (c == ConcurrentMap.class) return ConcurrentHashMap.class;
    return plainDefault;
  }

  /**
   * The generated path's rule, restated. It names the declared class when that class is one it can
   * write {@code new X<>()} for, and the family's default otherwise — so a type it cannot
   * instantiate is not one it accepts either, and asking the reflective path to handle it would be
   * demanding more than parity.
   */
  private static boolean generatedPathAllocates(final Class<?> c, final Class<?> plainDefault) {
    final var fallback = generatedDefaultFor(c, plainDefault);
    // Nothing can be instantiated for an interface or an abstract class, so the family's default
    // stands in — and only where that default is actually one of them. Where it is not, the
    // generated path emits an allocation the declared field cannot hold, so it is no more able to
    // handle the type than the reflective path is, and asking for parity there asks for too much.
    if (c.isInterface() || Modifier.isAbstract(c.getModifiers())) return c.isAssignableFrom(fallback);
    try {
      return Modifier.isPublic(c.getConstructor().getModifiers());
    } catch (final NoSuchMethodException e) {
      return false;
    }
  }

  /**
   * Adopter-shaped containers, which is most of what a real model declares and no JDK scan finds.
   */
  interface MyListIface<E> extends List<E> {}

  interface MySetIface<E> extends Set<E> {}

  interface MyMapIface<K, V> extends Map<K, V> {}

  abstract static class MyAbstractList<E> extends ArrayList<E> {

    private static final long serialVersionUID = 1L;
  }

  static final class MyArrayList<E> extends ArrayList<E> {

    private static final long serialVersionUID = 1L;
  }

  private static List<Class<?>> corpus() throws IOException {
    final var out = new ArrayList<Class<?>>(javaBaseContainers());
    out.addAll(List.of(MyListIface.class, MySetIface.class, MyMapIface.class, MyAbstractList.class, MyArrayList.class));
    return out;
  }

  @Test
  @DisplayName("the reflective path allocates exactly what the generated path allocates, and refuses the" + " rest")
  void bothPathsAgreeOnEveryContainer() throws IOException {
    final var types = corpus();
    assertTrue(types.size() > 20, () -> "the corpus should be the JDK's own plus adopter shapes: " + types.size());

    final var divergent = new LinkedHashSet<String>();
    final var badRefusals = new LinkedHashSet<String>();
    for (final var c : types) {
      for (final var family : FAMILIES) {
        if (!family.iface().isAssignableFrom(c)) continue;
        final var generatedAllocates = generatedPathAllocates(c, family.fallback());
        var reflectiveAllocates = true;
        try {
          family.lift().apply(c, c);
        } catch (final IllegalStateException e) {
          // A refusal has to arrive here, while the plan is being built, carrying the type's name
          // and what to do instead. Any other throwable means the plan was built and the failure
          // moved to conversion time, where it is a bare cast error per call and the fail-fast
          // registry the starters build at startup no longer catches it.
          reflectiveAllocates = false;
        } catch (final Throwable t) {
          badRefusals.add(family.label() + " " + c.getName() + " -> " + t.getClass().getName());
          reflectiveAllocates = false;
        }
        // Asserted in both directions rather than filtered. Skipping the types the generated path
        // refuses would leave the reflective path free to accept them and fail later, which is the
        // worse divergence of the two: a refusal at plan time becomes a cast error per conversion,
        // and the fail-fast registry the starters build at startup stops catching it.
        if (generatedAllocates != reflectiveAllocates) {
          divergent.add(
            family.label() +
              " " +
              c.getName() +
              " -> generated " +
              (generatedAllocates ? "allocates" : "refuses") +
              ", reflective " +
              (reflectiveAllocates ? "allocates" : "refuses")
          );
        }
      }
    }

    assertTrue(divergent.isEmpty(), () -> "the two paths disagree on:\n  " + String.join("\n  ", divergent));
    assertTrue(
      badRefusals.isEmpty(),
      () -> "these are refused, but not at plan time with a diagnostic:\n  " + String.join("\n  ", badRefusals)
    );
  }
}
