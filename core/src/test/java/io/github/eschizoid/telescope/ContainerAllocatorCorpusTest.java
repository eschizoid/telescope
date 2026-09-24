package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.BridgeProcessor;
import io.github.eschizoid.telescope.codegen.ProcessorHarness;
import io.github.eschizoid.telescope.internal.optics.Iso;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The generated path and the reflective path have to accept the same programs, because swapping one
 * for the other is the whole point of having both. They decide what to allocate for a container
 * field separately, and a type one of them handles and the other does not is a bean that compiles
 * under {@code @Bridge} and throws under {@code mapper(...)}.
 *
 * <p>A test that names the container types it checks can only cover the ones somebody thought of.
 * This enumerates them from {@code java.base} itself, and asks each path what it does rather than
 * what it is documented to do: the reflective side is driven through its own lift, and the
 * generated side is compiled through the real processor. Neither verdict is a description that
 * could drift from the thing it describes.
 *
 * <p>The enumeration covers what the platform declares, which is not the same as covering every
 * shape. A container reached through a static {@code builder()} is one no {@code java.base} scan
 * can produce, because no JDK container has one -- so the adopter-shaped types below are not a
 * convenience beside the enumeration, they are the only way those shapes are seen at all.
 *
 * <p>Nor is every enumerated type asked anything. Only the three container kinds the lifts convert
 * between have a family here, so the {@code Deque} and {@code Queue} implementations are
 * enumerated, counted, and then left alone -- neither path allocates for them, one passing the
 * reference through and the other refusing while the plan is built, so there is nothing for an
 * allocation gate to compare.
 */
