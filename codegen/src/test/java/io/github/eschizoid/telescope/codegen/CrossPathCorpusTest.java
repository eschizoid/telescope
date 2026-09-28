package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One input through both paths, for every cell of a generated grid.
 *
 * <p>Every parity gate in this repository is written by hand, and a hand-written gate covers the
 * shapes whoever wrote it thought of. Four separate ones have been green while the defect they were
 * written for was live, each time because the fixture varied a dimension the rule does not branch
 * on. Generating the grid removes the author's sample from the decision.
 *
 * <p>A cell agrees when both paths return the same value in the same container class, or when both
 * refuse. A cell disagrees when one path returns something the other does not, or when one refuses
 * and the other does not, and a disagreement is a defect unless {@link #KNOWN_DIVERGENCES} carries
 * it with a reason. Agreement alone is not enough: a cell also owes the rendering both paths should
 * have produced, so a pair that is wrong the same way still fails.
 *
 * <p>Every cell's types are declared in this test's own package with a per-cell name prefix,
 * because the classes are defined through this class's lookup: that is what puts them in a module
 * the runtime accessor substrate can read, and it holds each name once.
 */
class CrossPathCorpusTest {

  /**
   * One container pairing: what the source declares, what the target declares, the class both paths
   * have to allocate for the target, and whether that class promises an iteration order.
   *
   * <p>{@code kind} decides how the input is built and how a rebuilt container renders. {@code
   * ordered} decides how much of the rendering a cell can owe: every class here is compared across
   * the two paths exactly, and only one that keeps insertion order is also held to a literal.
   */
  private record Family(String name, String kind, String src, String tgt, String allocates, boolean ordered) {}

  /**
   * The pairings, one per family either allocator table names, excluding those whose rebuild takes
   * a comparator. Ordering is a separate axis with its own in-flight work, and a cell that reorders
   * would be measuring that instead of this.
   */
  private static final List<Family> FAMILIES = List.of(
    new Family("list/iface", "list", "java.util.List", "java.util.List", "java.util.ArrayList", true),
    new Family("list/concrete", "list", "java.util.List", "java.util.ArrayList", "java.util.ArrayList", true),
    new Family("collection", "list", "java.util.Collection", "java.util.Collection", "java.util.ArrayList", true),
    new Family("iterable", "list", "java.lang.Iterable", "java.lang.Iterable", "java.util.ArrayList", true),
    new Family("linkedlist", "list", "java.util.List", "java.util.LinkedList", "java.util.LinkedList", true),
    new Family("deque", "list", "java.util.List", "java.util.Deque", "java.util.ArrayDeque", true),
    new Family("queue", "list", "java.util.List", "java.util.Queue", "java.util.ArrayDeque", true),
    new Family("vector", "list", "java.util.List", "java.util.Vector", "java.util.Vector", true),
    new Family("stack", "list", "java.util.List", "java.util.Stack", "java.util.Stack", true),
    new Family(
      "cowlist",
      "list",
      "java.util.List",
      "java.util.concurrent.CopyOnWriteArrayList",
      "java.util.concurrent.CopyOnWriteArrayList",
      true
    ),
    new Family("set/iface", "set", "java.util.Set", "java.util.Set", "java.util.LinkedHashSet", true),
    new Family("set/concrete", "set", "java.util.Set", "java.util.LinkedHashSet", "java.util.LinkedHashSet", true),
    new Family("hashset", "set", "java.util.Set", "java.util.HashSet", "java.util.HashSet", false),
    new Family(
      "cowset",
      "set",
      "java.util.Set",
      "java.util.concurrent.CopyOnWriteArraySet",
      "java.util.concurrent.CopyOnWriteArraySet",
      true
    ),
    new Family("map/iface", "map", "java.util.Map", "java.util.Map", "java.util.LinkedHashMap", true),
    new Family("map/concrete", "map", "java.util.Map", "java.util.LinkedHashMap", "java.util.LinkedHashMap", true),
    new Family("hashmap", "map", "java.util.Map", "java.util.HashMap", "java.util.HashMap", false),
    new Family(
      "concurrentmap",
      "map",
      "java.util.Map",
      "java.util.concurrent.ConcurrentMap",
      "java.util.concurrent.ConcurrentHashMap",
      false
    ),
    new Family(
      "concurrenthashmap",
      "map",
      "java.util.Map",
      "java.util.concurrent.ConcurrentHashMap",
      "java.util.concurrent.ConcurrentHashMap",
      false
    ),
    new Family(
      "identityhashmap",
      "map",
      "java.util.Map",
      "java.util.IdentityHashMap",
      "java.util.IdentityHashMap",
      false
    ),
    new Family("weakhashmap", "map", "java.util.Map", "java.util.WeakHashMap", "java.util.WeakHashMap", false)
  );

  /**
   * The element type a cell's container holds. {@code rendered} takes the cell's name prefix and
   * one element's value, so a cell can say what its own converted elements look like.
   */
  private record Element(String name, String srcType, String tgtType, String rendered) {}

  private static final List<Element> ELEMENTS = List.of(
    new Element("scalar", "java.lang.String", "java.lang.String", "%2$s"),
    new Element("record", "%sLeaf", "%sLeafDto", "%1$sLeafDto[v=%2$s]")
  );

  /**
   * Which paths converted the cell at all, so a recorded divergence pins its shape rather than its
   * text.
   */
  private record Verdict(boolean generated, boolean reflective) {}

  /**
   * Cells whose two paths are known to differ, each recorded by what the two actually do.
   *
   * <p>An entry keeps a cell from failing and nothing else. A cell that starts differing without
   * one fails, and an entry whose cell has stopped differing fails too, so the register cannot
   * outlive what it describes.
   *
   * <p>All of these have one cause. The processor recognises a container only as a subtype of
   * {@code List}, {@code Set} or {@code Map}, while the shared pairing spec the reflective path
   * consults reads {@code Deque} and {@code Queue} as list-shaped and gives a {@code Collection}
   * whichever shape the other side has. So a target declared as any of those three is refused at
   * compile time and converted at run time — the harmful direction of the two, since a pairing that
   * works through {@code mapper(...)} cannot be moved to the generated path.
   */
  private static final Map<String, Verdict> KNOWN_DIVERGENCES = Map.of(
    "collection/record",
    new Verdict(false, true),
    "deque/scalar",
    new Verdict(false, true),
    "deque/record",
    new Verdict(false, true),
    "queue/scalar",
    new Verdict(false, true),
    "queue/record",
    new Verdict(false, true)
  );

  private static final String PACKAGE = "io.github.eschizoid.telescope.codegen";

  /**
   * The two element values every cell converts, in an order the insertion-ordered containers keep
   * and a plain hash container does not. One element renders a list and a set alike, which hides an
   * allocation that picked the wrong family; two of them do not.
   */
  private static final List<String> VALUES = List.of("b", "a");

  @Test
  @DisplayName("every generated cell is converted the same way by both paths")
  void bothPathsAgreeOnEveryCell() {
    final var failures = new ArrayList<String>();
    final var checked = new LinkedHashSet<String>();
    final var diverged = new LinkedHashSet<String>();
    var index = 0;

    for (final var family : FAMILIES) {
      for (final var element : ELEMENTS) {
        final var cell = family.name() + "/" + element.name();
        final var prefix = "C" + index++;
        final var sources = sources(prefix, family, element);
        checked.add(cell);

        // The pair compiles without the processor, because a refusal from the processor is one of
        // the outcomes being compared and the reflective path still needs the types to read. The
        // second compile adds the processor; the two see identical sources, so the generated bridge
        // links against the classes the first one already defined.
        final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources);
        assertTrue(plain.success(), () -> cell + " should compile without the processor: " + plain.errorMessages());
        final var processed = ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), sources);

        final Outcome generated;
        final Outcome reflective;
        try {
          final var classes = plain.define(MethodHandles.lookup());
          final var src = classes.get(PACKAGE + "." + prefix + "Src");
          final var tgt = classes.get(PACKAGE + "." + prefix + "Tgt");
          final var source = src.getConstructors()[0].newInstance(items(classes, prefix, family, element));
          final var items = tgt.getMethod("items");

          generated = processed.success()
            ? run(items, () -> emitted(processed, plain).getMethod("forward", src).invoke(null, source))
            : Outcome.refused(processed.errorMessages().strip());
          reflective = run(items, () -> Telescope.mapper(cast(src), cast(tgt)).forward(source));
        } catch (final ReflectiveOperationException e) {
          throw new IllegalStateException(cell + " could not be built", e);
        }

        if (!generated.agreesWith(reflective, family.ordered())) {
          diverged.add(cell);
          final var verdict = new Verdict(generated.refusal() == null, reflective.refusal() == null);
          if (!verdict.equals(KNOWN_DIVERGENCES.get(cell))) {
            failures.add(cell + ": generated " + generated + ", reflective " + reflective);
          }
          continue;
        }
        if (generated.refusal() != null) continue;
        owed(prefix, family, element, generated).ifPresent(owed -> failures.add(cell + ": " + owed));
      }
    }

    assertTrue(
      checked.size() == FAMILIES.size() * ELEMENTS.size(),
      () -> "every cell should be reached, saw " + checked
    );
    assertTrue(failures.isEmpty(), () -> failures.size() + " cell(s) failed:\n  " + String.join("\n  ", failures));
    // A register entry is only worth its line while the thing it describes is still true. One whose
    // cell has stopped diverging and one whose cell has left the grid are the same failure: a note
    // about something nobody is checking.
    final var stale = new LinkedHashSet<>(KNOWN_DIVERGENCES.keySet());
    stale.removeAll(diverged);
    assertTrue(
      stale.isEmpty(),
      () -> "recorded as diverging, but not observed to — resolved, or no longer in the grid:\n  " + stale
    );
  }

  /**
   * Define the classes the processor's compile added over the plain one, and hand back the bridge.
   *
   * <p>Only the added ones, because the pair itself is already defined and a name is defined once.
   */
  private static Class<?> emitted(
    final ProcessorHarness.Compilation processed,
    final ProcessorHarness.Compilation plain
  ) {
    final var added = new LinkedHashMap<>(processed.classes());
    plain.classes().keySet().forEach(added::remove);
    final var defined = new ProcessorHarness.Compilation(
      processed.success(),
      processed.diagnostics(),
      processed.generated(),
      processed.resources(),
      added
    ).define(MethodHandles.lookup());
    for (final var entry : defined.entrySet()) {
      if (entry.getKey().endsWith("Bridge")) return entry.getValue();
    }
    throw new IllegalStateException("the processor emitted no bridge, only " + defined.keySet());
  }

  private static JavaFileObject[] sources(final String prefix, final Family family, final Element element) {
    final var srcElement = element.srcType().formatted(prefix);
    final var tgtElement = element.tgtType().formatted(prefix);
    final var head = "package " + PACKAGE + ";\n";
    return new JavaFileObject[] {
      source(prefix + "Leaf", head + "public record " + prefix + "Leaf(String v) {}\n"),
      source(prefix + "LeafDto", head + "public record " + prefix + "LeafDto(String v) {}\n"),
      source(
        prefix + "Src",
        head +
          "import io.github.eschizoid.telescope.annotations.Bridge;\n@Bridge(" +
          prefix +
          "Tgt.class)\npublic record " +
          prefix +
          "Src(" +
          declared(family.src(), family.kind(), srcElement) +
          " items) {}\n"
      ),
      source(
        prefix + "Tgt",
        head + "public record " + prefix + "Tgt(" + declared(family.tgt(), family.kind(), tgtElement) + " items) {}\n"
      ),
    };
  }

  /**
   * A field type: the raw container with the element type, keyed by a String where it takes a key.
   */
  private static String declared(final String raw, final String kind, final String elementType) {
    return kind.equals("map") ? raw + "<java.lang.String, " + elementType + ">" : raw + "<" + elementType + ">";
  }

  private static JavaFileObject source(final String simpleName, final String code) {
    return ProcessorHarness.source(PACKAGE + "." + simpleName, code);
  }

  /**
   * What the cell's two paths still owe after agreeing: the container class the pairing names, the
   * elements it should hold, and for an insertion-ordered class the exact rendering.
   */
  private static java.util.Optional<String> owed(
    final String prefix,
    final Family family,
    final Element element,
    final Outcome outcome
  ) {
    if (!family.allocates().equals(outcome.allocated())) {
      return java.util.Optional.of("both paths allocated " + outcome.allocated() + ", expected " + family.allocates());
    }
    final var rendered = VALUES.stream()
      .map(v -> element.rendered().formatted(prefix, v))
      .toList();
    final var expectedElements = new TreeSet<String>();
    for (var i = 0; i < rendered.size(); i++) {
      expectedElements.add(family.kind().equals("map") ? "k" + (i + 1) + "=" + rendered.get(i) : rendered.get(i));
    }
    if (!expectedElements.equals(outcome.elements())) {
      return java.util.Optional.of("both paths held " + outcome.elements() + ", expected " + expectedElements);
    }
    if (!family.ordered()) return java.util.Optional.empty();
    final var body = family.kind().equals("map")
      ? "{k1=" + rendered.get(0) + ", k2=" + rendered.get(1) + "}"
      : "[" + rendered.get(0) + ", " + rendered.get(1) + "]";
    final var expected = prefix + "Tgt[items=" + body + "]";
    return expected.equals(outcome.value())
      ? java.util.Optional.empty()
      : java.util.Optional.of("both paths rendered " + outcome.value() + ", expected " + expected);
  }

  private static Object items(
    final Map<String, Class<?>> classes,
    final String prefix,
    final Family family,
    final Element element
  ) throws ReflectiveOperationException {
    final var leaves = new ArrayList<>();
    for (final var value : VALUES) {
      leaves.add(
        element.name().equals("record")
          ? classes.get(PACKAGE + "." + prefix + "Leaf").getConstructor(String.class).newInstance(value)
          : value
      );
    }
    return switch (family.kind()) {
      case "list" -> new ArrayList<>(leaves);
      case "set" -> new LinkedHashSet<>(leaves);
      default -> {
        final var map = new LinkedHashMap<String, Object>();
        for (var i = 0; i < leaves.size(); i++) map.put("k" + (i + 1), leaves.get(i));
        yield map;
      }
    };
  }

  /**
   * What one path produced, or the refusal it made instead.
   *
   * <p>{@code allocated} is the class of the rebuilt container rather than of the target record,
   * because an interface-typed field leaves that choice to whichever table the path consults, and
   * two tables that name different implementations both satisfy the declaration. {@code elements}
   * is what the container holds, sorted, so a class with no iteration-order promise can still be
   * held to its contents.
   */
  private record Outcome(String value, String allocated, java.util.SortedSet<String> elements, String refusal) {
    static Outcome of(final Object produced, final Object container) {
      return new Outcome(
        String.valueOf(produced),
        container == null ? "null" : container.getClass().getName(),
        elementsOf(container),
        null
      );
    }

    static Outcome refused(final String message) {
      return new Outcome(null, null, new TreeSet<>(), message);
    }

    static Outcome refused(final Throwable t) {
      final var sb = new StringBuilder(t.getClass().getSimpleName() + ": " + t.getMessage());
      for (var c = t.getCause(); c != null; c = c.getCause()) {
        sb.append(" <- ").append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
      }
      return new Outcome(null, null, new TreeSet<>(), sb.toString());
    }

    private static java.util.SortedSet<String> elementsOf(final Object container) {
      final var out = new TreeSet<String>();
      if (container instanceof Map<?, ?> map) {
        map.forEach((k, v) -> out.add(k + "=" + v));
      } else if (container instanceof Iterable<?> iterable) {
        for (final var e : iterable) out.add(String.valueOf(e));
      }
      return out;
    }

    /**
     * Whether the two paths did the same thing, with {@code ordered} saying how much of "the same"
     * the container promises.
     *
     * <p>A class that promises an iteration order is compared by its rendering, so a reordering is
     * caught. One that promises none cannot be: {@code IdentityHashMap} iterates by where its keys
     * land in a table whose size comes from the allocation argument, and two capacities holding the
     * same two keys order them differently about half the time. Comparing renderings there would
     * fail on a difference neither path owes the caller, and pass or fail per run. The contents and
     * the class are compared for every family either way.
     */
    boolean agreesWith(final Outcome other, final boolean ordered) {
      if (refusal != null || other.refusal != null) return refusal != null && other.refusal != null;
      if (!allocated.equals(other.allocated)) return false;
      return ordered ? value.equals(other.value) : elements.equals(other.elements);
    }

    @Override
    public String toString() {
      return refusal == null ? value + " in " + allocated : "refused(" + refusal + ")";
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> Class<T> cast(final Class<?> type) {
    return (Class<T>) type;
  }

  private interface Attempt {
    Object get() throws ReflectiveOperationException;
  }

  private static Outcome run(final Method items, final Attempt attempt) {
    try {
      final var produced = attempt.get();
      return Outcome.of(produced, items.invoke(produced));
    } catch (final InvocationTargetException e) {
      return Outcome.refused(e.getCause() == null ? e : e.getCause());
    } catch (final ReflectiveOperationException | RuntimeException e) {
      return Outcome.refused(e);
    }
  }
}
