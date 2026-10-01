package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
   * order} decides how much of the rendering a cell can owe: a class that promises no order is
   * compared by contents, and every other class is compared across the two paths exactly and held
   * to a literal.
   */
  private record Family(String name, String kind, String src, String tgt, String allocates, Order order) {}

  /**
   * How much of a rebuilt container's rendering the cell owes. A sorted family's input is ordered
   * by a comparator, so its literal is the order that comparator gives, and a rebuild that drops
   * the comparator renders in natural order instead.
   */
  private enum Order {
    NONE,
    INSERTION,
    SORTED,
  }

  /**
   * The pairings: one per family either allocator table names, excluding those whose rebuild takes
   * a key class, plus {@code iterable}, which neither table names and which is here to hold that
   * boundary.
   */
  private static final List<Family> FAMILIES = List.of(
    new Family("list/iface", "list", "java.util.List", "java.util.List", "java.util.ArrayList", Order.INSERTION),
    new Family(
      "list/concrete",
      "list",
      "java.util.List",
      "java.util.ArrayList",
      "java.util.ArrayList",
      Order.INSERTION
    ),
    new Family(
      "collection",
      "list",
      "java.util.Collection",
      "java.util.Collection",
      "java.util.ArrayList",
      Order.INSERTION
    ),
    // Neither cell reads allocates: the scalar one is a pass-through and the converting one
    // is a
    // registered mutual refusal, which is the boundary this family is here to hold.
    new Family("iterable", "list", "java.lang.Iterable", "java.lang.Iterable", "", Order.INSERTION),
    new Family("linkedlist", "list", "java.util.List", "java.util.LinkedList", "java.util.LinkedList", Order.INSERTION),
    new Family("deque", "list", "java.util.List", "java.util.Deque", "java.util.ArrayDeque", Order.INSERTION),
    new Family("queue", "list", "java.util.List", "java.util.Queue", "java.util.ArrayDeque", Order.INSERTION),
    new Family("vector", "list", "java.util.List", "java.util.Vector", "java.util.Vector", Order.INSERTION),
    new Family("stack", "list", "java.util.List", "java.util.Stack", "java.util.Stack", Order.INSERTION),
    new Family(
      "cowlist",
      "list",
      "java.util.List",
      "java.util.concurrent.CopyOnWriteArrayList",
      "java.util.concurrent.CopyOnWriteArrayList",
      Order.INSERTION
    ),
    new Family("set/iface", "set", "java.util.Set", "java.util.Set", "java.util.LinkedHashSet", Order.INSERTION),
    new Family(
      "set/concrete",
      "set",
      "java.util.Set",
      "java.util.LinkedHashSet",
      "java.util.LinkedHashSet",
      Order.INSERTION
    ),
    new Family("hashset", "set", "java.util.Set", "java.util.HashSet", "java.util.HashSet", Order.NONE),
    new Family(
      "cowset",
      "set",
      "java.util.Set",
      "java.util.concurrent.CopyOnWriteArraySet",
      "java.util.concurrent.CopyOnWriteArraySet",
      Order.INSERTION
    ),
    new Family("map/iface", "map", "java.util.Map", "java.util.Map", "java.util.LinkedHashMap", Order.INSERTION),
    new Family(
      "map/concrete",
      "map",
      "java.util.Map",
      "java.util.LinkedHashMap",
      "java.util.LinkedHashMap",
      Order.INSERTION
    ),
    new Family("hashmap", "map", "java.util.Map", "java.util.HashMap", "java.util.HashMap", Order.NONE),
    new Family(
      "concurrentmap",
      "map",
      "java.util.Map",
      "java.util.concurrent.ConcurrentMap",
      "java.util.concurrent.ConcurrentHashMap",
      Order.NONE
    ),
    new Family(
      "concurrenthashmap",
      "map",
      "java.util.Map",
      "java.util.concurrent.ConcurrentHashMap",
      "java.util.concurrent.ConcurrentHashMap",
      Order.NONE
    ),
    new Family(
      "identityhashmap",
      "map",
      "java.util.Map",
      "java.util.IdentityHashMap",
      "java.util.IdentityHashMap",
      Order.NONE
    ),
    new Family("weakhashmap", "map", "java.util.Map", "java.util.WeakHashMap", "java.util.WeakHashMap", Order.NONE),
    new Family("sortedset/iface", "set", "java.util.Set", "java.util.SortedSet", "java.util.TreeSet", Order.SORTED),
    new Family("navigableset", "set", "java.util.Set", "java.util.NavigableSet", "java.util.TreeSet", Order.SORTED),
    new Family("treeset", "set", "java.util.Set", "java.util.TreeSet", "java.util.TreeSet", Order.SORTED),
    new Family(
      "skiplistset",
      "set",
      "java.util.Set",
      "java.util.concurrent.ConcurrentSkipListSet",
      "java.util.concurrent.ConcurrentSkipListSet",
      Order.SORTED
    ),
    new Family("sortedmap/iface", "map", "java.util.Map", "java.util.SortedMap", "java.util.TreeMap", Order.SORTED),
    new Family("navigablemap", "map", "java.util.Map", "java.util.NavigableMap", "java.util.TreeMap", Order.SORTED),
    new Family("treemap", "map", "java.util.Map", "java.util.TreeMap", "java.util.TreeMap", Order.SORTED),
    new Family(
      "skiplistmap",
      "map",
      "java.util.Map",
      "java.util.concurrent.ConcurrentSkipListMap",
      "java.util.concurrent.ConcurrentSkipListMap",
      Order.SORTED
    )
  );

  /**
   * The element type a cell's container holds. {@code rendered} takes the cell's name prefix and
   * one element's value, so a cell can say what its own converted elements look like. {@code
   * converts} says whether the element changes type, which is also whether the input holds records
   * rather than strings.
   *
   * <p>The wildcard elements are the same on both sides, so each is an identity copy. The two type
   * systems answer type identity for a wildcard by different rules, and an element that is the same
   * on both sides is where a disagreement would show.
   */
  private record Element(String name, String srcType, String tgtType, String rendered, boolean converts) {}

  private static final List<Element> ELEMENTS = List.of(
    new Element("scalar", "java.lang.String", "java.lang.String", "%2$s", false),
    new Element("record", "%sLeaf", "%sLeafDto", "%1$sLeafDto[v=%2$s]", true),
    new Element("bounded", "? extends java.lang.String", "? extends java.lang.String", "%2$s", false),
    new Element("unbounded", "?", "?", "%2$s", false)
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
   */
  private static final Map<String, Verdict> KNOWN_DIVERGENCES = Map.of();

  /**
   * Cells both paths refuse, each with the reason.
   *
   * <p>Two paths that both refuse agree, so without a register a cell that stops converting goes
   * quiet instead of failing. That is the grid's worst failure mode, because both paths ask one
   * classifier for what counts as a container: a change there moves both together, parity holds,
   * and nothing is measured. Naming each mutual refusal turns it into a fact the grid checks in
   * both directions — a cell that starts refusing fails, and a cell listed here that starts
   * converting fails too.
   */
  private static final Map<String, Refusal> KNOWN_REFUSALS = Map.of(
    // Iterable is not a Collection subtype, so neither path's classifier gives it a shape.
    "iterable/record",
    new Refusal("has incompatible types", "incompatible source/target shapes"),
    // A sorted set whose elements are converted cannot carry the source's comparator, and the
    // converted element does not implement Comparable, so both paths refuse the pairing.
    "sortedset/iface/record",
    new Refusal("does not implement Comparable", "does not implement Comparable"),
    "navigableset/record",
    new Refusal("does not implement Comparable", "does not implement Comparable"),
    "treeset/record",
    new Refusal("does not implement Comparable", "does not implement Comparable"),
    "skiplistset/record",
    new Refusal("does not implement Comparable", "does not implement Comparable")
  );

  /**
   * What each path says when it refuses a registered cell.
   *
   * <p>A fragment of each message rather than a note about the cause, because a note is not
   * checked: an entry that only names a cell accepts any refusal at all, so a harness fault or an
   * unrelated failure on both sides would satisfy it and the cell would pass having measured
   * nothing. Naming what each path is expected to say makes the register carry the same weight as
   * an assertion.
   */
  private record Refusal(String generatedSays, String reflectiveSays) {}

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
          final var forward = processed.success() ? emitted(processed, plain, prefix).getMethod("forward", src) : null;
          generated =
            forward == null
              ? Outcome.refused(processed.errorMessages().strip())
              : run(items, () -> forward.invoke(null, source));
          reflective = run(items, () -> Telescope.mapper(cast(src), cast(tgt)).forward(source));
        } catch (final ReflectiveOperationException e) {
          throw new IllegalStateException(cell + " could not be built", e);
        }

        if (!generated.agreesWith(reflective, family.order())) {
          diverged.add(cell);
          final var verdict = new Verdict(generated.refusal() == null, reflective.refusal() == null);
          if (!verdict.equals(KNOWN_DIVERGENCES.get(cell))) {
            failures.add(cell + ": generated " + generated + ", reflective " + reflective);
          }
          continue;
        }
        if (generated.refusal() != null) {
          refused.add(cell);
          final var recorded = KNOWN_REFUSALS.get(cell);
          if (recorded == null) {
            failures.add(cell + ": both paths refused, and no reason is recorded — " + generated);
          } else if (
            !generated.refusal().contains(recorded.generatedSays()) ||
            !reflective.refusal().contains(recorded.reflectiveSays())
          ) {
            failures.add(
              cell +
                ": refused for a reason the register does not name — generated " +
                generated +
                ", reflective " +
                reflective
            );
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

  /**
   * A container subtype whose own type arguments differ from the container it is viewed as: the
   * declaration, the field type that names it, the target field, how its input is filled, and what
   * both paths owe — the rendering and the class of the rebuilt container, or a refusal.
   *
   * <p>{@code %s} is the cell's prefix everywhere it appears. A refusal is owed as {@code refused:}
   * followed by a fragment of the processor's own diagnostic, so a javac error inside a generated
   * file cannot pass for one. The scalar rows copy their elements unchanged, which takes the
   * self-contained helper rather than the element-bridging ones.
   */
  private record SubtypeView(
    String name,
    String declaration,
    String srcField,
    String tgtField,
    String put,
    String owed
  ) {}

  private static final List<SubtypeView> SUBTYPE_VIEWS = List.of(
    // Declares two arguments and holds elements of the second.
    new SubtypeView(
      "extra parameter to an interface",
      "public class %sTagged<Tag, E> extends java.util.ArrayList<E> {}",
      "%sTagged<String, %sLeaf>",
      "java.util.List<%sLeafDto>",
      "add",
      "%sTgt[items=[%sLeafDto[v=x]]] in java.util.ArrayList"
    ),
    new SubtypeView(
      "extra parameter on both sides",
      "public class %sTagged<Tag, E> extends java.util.ArrayList<E> {}",
      "%sTagged<String, %sLeaf>",
      "%sTagged<String, %sLeafDto>",
      "add",
      "%sTgt[items=[%sLeafDto[v=x]]] in %sTagged"
    ),
    // Declares its key and value in the opposite order to Map.
    new SubtypeView(
      "reordered map parameters",
      "public class %sReordered<V, K> extends java.util.HashMap<K, V> {}",
      "%sReordered<%sLeaf, String>",
      "%sReordered<%sLeafDto, String>",
      "put",
      "%sTgt[items={k=%sLeafDto[v=x]}] in %sReordered"
    ),
    // Fixes the key and declares one argument where Map has two.
    new SubtypeView(
      "fixed map key",
      "public class %sStringMap<V> extends java.util.HashMap<String, V> {}",
      "%sStringMap<%sLeaf>",
      "java.util.Map<String, %sLeafDto>",
      "put",
      "%sTgt[items={k=%sLeafDto[v=x]}] in java.util.LinkedHashMap"
    ),
    new SubtypeView(
      "scalar into an extra parameter",
      "public class %sTagged<Tag, E> extends java.util.ArrayList<E> {}",
      "java.util.List<String>",
      "%sTagged<Integer, String>",
      "addString",
      "%sTgt[items=[s]] in %sTagged"
    ),
    new SubtypeView(
      "scalar, extra parameter on both sides",
      "public class %sTagged<Tag, E> extends java.util.ArrayList<E> {}",
      "%sTagged<String, String>",
      "%sTagged<Integer, String>",
      "addString",
      "%sTgt[items=[s]] in %sTagged"
    ),
    new SubtypeView(
      "scalar, fixed map key",
      "public class %sStringMap<V> extends java.util.HashMap<String, V> {}",
      "java.util.Map<String, String>",
      "%sStringMap<String>",
      "putString",
      "%sTgt[items={k=s}] in %sStringMap"
    ),
    // A wildcard among the subtype's own arguments, which an allocation cannot name.
    new SubtypeView(
      "wildcard tag",
      "public class %sTagged<Tag, E> extends java.util.ArrayList<E> {}",
      "%sTagged<String, %sLeaf>",
      "%sTagged<? extends CharSequence, %sLeafDto>",
      "add",
      "%sTgt[items=[%sLeafDto[v=x]]] in %sTagged"
    ),
    new SubtypeView(
      "unbounded tag on both sides",
      "public class %sTagged<Tag, E> extends java.util.ArrayList<E> {}",
      "%sTagged<?, %sLeaf>",
      "%sTagged<?, %sLeafDto>",
      "add",
      "%sTgt[items=[%sLeafDto[v=x]]] in %sTagged"
    ),
    // Declares its parameters in the opposite order to TreeMap, and its comparator orders
    // keys.
    new SubtypeView(
      "sorted map with swapped parameters",
      "public class %sSwapped<V, K> extends java.util.TreeMap<K, V> {\n" +
        "  public %sSwapped() {}\n" +
        "  public %sSwapped(final java.util.Comparator<? super K> order) { super(order); }\n" +
        "}",
      "java.util.SortedMap<String, %sLeaf>",
      "%sSwapped<%sLeafDto, String>",
      "putReversed",
      "%sTgt[items={k=%sLeafDto[v=x], j=%sLeafDto[v=x]}] in %sSwapped"
    ),
    // A wildcard for a bounded parameter allocates over the bound.
    new SubtypeView(
      "wildcard for a bounded parameter",
      "public class %sBTag<T extends Number, E> extends java.util.ArrayList<E> {}",
      "%sBTag<?, %sLeaf>",
      "%sBTag<? super Integer, %sLeafDto>",
      "add",
      "%sTgt[items=[%sLeafDto[v=x]]] in %sBTag"
    ),
    // A self-referential bound has no type that can be written for a wildcard.
    new SubtypeView(
      "wildcard for a self-referential parameter",
      "public class %sFTag<T extends Comparable<T>, E> extends java.util.ArrayList<E> {}",
      "%sFTag<?, %sLeaf>",
      "%sFTag<?, %sLeafDto>",
      "add",
      "%sTgt[items=[%sLeafDto[v=x]]] in %sFTag"
    ),
    // An upper bound the parameter's bound does not admit leaves no type to write either.
    new SubtypeView(
      "wildcard outside a parameter's bound",
      "public class %sBTag<T extends Number, E> extends java.util.ArrayList<E> {}",
      "%sBTag<? extends Comparable<Integer>, %sLeaf>",
      "java.util.List<%sLeafDto>",
      "add",
      "%sTgt[items=[%sLeafDto[v=x]]] in java.util.ArrayList"
    ),
    // A sorted subtype reached through the raw allocation still receives the source's
    // comparator.
    new SubtypeView(
      "sorted subtype with a self-referential parameter",
      "public class %sFTree<T extends Comparable<T>, E> extends java.util.TreeSet<E> {\n" +
        "  public %sFTree() {}\n" +
        "  public %sFTree(final java.util.Comparator<? super E> order) { super(order); }\n" +
        "}",
      "java.util.SortedSet<String>",
      "%sFTree<?, String>",
      "addReversed",
      "%sTgt[items=[t, s]] in %sFTree"
    ),
    // Holds Strings whatever its argument says, so its elements and the target's cannot pair.
    new SubtypeView(
      "argument unrelated to the elements",
      "public class %sWrap<E> extends java.util.ArrayList<String> {}",
      "%sWrap<%sLeaf>",
      "java.util.List<%sLeafDto>",
      "addString",
      "refused: element types are incompatible"
    )
  );

  @Test
  @DisplayName("a subtype whose own arguments differ from its container view converts the same way on both paths")
  void aSubtypeViewConvertsTheSameWayOnBothPaths() throws ReflectiveOperationException {
    // The view is where an element's type is found, and the declared type is what the generated
    // source has to write: a field of a subtype is a value of that subtype, not of the container
    // it is viewed as.
    final var failures = new ArrayList<String>();
    var index = 0;
    for (final var shape : SUBTYPE_VIEWS) {
      final var prefix = "Sv" + index++;
      final var head = "package " + PACKAGE + ";\n";
      final var declaration = shape.declaration().replace("%s", prefix);
      final var declared = declaration.split(" ")[2].split("<")[0];
      final var sources = new JavaFileObject[] {
        source(prefix + "Leaf", head + "public record " + prefix + "Leaf(String v) {}\n"),
        source(prefix + "LeafDto", head + "public record " + prefix + "LeafDto(String v) {}\n"),
        source(declared, head + declaration + "\n"),
        source(
          prefix + "Src",
          head +
            "import io.github.eschizoid.telescope.annotations.Bridge;\n@Bridge(" +
            prefix +
            "Tgt.class)\npublic record " +
            prefix +
            "Src(" +
            shape.srcField().replace("%s", prefix) +
            " items) {}\n"
        ),
        source(
          prefix + "Tgt",
          head + "public record " + prefix + "Tgt(" + shape.tgtField().replace("%s", prefix) + " items) {}\n"
        ),
      };
      final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources);
      assertTrue(
        plain.success(),
        () -> shape.name() + " should compile without the processor: " + plain.errorMessages()
      );
      final var processed = ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), sources);

      final var classes = plain.define(MethodHandles.lookup());
      final var src = classes.get(PACKAGE + "." + prefix + "Src");
      final var tgt = classes.get(PACKAGE + "." + prefix + "Tgt");
      final var declaredClass = classes.get(PACKAGE + "." + declared);
      final var container = shape.put().endsWith("Reversed")
        ? declaredClass.getConstructor(Comparator.class).newInstance(Comparator.reverseOrder())
        : declaredClass.getConstructor().newInstance();
      final var leaf = classes.get(PACKAGE + "." + prefix + "Leaf").getConstructor(String.class).newInstance("x");
      switch (shape.put()) {
        case "put" -> Map.class.getMethod("put", Object.class, Object.class).invoke(container, "k", leaf);
        case "add" -> Collection.class.getMethod("add", Object.class).invoke(container, leaf);
        case "putString" -> Map.class.getMethod("put", Object.class, Object.class).invoke(container, "k", "s");
        case "addReversed" -> {
          Collection.class.getMethod("add", Object.class).invoke(container, "s");
          Collection.class.getMethod("add", Object.class).invoke(container, "t");
        }
        case "putReversed" -> {
          Map.class.getMethod("put", Object.class, Object.class).invoke(container, "j", leaf);
          Map.class.getMethod("put", Object.class, Object.class).invoke(container, "k", leaf);
        }
        default -> Collection.class.getMethod("add", Object.class).invoke(container, "s");
      }
      final var source = src.getConstructors()[0].newInstance(container);
      final var items = tgt.getMethod("items");

      final var forward = processed.success() ? emitted(processed, plain, prefix).getMethod("forward", src) : null;
      final var generated =
        forward == null
          ? Outcome.refused(processed.errorMessages().strip())
          : run(items, () -> forward.invoke(null, source));
      final var reflective = run(items, () -> Telescope.mapper(cast(src), cast(tgt)).forward(source));

      final var owed = shape
        .owed()
        .replace("%s", prefix)
        .replace(prefix + "Tagged", PACKAGE + "." + prefix + "Tagged")
        .replace(prefix + "Reordered", PACKAGE + "." + prefix + "Reordered")
        .replace(prefix + "StringMap", PACKAGE + "." + prefix + "StringMap")
        .replace(prefix + "Swapped", PACKAGE + "." + prefix + "Swapped")
        .replace(prefix + "BTag", PACKAGE + "." + prefix + "BTag")
        .replace(prefix + "FTag", PACKAGE + "." + prefix + "FTag")
        .replace(prefix + "FTree", PACKAGE + "." + prefix + "FTree");
      final var owesRefusal = owed.startsWith("refused: ");
      for (final var side : List.of(Map.entry("generated", generated), Map.entry("reflective", reflective))) {
        final var outcome = side.getValue();
        final var met = owesRefusal
          ? outcome.refusal() != null &&
            (side.getKey().equals("reflective") || outcome.refusal().contains(owed.substring("refused: ".length())))
          : outcome.refusal() == null && owed.equals(outcome.toString());
        if (!met) failures.add(shape.name() + ": " + side.getKey() + " gave " + outcome + ", owed " + owed);
      }
    }
    assertTrue(
      failures.isEmpty(),
      () -> failures.size() + " subtype view(s) failed:\n  " + String.join("\n  ", failures)
    );
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
    return !element.converts() && family.src().equals(family.tgt());
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
    //
    // The map rows are what cover the two-argument allocation: writing the key and the value the
    // wrong way round emits a file that does not compile, which no row without a map subtype
    // reaches.
    // Running each pairing covers a different mistake, one that compiles and holds the right
    // contents
    // in the wrong container class, which no assertion on the compile alone can see.
    final var failures = new ArrayList<String>();
    var index = 0;
    for (final var pairing : RAW_PAIRINGS) {
      final var prefix = "Rs" + index++;
      final var sources = rawSources(prefix, pairing);
      // Only the compile is guarded, because a processor throwing is the outcome being detected
      // here. A fault anywhere after it belongs to this test, and reporting one as though the
      // processor had thrown is how a broken fixture reads as a product defect.
      final ProcessorHarness.Compilation processed;
      try {
        processed = ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), sources);
      } catch (final RuntimeException e) {
        failures.add(pairing.name() + ": the processor threw " + e.getCause());
        continue;
      }
      try {
        // Every pairing in the table converts, so a refusal is a failure rather than an outcome to
        // record. Letting a diagnosed refusal through would leave the same hole the grid's refusal
        // register closes: the classification these pairings depend on is shared, so a change there
        // turns them all into refusals at once, and a test that accepts a refusal goes quiet
        // exactly
        // when the thing it guards breaks.
        if (!processed.success()) {
          failures.add(pairing.name() + ": refused, where every pairing here converts: " + processed.errorMessages());
          continue;
        }
        final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources);
        assertTrue(plain.success(), () -> pairing.name() + " should compile without the processor");
        final var classes = plain.define(MethodHandles.lookup());
        final var src = classes.get(PACKAGE + "." + prefix + "Src");
        final var tgt = classes.get(PACKAGE + "." + prefix + "Tgt");
        final var source = src.getConstructors()[0].newInstance(rawInput(classes, prefix, pairing));
        final var bridge = emitted(processed, plain, prefix);
        final var produced = bridge.getMethod("forward", src).invoke(null, source);
        final var container = tgt.getMethod("items").invoke(produced);
        final var got = container.getClass().getName() + " " + container;
        final var want =
          pairing.allocates().formatted(PACKAGE + "." + prefix) + " " + pairing.rendered().formatted(prefix, prefix);
        if (!want.equals(got)) failures.add(pairing.name() + ": produced " + got + ", expected " + want);
      } catch (final ReflectiveOperationException e) {
        throw new IllegalStateException(pairing.name() + " could not be built", e);
      }
    }
    assertTrue(
      failures.isEmpty(),
      () -> failures.size() + " raw pairing(s) failed:\n  " + String.join("\n  ", failures)
    );
  }

  /**
   * One raw-subtype pairing: the kind that decides how the input is built, the two declared field
   * types, the class the rebuild has to produce, and how it renders.
   *
   * <p>{@code src} and {@code tgt} take the cell's name prefix wherever they name a generated type.
   */
  private record RawPairing(String name, String kind, String src, String tgt, String allocates, String rendered) {}

  /**
   * One pairing per arm of the allocation and per side of the pair: the single-argument arm through
   * a list, a set and a map subtype, the two-argument arm through {@code Map} and {@code
   * ConcurrentMap}, and each general interface as both source and target, because a raw subtype
   * reaches a different emitter from a parameterized one.
   */
  private static final List<RawPairing> RAW_PAIRINGS = List.of(
    new RawPairing(
      "names -> Collection",
      "list",
      "%sNames",
      "java.util.Collection<%sLeafDto>",
      "java.util.ArrayList",
      "[%sLeafDto[v=b], %sLeafDto[v=a]]"
    ),
    new RawPairing(
      "names -> Deque",
      "list",
      "%sNames",
      "java.util.Deque<%sLeafDto>",
      "java.util.ArrayDeque",
      "[%sLeafDto[v=b], %sLeafDto[v=a]]"
    ),
    new RawPairing(
      "names -> Queue",
      "list",
      "%sNames",
      "java.util.Queue<%sLeafDto>",
      "java.util.ArrayDeque",
      "[%sLeafDto[v=b], %sLeafDto[v=a]]"
    ),
    new RawPairing(
      "names -> List",
      "list",
      "%sNames",
      "java.util.List<%sLeafDto>",
      "java.util.ArrayList",
      "[%sLeafDto[v=b], %sLeafDto[v=a]]"
    ),
    new RawPairing(
      "Collection -> names",
      "list",
      "java.util.Collection<%sLeaf>",
      "%sNamesDto",
      "%sNamesDto",
      "[%sLeafDto[v=b], %sLeafDto[v=a]]"
    ),
    new RawPairing(
      "Deque -> names",
      "list",
      "java.util.Deque<%sLeaf>",
      "%sNamesDto",
      "%sNamesDto",
      "[%sLeafDto[v=b], %sLeafDto[v=a]]"
    ),
    new RawPairing(
      "Queue -> names",
      "list",
      "java.util.Queue<%sLeaf>",
      "%sNamesDto",
      "%sNamesDto",
      "[%sLeafDto[v=b], %sLeafDto[v=a]]"
    ),
    new RawPairing(
      "List -> names",
      "list",
      "java.util.List<%sLeaf>",
      "%sNamesDto",
      "%sNamesDto",
      "[%sLeafDto[v=b], %sLeafDto[v=a]]"
    ),
    new RawPairing(
      "namesSet -> Set",
      "set",
      "%sNamesSet",
      "java.util.Set<%sLeafDto>",
      "java.util.LinkedHashSet",
      "[%sLeafDto[v=b], %sLeafDto[v=a]]"
    ),
    new RawPairing(
      "namesMap -> Map",
      "map",
      "%sNamesMap",
      "java.util.Map<java.lang.String, %sLeafDto>",
      "java.util.LinkedHashMap",
      "{k1=%sLeafDto[v=b], k2=%sLeafDto[v=a]}"
    ),
    new RawPairing(
      "namesMap -> ConcurrentMap",
      "map",
      "%sNamesMap",
      "java.util.concurrent.ConcurrentMap<java.lang.String, %sLeafDto>",
      "java.util.concurrent.ConcurrentHashMap",
      "{k1=%sLeafDto[v=b], k2=%sLeafDto[v=a]}"
    )
  );

  private static JavaFileObject[] rawSources(final String prefix, final RawPairing pairing) {
    final var head = "package " + PACKAGE + ";\n";
    return new JavaFileObject[] {
      source(prefix + "Leaf", head + "public record " + prefix + "Leaf(String v) {}\n"),
      source(prefix + "LeafDto", head + "public record " + prefix + "LeafDto(String v) {}\n"),
      source(
        prefix + "Names",
        head + "public class " + prefix + "Names extends java.util.ArrayList<" + prefix + "Leaf> {}\n"
      ),
      source(
        prefix + "NamesDto",
        head + "public class " + prefix + "NamesDto extends java.util.ArrayList<" + prefix + "LeafDto> {}\n"
      ),
      source(
        prefix + "NamesSet",
        head + "public class " + prefix + "NamesSet extends java.util.LinkedHashSet<" + prefix + "Leaf> {}\n"
      ),
      source(
        prefix + "NamesMap",
        head +
          "public class " +
          prefix +
          "NamesMap extends java.util.LinkedHashMap<java.lang.String, " +
          prefix +
          "Leaf> {}\n"
      ),
      source(
        prefix + "Src",
        head +
          "import io.github.eschizoid.telescope.annotations.Bridge;\n@Bridge(" +
          prefix +
          "Tgt.class)\npublic record " +
          prefix +
          "Src(" +
          pairing.src().formatted(prefix, prefix) +
          " items) {}\n"
      ),
      source(
        prefix + "Tgt",
        head + "public record " + prefix + "Tgt(" + pairing.tgt().formatted(prefix, prefix) + " items) {}\n"
      ),
    };
  }

  private static Object rawInput(final Map<String, Class<?>> classes, final String prefix, final RawPairing pairing)
    throws ReflectiveOperationException {
    final var leafType = classes.get(PACKAGE + "." + prefix + "Leaf");
    final var leaves = new ArrayList<>();
    for (final var value : VALUES) leaves.add(leafType.getConstructor(String.class).newInstance(value));
    final var declared = pairing.src().formatted(prefix, prefix);
    // A raw-subtype source has to be an instance of that subtype. A general-interface source takes
    // any container the interface admits, which is not the same as any container of the kind: a
    // Deque and a Queue are list-shaped and an ArrayList is neither of them.
    final var raw = declared.contains("<") ? declared.substring(0, declared.indexOf('<')) : declared;
    final Class<?> type = declared.startsWith(prefix)
      ? classes.get(PACKAGE + "." + declared)
      : switch (raw) {
          case "java.util.Deque", "java.util.Queue" -> ArrayDeque.class;
          case "java.util.Set" -> LinkedHashSet.class;
          case "java.util.Map", "java.util.concurrent.ConcurrentMap" -> LinkedHashMap.class;
          default -> ArrayList.class;
        };
    final var instance = type.getConstructor().newInstance();
    if (pairing.kind().equals("map")) {
      final var put = Map.class.getMethod("put", Object.class, Object.class);
      for (var i = 0; i < leaves.size(); i++) put.invoke(instance, "k" + (i + 1), leaves.get(i));
    } else {
      final var add = Collection.class.getMethod("add", Object.class);
      for (final var leaf : leaves) add.invoke(instance, leaf);
    }
    return instance;
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
    if (family.order() == Order.NONE) return java.util.Optional.empty();
    // A sorted family's input is ordered by a reversing comparator, so the rendering it owes is the
    // one that comparator produces; a rebuild that dropped it would render in natural order.
    final var sorted = family.order() == Order.SORTED;
    final var laid =
      sorted && !family.kind().equals("map") ? rendered.stream().sorted(Comparator.reverseOrder()).toList() : rendered;
    final var body = family.kind().equals("map")
      ? (sorted
          ? "{k2=" + laid.get(1) + ", k1=" + laid.get(0) + "}"
          : "{k1=" + laid.get(0) + ", k2=" + laid.get(1) + "}")
      : "[" + laid.get(0) + ", " + laid.get(1) + "]";
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
        element.converts()
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
        // A sorted family's input carries a comparator, which is the only thing a rebuild of one
        // can lose while producing a container of the right class holding the right elements.
        final Collection<Object> set =
          family.order() == Order.SORTED ? new TreeSet<>(REVERSED) : new InputSet<Object>();
        set.addAll(leaves);
        yield set;
      }
      default -> {
        final Map<String, Object> map =
          family.order() == Order.SORTED ? new TreeMap<>(REVERSED) : new InputMap<String, Object>();
        for (var i = 0; i < leaves.size(); i++) map.put("k" + (i + 1), leaves.get(i));
        yield map;
      }
    };
  }

  /**
   * The order a sorted family's input is built in, so that a rebuild which drops the comparator
   * renders differently from one that carries it.
   */
  private static final Comparator<Object> REVERSED = Comparator.comparing(String::valueOf).reversed();

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
    boolean agreesWith(final Outcome other, final Order order) {
      if (refusal != null || other.refusal != null) return refusal != null && other.refusal != null;
      if (!allocated.equals(other.allocated)) return false;
      return order == Order.NONE ? elements.equals(other.elements) : value.equals(other.value);
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
