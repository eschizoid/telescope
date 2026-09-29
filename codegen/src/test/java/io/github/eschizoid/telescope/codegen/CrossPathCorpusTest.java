package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * <p>A parity gate written by hand covers the shapes its author thought of, so it can stay green
 * while the rule it guards is wrong: the fixture varies a dimension the rule does not branch on.
 * Crossing the container families against the element shapes takes the choice of sample out of the
 * author's hands, though the axes themselves are still written down here.
 *
 * <p>A cell agrees when both paths hold the same contents in the same container class, and for a
 * container that promises an iteration order the same rendering too. It disagrees when one path
 * returns something the other does not, or when one refuses and the other does not, and a
 * disagreement is a defect unless {@link #KNOWN_DIVERGENCES} carries it. Agreement alone is not
 * enough in either direction: a converting cell owes the rendering both paths should have produced,
 * so a pair that is wrong the same way still fails, and a cell both paths refuse owes an entry in
 * {@link #KNOWN_REFUSALS}, because two refusals agree while measuring nothing.
 *
 * <p>Every cell's types are declared in this test's own package with a per-cell name prefix,
 * because the classes are defined through this class's lookup: that is what puts them in a module
 * the runtime accessor substrate can read, and it holds each name once.
 */
class CrossPathCorpusTest {

  /**
   * One container pairing: what the source declares, what the target declares, the class a rebuild
   * has to produce for the target, and whether that class promises an iteration order.
   *
   * <p>{@code allocates} is what a rebuild produces. A pair that is the same type on both sides
   * with an element needing no conversion rebuilds nothing at all, and {@link #passesThrough}
   * decides which of the two a cell owes.
   *
   * <p>{@code kind} decides how the input is built and how a rebuilt container renders. {@code
   * ordered} decides how much of the rendering a cell can owe: every class here is compared across
   * the two paths exactly, and only one that keeps insertion order is also held to a literal.
   */
  private record Family(String name, String kind, String src, String tgt, String allocates, boolean ordered) {}

  /**
   * The pairings: one per family either allocator table names, excluding those whose rebuild takes
   * a comparator, plus {@code iterable}, which neither table names and which is here to hold that
   * boundary. Ordering is a separate axis with its own in-flight work, and a cell that reorders
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

  /**
   * Cells both paths refuse, each with the reason.
   *
   * <p>Two paths that both refuse agree, so without a register a cell that stops converting goes
   * quiet instead of failing. That is the grid's worst failure mode, because the two paths ask one
   * classifier: a change there moves both together, parity holds, and nothing is measured. Naming
   * each mutual refusal turns it into a fact the grid checks in both directions — a cell that
   * starts refusing fails, and a cell listed here that starts converting fails too.
   */
  private static final Map<String, String> KNOWN_REFUSALS = Map.of(
    "iterable/record",
    "Iterable is not a Collection subtype, so neither path's classifier gives it a shape"
  );

  private static final String PACKAGE = "io.github.eschizoid.telescope.codegen";

  /**
   * The two element values every cell converts, in an order the insertion-ordered containers keep
   * and a plain hash container does not, which is what makes a hash family's cell worth comparing
   * by contents rather than by rendering. A wrong family is caught by the container class either
   * way: two values do not separate a list from an insertion-ordered set, whose renderings are
   * identical at any size.
   */
  private static final List<String> VALUES = List.of("b", "a");

  @Test
  @DisplayName("every generated cell is converted the same way by both paths")
  void bothPathsAgreeOnEveryCell() {
    final var failures = new ArrayList<String>();
    final var checked = new LinkedHashSet<String>();
    final var diverged = new LinkedHashSet<String>();
    final var refused = new LinkedHashSet<String>();
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

          // Resolved outside the attempt below, because a harness fault has to be an error rather
          // than an outcome: recorded as a refusal it would pair with the other path's refusal, and
          // the cell would report agreement having measured nothing.
          final var bridge = processed.success() ? emitted(processed, plain, prefix) : null;
          generated =
            bridge == null
              ? Outcome.refused(processed.errorMessages().strip())
              : run(items, () -> bridge.getMethod("forward", src).invoke(null, source));
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
        if (generated.refusal() != null) {
          refused.add(cell);
          if (!KNOWN_REFUSALS.containsKey(cell)) {
            failures.add(cell + ": both paths refused, and no reason is recorded — " + generated);
          }
          continue;
        }
        if (KNOWN_REFUSALS.containsKey(cell)) {
          failures.add(cell + ": recorded as refused by both paths, but it converted — " + generated);
          continue;
        }
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
    final var staleRefusals = new LinkedHashSet<>(KNOWN_REFUSALS.keySet());
    staleRefusals.removeAll(refused);
    assertTrue(
      staleRefusals.isEmpty(),
      () -> "recorded as refused, but not observed to be — no longer in the grid:\n  " + staleRefusals
    );
  }

  /**
   * Define the classes the processor's compile added over the plain one, and hand back the cell's
   * own bridge, named.
   *
   * <p>Only the added ones, because the pair itself is already defined and a name is defined once.
   * The bridge is looked up by name rather than by suffix: a cell whose element needs converting
   * also gets a bridge for the element pair, so two of the added names end in {@code Bridge} and
   * whichever the compiler happened to write first would otherwise decide which one the cell runs.
   */
  private static Class<?> emitted(
    final ProcessorHarness.Compilation processed,
    final ProcessorHarness.Compilation plain,
    final String prefix
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
    final var bridge = defined.get(PACKAGE + "." + prefix + "SrcBridge");
    if (bridge == null) throw new IllegalStateException("the processor emitted no bridge, only " + defined.keySet());
    return bridge;
  }

  @Test
  @DisplayName("a subtype whose own arguments are not its container view's is refused, not emitted wrong")
  void aSubtypeViewIsRefusedRatherThanEmittedWrong() {
    final var sources = new JavaFileObject[] {
      source("SvLeaf", "package " + PACKAGE + ";\npublic record SvLeaf(String v) {}\n"),
      source("SvLeafDto", "package " + PACKAGE + ";\npublic record SvLeafDto(String v) {}\n"),
      source("SvTagged", "package " + PACKAGE + ";\npublic class SvTagged<Tag, E> extends java.util.ArrayList<E> {}\n"),
      source(
        "SvSrc",
        "package " +
          PACKAGE +
          ";\nimport io.github.eschizoid.telescope.annotations.Bridge;\n@Bridge(SvTgt.class)\n" +
          "public record SvSrc(SvTagged<String, SvLeaf> items) {}\n"
      ),
      source("SvTgt", "package " + PACKAGE + ";\npublic record SvTgt(java.util.List<SvLeafDto> items) {}\n"),
    };
    final var processed = ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), sources);

    // The shared spec sees a one-element list here, because it resolves the arguments of the List
    // supertype rather than reading the two the subtype declares. A rebuild cannot write that view
    // into the declared name, so the plan has to refuse — and the refusal owed is the pairing one
    // that names both sides, not a type-argument count raised against code nobody wrote.
    assertFalse(processed.success(), () -> "should refuse: " + processed.generated().keySet());
    assertTrue(processed.hasError("has incompatible types"), processed::errorMessages);
    assertFalse(processed.hasError("wrong number of type arguments"), processed::errorMessages);
  }

  /**
   * Whether the pair is the same type on both sides with an element that needs no conversion, in
   * which case neither path allocates anything and the target holds the source's own container.
   *
   * <p>Such a cell would otherwise assert against the class the fixture happened to build, which
   * the families expect anyway — it would pass whatever either path did. The fixture builds a class
   * no family names, so the pass-through is visible, and the cell pins it: if either path ever
   * starts copying here, the cell fails and the decision surfaces rather than changing quietly.
   */
  private static boolean passesThrough(final Family family, final Element element) {
    return element.name().equals("scalar") && family.src().equals(family.tgt());
  }

  private static String inputClassOf(final String kind) {
    return switch (kind) {
      case "list" -> InputList.class.getName();
      case "set" -> InputSet.class.getName();
      default -> InputMap.class.getName();
    };
  }

  /** Container classes no family allocates, so handing one back unchanged is observable. */
  private static final class InputList<E> extends ArrayList<E> {

    private static final long serialVersionUID = 1L;
  }

  private static final class InputSet<E> extends LinkedHashSet<E> {

    private static final long serialVersionUID = 1L;
  }

  private static final class InputMap<K, V> extends LinkedHashMap<K, V> {

    private static final long serialVersionUID = 1L;
  }

  @Test
  @DisplayName("a raw container subtype pairs with a general-interface side without crashing the processor")
  void aRawSubtypePairsWithAGeneralInterfaceSide() {
    // A raw subtype carries its element type on a supertype, so an allocation has to walk for it. A
    // Deque, a Queue and a field declared as the general Collection are list-shaped without being
    // Lists, so a walk that asks under the List interface finds nothing for them: the emitter then
    // has no argument to write and the processor dies inside javac with no diagnostic of its own,
    // which is worse than any refusal because the adopter sees only a stack trace.
    final var general = List.of("java.util.Collection", "java.util.Deque", "java.util.Queue", "java.util.List");
    final var failures = new ArrayList<String>();
    for (final var raw : general) {
      for (final var side : List.of("source", "target")) {
        final var declaredGeneral = raw + "<RsLeafDto>";
        final var pair = side.equals("source")
          ? new String[] { "RsNames", declaredGeneral }
          : new String[] { raw + "<RsLeaf>", "RsNamesDto" };
        final var head = "package " + PACKAGE + ";\n";
        final var sources = new JavaFileObject[] {
          source("RsLeaf", head + "public record RsLeaf(String v) {}\n"),
          source("RsLeafDto", head + "public record RsLeafDto(String v) {}\n"),
          source("RsNames", head + "public class RsNames extends java.util.ArrayList<RsLeaf> {}\n"),
          source("RsNamesDto", head + "public class RsNamesDto extends java.util.ArrayList<RsLeafDto> {}\n"),
          source(
            "RsSrc",
            head +
              "import io.github.eschizoid.telescope.annotations.Bridge;\n@Bridge(RsTgt.class)\n" +
              "public record RsSrc(" +
              pair[0] +
              " items) {}\n"
          ),
          source("RsTgt", head + "public record RsTgt(" + pair[1] + " items) {}\n"),
        };
        try {
          final var processed = ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), sources);
          // Either outcome is a decision the processor made and said so. What must not happen is
          // the
          // processor throwing, which reaches the adopter as a compiler crash.
          if (!processed.success() && !processed.hasError("@Bridge")) {
            failures.add(
              raw + " as " + side + ": refused without a telescope diagnostic: " + processed.errorMessages()
            );
          }
        } catch (final RuntimeException e) {
          failures.add(raw + " as " + side + ": the processor threw " + e.getCause());
        }
      }
    }
    assertTrue(failures.isEmpty(), () -> String.join("\n  ", failures));
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
    final var expectedClass = passesThrough(family, element) ? inputClassOf(family.kind()) : family.allocates();
    if (!expectedClass.equals(outcome.allocated())) {
      return java.util.Optional.of("both paths allocated " + outcome.allocated() + ", expected " + expectedClass);
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
      case "list" -> {
        final var list = new InputList<Object>();
        list.addAll(leaves);
        yield list;
      }
      case "set" -> {
        final var set = new InputSet<Object>();
        set.addAll(leaves);
        yield set;
      }
      default -> {
        final var map = new InputMap<String, Object>();
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