// Public so the adopter-shaped fixtures nested below can be named by the sources the gate compiles,
// which live in their own package. A package-private enclosing class hides them, and the gate would
// record the processor refusing a type it was never able to see.
public class ContainerAllocatorCorpusTest {

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
      // Every java.base has both of these, so a missing one is the enumeration being wrong rather
      // than a platform that lacks it. Skipping it quietly is what lets a whole root disappear.
      assertTrue(Files.isDirectory(root), () -> "the enumeration is pointed at a root that is not there: " + root);
      final var before = out.size();
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
      // Per root, not over the total. The two are far from equal -- one holds roughly twice the
      // other -- so a total large enough to look healthy is reached with the smaller one missing
      // entirely, and what goes with it is every concurrent container, which is exactly the set
      // the special-case tables on both paths exist for.
      assertTrue(out.size() > before, () -> "no container came from " + root);
    }
    // Asserted where the enumeration is produced rather than where it is used. An empty one
    // satisfies every question anybody asks of it -- "do these all agree", "does none of them
    // have a builder" -- so a caller that forgets to check reports success for having looked at
    // nothing.
    return out;
  }

  private static boolean isContainer(final Class<?> c) {
    return (
      Modifier.isPublic(c.getModifiers()) && (Collection.class.isAssignableFrom(c) || Map.class.isAssignableFrom(c))
    );
  }

  /**
   * The generated path's rule, executed. Every declared type in a family is compiled as a
   * {@code @Bridge} pair through the real processor and attributed by javac, so a verdict is what
   * the processor did rather than a description of the guard it consults. A restatement can only
   * find divergences it already models, and the routes that never reach the allocation guard are
   * exactly the ones it cannot see.
   *
   * <p>What it covers is one of those routes, not all of them. Both sides of a pair get the same
   * element type, so every conversion here preserves its elements and the processor takes the copy
   * route for all of them. That is the route the divergence this was built for lives on, and the
   * one a restatement could not reach -- but a conversion whose element type changes allocates
   * through different code, and nothing here looks at it. One blind spot narrowed, not removed.
   *
   * <p>The source declares the family's interface and the target the type under test, so the two
   * differ and an allocation is forced. Declaring the same type on both sides lets the reference
   * through unchanged and asks nothing.
   *
   * <p>One enum is the element type for every family. It is the only choice that satisfies every
   * bound in the corpus -- {@code EnumSet} and {@code EnumMap} accept nothing else, and an enum is
   * {@code Comparable}, so the sorted containers take it too. An element type that failed a bound
   * would be recorded as the processor refusing the container, which answers a question nobody
   * asked.
   *
   * <p>One compilation carries the whole family. Running them apart is the same answer at sixty
   * times the cost, and each type is emitted into its own package so a diagnostic names the type it
   * belongs to.
   */
  private static Map<Class<?>, Boolean> generatedVerdicts(final Family family, final List<Class<?>> types) {
    final var participating = types
      .stream()
      .filter(c -> family.iface().isAssignableFrom(c))
      .toList();
    final var sources = new ArrayList<JavaFileObject>();
    for (int i = 0; i < participating.size(); i++) {
      final var pkg = "corpus.t" + i;
      sources.add(ProcessorHarness.source(pkg + ".Key", "package " + pkg + ";\npublic enum Key { A, B }\n"));
      sources.add(
        ProcessorHarness.source(
          pkg + ".Src",
          "package " +
            pkg +
            ";\nimport io.github.eschizoid.telescope.annotations.Bridge;\n@Bridge(" +
            pkg +
            ".Dst.class)\npublic record Src(" +
            source(family.iface(), pkg, participating.get(i).getTypeParameters().length) +
            " items) {}\n"
        )
      );
      sources.add(
        ProcessorHarness.source(
          pkg + ".Dst",
          "package " +
            pkg +
            ";\npublic record Dst(" +
            declared(participating.get(i), pkg, participating.get(i).getTypeParameters().length) +
            " items) {}\n"
        )
      );
    }
    final var compilation = ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      sources.toArray(JavaFileObject[]::new)
    );
    final var refused = new HashSet<Integer>();
    final var belongsTo = Pattern.compile("/corpus/t(\\d+)/");
    for (final var d : compilation.errors()) {
      final var where = d.getSource() == null ? d.getMessage(Locale.ROOT) : d.getSource().toUri().toString();
      final var m = belongsTo.matcher(where);
      if (m.find()) refused.add(Integer.parseInt(m.group(1)));
      else {
        final var byName = Pattern.compile("corpus\\.t(\\d+)\\b").matcher(d.getMessage(Locale.ROOT));
        if (byName.find()) refused.add(Integer.parseInt(byName.group(1)));
      }
    }
    // Read off what the processor actually wrote rather than off the absence of an error attributed
    // to this package. The two agree today, and only one of them keeps agreeing: a diagnostic with
    // no source, a silent skip, or a round that aborts all leave a type looking accepted while
    // nothing was emitted for it.
    final var out = new LinkedHashMap<Class<?>, Boolean>();
    for (int i = 0; i < participating.size(); i++) {
      final var emitted = compilation.generated().containsKey("corpus.t" + i + ".SrcBridge");
      final var pair = family.label() + " " + participating.get(i).getName();
      assertEquals(!refused.contains(i), emitted, () -> "diagnostics and emitted output disagree about " + pair);
      out.put(participating.get(i), emitted);
    }
    return out;
  }

  /**
   * The source side of a pair. It carries type arguments whichever arity the target has, because a
   * raw source can be bridged to nothing: the processor refuses {@code Map} to {@code HashMap}
   * exactly as it refuses {@code Map} to {@code Properties}, and neither refusal says anything
   * about the target. A raw target is met with the arguments its own supertype fixes, which is
   * {@code Object}, and that pair compiles.
   */
  private static String source(final Class<?> iface, final String pkg, final int targetArity) {
    final var name = iface.getCanonicalName();
    if (targetArity != 0) return declared(iface, pkg, targetArity);
    return Map.class.isAssignableFrom(iface)
      ? name + "<java.lang.Object, java.lang.Object>"
      : name + "<java.lang.Object>";
  }

  /** The declared type as source text, with as many arguments as the class itself takes. */
  private static String declared(final Class<?> c, final String pkg, final int arity) {
    final var name = c.getCanonicalName();
    return switch (arity) {
      case 0 -> name;
      case 1 -> name + "<" + pkg + ".Key>";
      default -> name + "<" + pkg + ".Key, java.lang.String>";
    };
  }

  /** How a pair is decided by each path, so a recorded divergence names which way it runs. */
  private record Verdict(boolean generatedAllocates, boolean reflectiveAllocates) {
    private String render(final String pair) {
      return (
        pair +
        " -> generated " +
        (generatedAllocates ? "allocates" : "refuses") +
        ", reflective " +
        (reflectiveAllocates ? "allocates" : "refuses")
      );
    }
  }

  /**
   * Pairs the two paths are known to decide differently, by name and by direction, so a
   * disagreement is a recorded decision rather than a silence. A new one fails the gate; so does
   * one of these quietly agreeing, or one whose type has left the corpus, since a note about
   * something nobody checks is the same as a note about something untrue.
   *
   * <p>The direction is held as a value rather than written into the key. One of the two is the
   * harm this file opens by naming — generated accepts, reflective refuses, so a program compiles
   * and then throws — and the other merely refuses work that would have succeeded, so a register
   * that could not tell them apart would let the first pass on the strength of the second. Keeping
   * it structured means rewording the message below cannot fail the gate, which would otherwise
   * teach the next reader to fix a red gate by pasting a string.
   *
   * <p>The one entry: the reflective allocator reaches a static builder and the generated one has
   * no route to a container through one, because emitting that call needs the builder's own generic
   * signature rather than just its name. Tracked as a capability of its own. The benign direction.
   */
  private static final Map<String, Verdict> KNOWN_DIVERGENCES = Map.of(
    "List " + BuildableList.class.getName(),
    new Verdict(false, true),
    // Reached by the element-preserving copy, which never consults the allocation guard: the
    // processor writes a copy-constructor call where the reflective path refuses the type
    // outright. The harmful direction of the two, a build that succeeds and a conversion that
    // does not.
    "Map " + EnumMap.class.getName(),
    new Verdict(true, false)
  );

  /**
   * Abstract, with a static {@code builder()} that makes something concrete — the shape a {@code
   * java.base} enumeration structurally cannot produce, since no JDK container has a builder.
   *
   * <p>Its constructor's access is not what makes it interesting, and nothing on either path reads
   * it: abstractness alone settles the allocation question, and the builder is what the two then
   * disagree about. {@code MyAbstractList} is the control, differing in exactly that one property.
   */
  public abstract static class BuildableList<E> extends ArrayList<E> {

    private static final long serialVersionUID = 1L;

    protected BuildableList() {}

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      public BuildableList<Object> build() {
        return new BuildableListImpl<>();
      }
    }
  }

  /** What that builder makes. */
  public static final class BuildableListImpl<E> extends BuildableList<E> {

    private static final long serialVersionUID = 1L;

    public BuildableListImpl() {}
  }

  /**
   * Adopter-shaped containers, which is most of what a real model declares and no JDK scan finds.
   */
  public interface MyListIface<E> extends List<E> {}

  public interface MySetIface<E> extends Set<E> {}

  public interface MyMapIface<K, V> extends Map<K, V> {}

  /**
   * Written the way an adopter writes one: public, with a public no-argument constructor. A
   * package-private version of either of these passes the assertions below for the wrong reason —
   * the concrete one by being refused rather than allocated, and the abstract one by never reaching
   * the probe that would raise a linkage error on it.
   */
  public abstract static class MyAbstractList<E> extends ArrayList<E> {

    private static final long serialVersionUID = 1L;

    public MyAbstractList() {}
  }

  /** The acceptance case: a concrete adopter subclass the reflective path should allocate. */
  public static final class MyArrayList<E> extends ArrayList<E> {

    private static final long serialVersionUID = 1L;

    public MyArrayList() {}
  }

  private static List<Class<?>> corpus() throws IOException {
    final var out = new ArrayList<Class<?>>(javaBaseContainers());
    out.addAll(
      List.of(
        MyListIface.class,
        MySetIface.class,
        MyMapIface.class,
        MyAbstractList.class,
        MyArrayList.class,
        BuildableList.class
      )
    );
    return out;
  }

  @Test
  @DisplayName("no platform container is reached through a builder, which is why the adopter shapes exist")
  void noPlatformContainerHasABuilder() throws IOException {
    // The justification for the adopter-shaped fixtures, asserted rather than described. If a JDK
    // container ever grows a static builder(), the enumeration starts covering the shape on its own
    // and the sentence above it stops being the reason those fixtures are there.
    final var withBuilders = javaBaseContainers()
      .stream()
      .filter(c -> {
        try {
          return Modifier.isStatic(c.getMethod("builder").getModifiers());
        } catch (final NoSuchMethodException e) {
          return false;
        }
      })
      .map(Class::getName)
      .toList();

    assertTrue(
      withBuilders.isEmpty(),
      () -> "the enumeration can reach the builder shape after all, through: " + withBuilders
    );
  }

  @Test
  @DisplayName("the reflective path allocates exactly what the generated path allocates, and refuses the" + " rest")
  void bothPathsAgreeOnEveryContainer() throws IOException {
    final var types = corpus();

    // One compilation per family, ahead of the loop, so the processor runs three times rather than
    // once per pair.
    final var generated = new LinkedHashMap<String, Map<Class<?>, Boolean>>();
    for (final var family : FAMILIES) generated.put(family.label(), generatedVerdicts(family, types));

    final var divergent = new LinkedHashSet<String>();
    final var observedDivergences = new LinkedHashSet<String>();
    final var badRefusals = new LinkedHashSet<String>();
    for (final var c : types) {
      for (final var family : FAMILIES) {
        if (!family.iface().isAssignableFrom(c)) continue;
        final var generatedAllocates = generated.get(family.label()).get(c);
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
        final var pair = family.label() + " " + c.getName();
        final var verdict = new Verdict(generatedAllocates, reflectiveAllocates);
        if (generatedAllocates != reflectiveAllocates) {
          observedDivergences.add(pair);
          if (!verdict.equals(KNOWN_DIVERGENCES.get(pair))) divergent.add(verdict.render(pair));
        }
      }
    }

    assertTrue(divergent.isEmpty(), () -> "the two paths disagree on:\n  " + String.join("\n  ", divergent));
    // A register entry is only worth its line while the thing it describes is still true. One that
    // has stopped diverging, and one whose type has left the corpus so nothing looks any more, are
    // the same failure: a note about something nobody is checking.
    final var stale = new LinkedHashSet<>(KNOWN_DIVERGENCES.keySet());
    stale.removeAll(observedDivergences);
    assertTrue(
      stale.isEmpty(),
      () -> "recorded as diverging, but not observed to — resolved, or no longer in the corpus:\n  " + stale
    );
    assertTrue(
      badRefusals.isEmpty(),
      () -> "these are refused, but not at plan time with a diagnostic:\n  " + String.join("\n  ", badRefusals)
    );
  }
}
