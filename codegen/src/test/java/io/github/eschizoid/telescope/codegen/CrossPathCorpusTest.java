package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.codegen.ctorbox.BaggedDst;
import io.github.eschizoid.telescope.codegen.ctorbox.GuardedDst;
import io.github.eschizoid.telescope.codegen.ctorpair.BaggedSrc;
import io.github.eschizoid.telescope.codegen.ctorpair.GuardedSrc;
import io.github.eschizoid.telescope.conversion.ForwardMapper;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.mapping.WriteHint;
import io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
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
   * with an element needing no conversion is rebuilt too, unless neither table names its family,
   * and {@link #passesThrough} decides which of the two a cell owes.
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
    // Agreement, not a difference: Iterable is not a Collection subtype, so neither path's
    // classifier gives it a shape, and both refuse it by name.
    "iterable/record",
    new Refusal("has incompatible types", "incompatible source/target shapes"),
    // Agreement, not a difference, in this entry and the three after it: a sorted set whose
    // elements are converted cannot carry the source's comparator, and the converted
    // element does not implement Comparable, so both paths refuse the pairing by name.
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
        final var processed = bridged(sources);

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
          final var nested = nestedBridgesFirst(processed, plain, prefix);
          reflective = run(items, () -> Telescope.mapper(cast(src), cast(tgt)).forward(source));
          final var forward = processed.success()
            ? emitted(processed, plain, prefix, nested).getMethod("forward", src)
            : null;
          generated =
            forward == null
              ? Outcome.refused(processed.errorMessages().strip())
              : run(items, () -> forward.invoke(null, source));
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
   * {@code sources} compiled with the bridge processor, failing the calling test on any warning
   * javac reports in a file the processor wrote. The harness compiles with {@code -Xlint:all}, and
   * a generated file is code its adopter cannot edit, so a build compiling it with {@code -Werror}
   * passes only when the file raises nothing.
   */
  private static ProcessorHarness.Compilation bridged(final JavaFileObject... sources) {
    final var processed = ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), sources);
    final var warnings = warningsInGenerated(processed);
    assertTrue(warnings.isEmpty(), () -> "the generated code warns:\n  " + String.join("\n  ", warnings));
    return processed;
  }

  /** Every warning {@code compilation} reports in a source file a processor generated. */
  private static List<String> warningsInGenerated(final ProcessorHarness.Compilation compilation) {
    final var generatedFiles = compilation
      .generated()
      .keySet()
      .stream()
      .map(name -> "/" + name.replace('.', '/') + ".java")
      .collect(Collectors.toSet());
    final var warnings = new ArrayList<String>();
    for (final var d : compilation.diagnostics()) {
      if (d.getKind() != Diagnostic.Kind.WARNING && d.getKind() != Diagnostic.Kind.MANDATORY_WARNING) continue;
      if (d.getSource() == null || !generatedFiles.contains(d.getSource().getName())) continue;
      warnings.add(d.getSource().getName() + ":" + d.getLineNumber() + ": " + d.getMessage(Locale.ROOT));
    }
    return warnings;
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
    return emitted(processed, plain, prefix, Set.of());
  }

  /**
   * As {@link #emitted(ProcessorHarness.Compilation, ProcessorHarness.Compilation, String)}, past
   * {@code done}.
   */
  private static Class<?> emitted(
    final ProcessorHarness.Compilation processed,
    final ProcessorHarness.Compilation plain,
    final String prefix,
    final Set<String> done
  ) {
    final var bridgeName = PACKAGE + "." + prefix + "SrcBridge";
    final var defined = defineAdded(processed, plain, name -> !done.contains(name));
    final var bridge = defined.get(bridgeName);
    if (bridge == null) throw new IllegalStateException("the processor emitted no bridge, only " + defined.keySet());
    return bridge;
  }

  /**
   * Define every bridge the processor's compile added except the cell's own, so a runtime mapper
   * built next finds each nested pair's own bridge as it would in a real build, and hand back their
   * names for {@link #emitted(ProcessorHarness.Compilation, ProcessorHarness.Compilation, String,
   * Set)} to pass over. A compile the processor refused added nothing. The cell's own bridge comes
   * after the runtime mapper, so a mapper that would route through a bridge beside its source
   * cannot be the generated path a second time.
   */
  private static Set<String> nestedBridgesFirst(
    final ProcessorHarness.Compilation processed,
    final ProcessorHarness.Compilation plain,
    final String prefix
  ) {
    return processed.success() ? definedExceptOwn(processed, plain, prefix) : Set.of();
  }

  /**
   * Define every class the processor's compile added over the plain one except the cell's own
   * bridge and the classes nested in it, and hand back the names defined.
   */
  private static Set<String> definedExceptOwn(
    final ProcessorHarness.Compilation processed,
    final ProcessorHarness.Compilation plain,
    final String prefix
  ) {
    final var own = PACKAGE + "." + prefix + "SrcBridge";
    return defineAdded(processed, plain, name -> !name.equals(own) && !name.startsWith(own + "$")).keySet();
  }

  /**
   * Define the classes the processor's compile added over the plain one that {@code wanted} keeps.
   */
  private static Map<String, Class<?>> defineAdded(
    final ProcessorHarness.Compilation processed,
    final ProcessorHarness.Compilation plain,
    final Predicate<String> wanted
  ) {
    final var added = new LinkedHashMap<>(processed.classes());
    plain.classes().keySet().forEach(added::remove);
    added.keySet().removeIf(wanted.negate());
    return new ProcessorHarness.Compilation(
      processed.success(),
      processed.diagnostics(),
      processed.generated(),
      processed.resources(),
      added
    ).define(MethodHandles.lookup());
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
      final var processed = bridged(sources);

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

      final var nested = nestedBridgesFirst(processed, plain, prefix);
      final var reflective = run(items, () -> Telescope.mapper(cast(src), cast(tgt)).forward(source));
      final var forward = processed.success()
        ? emitted(processed, plain, prefix, nested).getMethod("forward", src)
        : null;
      final var generated =
        forward == null
          ? Outcome.refused(processed.errorMessages().strip())
          : run(items, () -> forward.invoke(null, source));

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
   * Whether the pair is the same type on both sides with an element that needs no conversion, and
   * of a family neither table names, in which case neither path allocates anything and the target
   * holds the source's own container. A same-typed pair of a family the tables name is copied into
   * the class the family allocates, like any other pair.
   *
   * <p>The fixture builds a class no family names, so which of the two a cell did is visible: a
   * same-typed cell expected to copy fails if either path hands the input across, and one expected
   * to pass through fails if either path copies.
   */
  private static boolean passesThrough(final Family family, final Element element) {
    return !element.converts() && family.src().equals(family.tgt()) && family.allocates().isEmpty();
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
        processed = bridged(sources);
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

  /**
   * A sorted source set rebuilt backward from an unsorted target, so the rebuilt set orders its
   * elements by their own {@code compareTo}: a comparator the source carried either orders the type
   * converted away from, or never reaches a target that keeps no order.
   *
   * <p>{@code declarations} are the cell's own types beyond the pair, each a whole top-level
   * declaration; {@code A} is the source element, and {@code B} the converted one where the
   * elements change type. {@code srcItems} and {@code tgtItems} are the two field types, and {@code
   * leaf} is the class the target's elements are built from. A target field whose type is a class
   * is filled through its own no-argument constructor, and one whose type is an interface holds a
   * {@code LinkedHashSet}. When {@code nested} is set, the pair holds the two containers one level
   * down, in records named {@code Inner} and {@code InnerDto}. {@code owed} is what both paths
   * throw, rendered by {@link #thrown}. {@code %s} is the cell's prefix in the declarations and
   * field types, and its qualified prefix in {@code owed}.
   */
  private record SortedBackward(
    String name,
    List<String> declarations,
    String srcItems,
    String tgtItems,
    String leaf,
    boolean nested,
    String owed
  ) {}

  /**
   * The refusal a sorted container gives an element it cannot order, with the cast as its cause.
   */
  private static String unorderable(final String container, final String implementing) {
    return (
      "IllegalStateException: Deep map: " +
      container +
      " keeps its elements in order, and %sA could not be ordered there, " +
      implementing +
      ". Supply an ordering these elements accept through a Mapping.via(...) row, or declare the" +
      " target as a set that keeps no order. The cause is the cast itself. <- ClassCastException"
    );
  }

  private static final String NOT_COMPARABLE = "public record %sA(String v) {}";

  /**
   * An element whose {@code Comparable} declaration names its own kind only through a type variable
   * its subclass binds, and whose {@code compareTo} raises a cast of its own.
   */
  private static final List<String> BOUND_THROUGH_A_VARIABLE = List.of(
    "public abstract class %sCmp<T extends %sCmp<T>> implements Comparable<T> {\n" +
      "  public int compareTo(final T o) { throw new ClassCastException(\"its own\"); }\n}",
    "public final class %sA extends %sCmp<%sA> {\n" +
      "  private String v;\n" +
      "  public %sA() {}\n" +
      "  public %sA(final String v) { this.v = v; }\n" +
      "  public String getV() { return v; }\n" +
      "  public void setV(final String v) { this.v = v; }\n}"
  );

  private static final List<SortedBackward> SORTED_BACKWARD = List.of(
    new SortedBackward(
      "an element that is not Comparable",
      List.of(NOT_COMPARABLE),
      "java.util.SortedSet<%sA>",
      "java.util.Set<%sB>",
      "B",
      false,
      unorderable("java.util.SortedSet", "and its type does not implement Comparable")
    ),
    new SortedBackward(
      "an element ordered against another type",
      List.of(
        "public record %sA(String v) implements Comparable<String> {\n" +
          "  public int compareTo(final String o) { return v.compareTo(o); }\n}"
      ),
      "java.util.SortedSet<%sA>",
      "java.util.Set<%sB>",
      "B",
      false,
      unorderable("java.util.SortedSet", "though its type implements Comparable")
    ),
    // An element ordered against its own kind has said it can be ordered,
    // so a cast escaping its compareTo is its own and propagates as it is.
    new SortedBackward(
      "an element whose own compareTo casts",
      List.of(
        "public record %sA(String v) implements Comparable<%sA> {\n" +
          "  public int compareTo(final %sA o) { throw new ClassCastException(\"its own\"); }\n}"
      ),
      "java.util.SortedSet<%sA>",
      "java.util.Set<%sB>",
      "B",
      false,
      "ClassCastException: its own"
    ),
    new SortedBackward(
      "an element ordered against its own kind through a type variable",
      BOUND_THROUGH_A_VARIABLE,
      "java.util.SortedSet<%sA>",
      "java.util.Set<%sB>",
      "B",
      false,
      "ClassCastException: its own"
    ),
    new SortedBackward(
      "an element copied unchanged that is not Comparable",
      List.of(NOT_COMPARABLE),
      "java.util.SortedSet<%sA>",
      "java.util.Set<%sA>",
      "A",
      false,
      unorderable("java.util.SortedSet", "and its type does not implement Comparable")
    ),
    new SortedBackward(
      "an element copied unchanged whose own compareTo casts",
      BOUND_THROUGH_A_VARIABLE,
      "java.util.SortedSet<%sA>",
      "java.util.Set<%sA>",
      "A",
      false,
      "ClassCastException: its own"
    ),
    new SortedBackward(
      "an element converted between sorted container subtypes",
      List.of(
        NOT_COMPARABLE,
        "public record %sB(String v) implements Comparable<%sB> {\n" +
          "  public int compareTo(final %sB o) { return v.compareTo(o.v()); }\n}",
        "public class %sSorted extends java.util.TreeSet<%sA> {}",
        "public class %sSortedDto extends java.util.TreeSet<%sB> {}"
      ),
      "%sSorted",
      "%sSortedDto",
      "B",
      false,
      unorderable("%sSorted", "and its type does not implement Comparable")
    ),
    new SortedBackward(
      "an element converted one level down",
      List.of(NOT_COMPARABLE),
      "java.util.SortedSet<%sA>",
      "java.util.Set<%sB>",
      "B",
      true,
      unorderable("java.util.SortedSet", "and its type does not implement Comparable")
    ),
    new SortedBackward(
      "an element copied unchanged one level down",
      List.of(NOT_COMPARABLE),
      "java.util.SortedSet<%sA>",
      "java.util.Set<%sA>",
      "A",
      true,
      unorderable("java.util.SortedSet", "and its type does not implement Comparable")
    )
  );

  @Test
  @DisplayName("a sorted source rebuilt backward refuses the same way on both paths")
  void anUnorderableSortedRebuildRefusesTheSameWayOnBothPaths() throws ReflectiveOperationException {
    final var failures = new ArrayList<String>();
    final var declared = Pattern.compile("(?:class|record) (\\w+)");
    var index = 0;
    for (final var shape : SORTED_BACKWARD) {
      final var prefix = "Sb" + index++;
      final var head = "package " + PACKAGE + ";\n";
      final var sources = new ArrayList<JavaFileObject>();
      for (final var declaration : shape.declarations()) {
        final var code = declaration.replace("%s", prefix);
        final var named = declared.matcher(code);
        if (!named.find()) throw new IllegalStateException("no type declared in " + code);
        sources.add(source(named.group(1), head + code + "\n"));
      }
      if (sources.stream().noneMatch(s -> s.getName().endsWith("/" + prefix + "B.java"))) {
        sources.add(source(prefix + "B", head + "public record " + prefix + "B(String v) {}\n"));
      }
      final var srcItems = shape.srcItems().replace("%s", prefix);
      final var tgtItems = shape.tgtItems().replace("%s", prefix);
      final var srcHolds = shape.nested() ? prefix + "Inner" : srcItems;
      final var tgtHolds = shape.nested() ? prefix + "InnerDto" : tgtItems;
      if (shape.nested()) {
        sources.add(source(prefix + "Inner", head + "public record " + prefix + "Inner(" + srcItems + " items) {}\n"));
        sources.add(
          source(prefix + "InnerDto", head + "public record " + prefix + "InnerDto(" + tgtItems + " items) {}\n")
        );
      }
      sources.add(
        source(
          prefix + "Src",
          head +
            "import io.github.eschizoid.telescope.annotations.Bridge;\n@Bridge(" +
            prefix +
            "Tgt.class)\npublic record " +
            prefix +
            "Src(" +
            srcHolds +
            " items) {}\n"
        )
      );
      sources.add(source(prefix + "Tgt", head + "public record " + prefix + "Tgt(" + tgtHolds + " items) {}\n"));
      final var compiled = sources.toArray(JavaFileObject[]::new);
      final var plain = ProcessorHarness.compileFully(List.of(), List.of(), compiled);
      assertTrue(plain.success(), () -> shape.name() + " should compile: " + plain.errorMessages());
      final var processed = bridged(compiled);
      assertTrue(processed.success(), () -> shape.name() + " should bridge: " + processed.errorMessages());
      // The refusal is built in generated code, which a consumer may compile with every lint on and
      // warnings as errors, so it has to raise none of its own.
      final var warnings = processed
        .diagnostics()
        .stream()
        .filter(d -> d.getKind() == Diagnostic.Kind.WARNING || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
        .filter(d -> d.getSource() != null && d.getSource().getName().endsWith("Bridge.java"))
        .map(d -> d.getMessage(null))
        .toList();
      if (!warnings.isEmpty()) failures.add(shape.name() + ": the bridge raised " + warnings);

      final var classes = plain.define(MethodHandles.lookup());
      final var src = classes.get(PACKAGE + "." + prefix + "Src");
      final var tgt = classes.get(PACKAGE + "." + prefix + "Tgt");
      final var leaf = classes.get(PACKAGE + "." + prefix + shape.leaf()).getConstructor(String.class);
      final var container = classes.getOrDefault(PACKAGE + "." + tgtItems, LinkedHashSet.class);
      @SuppressWarnings("unchecked")
      final var items = (Collection<Object>) container.getConstructor().newInstance();
      for (final var value : VALUES) items.add(leaf.newInstance(value));
      final var held = shape.nested()
        ? classes.get(PACKAGE + "." + prefix + "InnerDto").getConstructors()[0].newInstance(items)
        : items;
      final var target = tgt.getConstructors()[0].newInstance(held);
      final var nested = nestedBridgesFirst(processed, plain, prefix);
      final var reflective = thrown(() -> Telescope.mapper(cast(src), cast(tgt)).backward(target));
      final var backward = emitted(processed, plain, prefix, nested).getMethod("backward", tgt);

      final var owed = shape.owed().replace("%s", PACKAGE + "." + prefix);
      final var generated = thrown(() -> backward.invoke(null, target));
      if (!owed.equals(generated)) failures.add(shape.name() + ": generated threw " + generated);
      if (!owed.equals(reflective)) failures.add(shape.name() + ": reflective threw " + reflective);
    }
    assertTrue(failures.isEmpty(), () -> String.join("\n  ", failures));
  }

  /**
   * Two container classes that each fix their own element type, which no element copy takes: they
   * differ in kind, or one of them cannot be allocated. What each class declares, and what both
   * paths owe in each direction — the target's rendering, held in the class the field declares, or
   * a fragment of each path's refusal.
   *
   * <p>{@code %s} is the cell's prefix, and the two declarations name the classes {@code %sSA} and
   * {@code %sTB}. A container's elements are no property of it, so such a pair either converts each
   * element or is refused by name. Rebuilding either side as a bean would hold no elements at all,
   * which is what every row is here to rule out: the source is never empty, so neither is a
   * converted target. {@code Leaf} and {@code LeafDto} are both {@code Comparable}, so a sorted
   * side can hold either; {@code Plain} is not.
   */
  private record ContainerClassPair(
    String name,
    String srcDeclaration,
    String tgtDeclaration,
    String forward,
    String backward,
    Refusal refused
  ) {}

  private static ContainerClassPair converts(
    final String name,
    final String srcExtends,
    final String tgtExtends,
    final String forward,
    final String backward
  ) {
    return new ContainerClassPair(
      name,
      subtype("SA", srcExtends, ""),
      subtype("TB", tgtExtends, ""),
      forward,
      backward,
      null
    );
  }

  private static ContainerClassPair refuses(
    final String name,
    final String srcExtends,
    final String tgtExtends,
    final Refusal refused
  ) {
    return new ContainerClassPair(
      name,
      subtype("SA", srcExtends, ""),
      subtype("TB", tgtExtends, ""),
      null,
      null,
      refused
    );
  }

  private static ContainerClassPair refusesDeclared(
    final String name,
    final String srcDeclaration,
    final String tgtDeclaration,
    final Refusal refused
  ) {
    return new ContainerClassPair(name, srcDeclaration, tgtDeclaration, null, null, refused);
  }

  private static final Refusal DIFFERENT_SHAPES = new Refusal(
    "has incompatible types",
    "incompatible source/target shapes"
  );

  private static final Refusal UNORDERABLE_KEY = new Refusal("a sorted map whose key", "a sorted map whose key");

  private static final Refusal NOT_ALLOCABLE = new Refusal(
    "has no no-argument constructor a rebuild can call",
    "has no no-argument constructor a rebuild can call"
  );

  /**
   * A collection that is neither a list, a set nor a queue, keeping its elements in a {@code
   * values} bean property. {@code %s} is the class's own name.
   */
  private static final String VALUES_BACKED =
    "  private java.util.List<String> values = new java.util.ArrayList<>();\n" +
    "  public %s() {}\n" +
    "  public java.util.List<String> getValues() { return values; }\n" +
    "  public void setValues(final java.util.List<String> values) { this.values = values; }\n" +
    "  @Override public java.util.Iterator<String> iterator() { return values.iterator(); }\n" +
    "  @Override public int size() { return values.size(); }\n" +
    "  @Override public boolean add(final String value) { return values.add(value); }\n";

  /** The same collection with a bean property beside its elements. */
  private static final String VALUES_BACKED_TITLED =
    VALUES_BACKED +
    "  private String title;\n" +
    "  public String getTitle() { return title; }\n" +
    "  public void setTitle(final String title) { this.title = title; }\n";

  /** A constructor taking only a capacity, which is all a class declaring it can be built with. */
  private static final String CAPACITY_ONLY = "  public %s(final int capacity) { super(capacity); }\n";

  private static final List<ContainerClassPair> CONTAINER_CLASS_PAIRS = List.of(
    converts(
      "a sorted set into a set that keeps no order",
      "java.util.TreeSet<%sLeaf>",
      "java.util.LinkedHashSet<%sLeafDto>",
      "%sTgt[items=[%sLeafDto[v=a], %sLeafDto[v=b]]]",
      "%sSrc[items=[%sLeaf[v=a], %sLeaf[v=b]]]"
    ),
    converts(
      "a set that keeps no order into a sorted set",
      "java.util.LinkedHashSet<%sLeaf>",
      "java.util.TreeSet<%sLeafDto>",
      "%sTgt[items=[%sLeafDto[v=a], %sLeafDto[v=b]]]",
      "%sSrc[items=[%sLeaf[v=a], %sLeaf[v=b]]]"
    ),
    converts(
      "a sorted set into a set that keeps no order, elements unchanged",
      "java.util.TreeSet<String>",
      "java.util.LinkedHashSet<String>",
      "%sTgt[items=[a, b]]",
      "%sSrc[items=[a, b]]"
    ),
    converts(
      "a sorted map into a map that keeps insertion order",
      "java.util.TreeMap<String, %sLeaf>",
      "java.util.LinkedHashMap<String, %sLeafDto>",
      "%sTgt[items={a=%sLeafDto[v=a], b=%sLeafDto[v=b]}]",
      "%sSrc[items={a=%sLeaf[v=a], b=%sLeaf[v=b]}]"
    ),
    converts(
      "a map that keeps insertion order into a sorted map",
      "java.util.LinkedHashMap<String, %sLeaf>",
      "java.util.TreeMap<String, %sLeafDto>",
      "%sTgt[items={a=%sLeafDto[v=a], b=%sLeafDto[v=b]}]",
      "%sSrc[items={a=%sLeaf[v=a], b=%sLeaf[v=b]}]"
    ),
    refuses(
      "a set into a sorted set of an element that is not Comparable",
      "java.util.LinkedHashSet<%sLeaf>",
      "java.util.TreeSet<%sPlain>",
      new Refusal("does not implement Comparable", "does not implement Comparable")
    ),
    refuses(
      "a sorted set into a list",
      "java.util.TreeSet<%sLeaf>",
      "java.util.ArrayList<%sLeafDto>",
      DIFFERENT_SHAPES
    ),
    refuses(
      "a set into a map",
      "java.util.LinkedHashSet<%sLeaf>",
      "java.util.LinkedHashMap<String, %sLeafDto>",
      DIFFERENT_SHAPES
    ),
    refuses("a deque into a list", "java.util.ArrayDeque<%sLeaf>", "java.util.LinkedList<%sLeafDto>", DIFFERENT_SHAPES),
    refuses(
      "a map into a sorted map over keys that are not Comparable",
      "java.util.LinkedHashMap<%sPlain, %sLeaf>",
      "java.util.TreeMap<%sPlain, %sLeafDto>",
      UNORDERABLE_KEY
    ),
    refuses(
      "a map into a sorted map over keys that are not Comparable, values unchanged",
      "java.util.LinkedHashMap<%sPlain, String>",
      "java.util.TreeMap<%sPlain, String>",
      UNORDERABLE_KEY
    ),
    // An element carried across unchanged is let through when the pair is built,
    // since a sorted source can hand its comparator across with it. Where nothing
    // orders it, it is refused by name when it is inserted.
    refuses(
      "a set into a sorted set of an element carried unchanged that is not Comparable",
      "java.util.LinkedHashSet<%sPlain>",
      "java.util.TreeSet<%sPlain>",
      new Refusal("could not be ordered there", "could not be ordered there")
    ),
    // A collection that names no shape has no container view, so no lift can
    // rebuild it. It is a container, so it is not decomposed as a bean either: its
    // elements are not what its properties describe, and a class that adds a
    // property of its own beside them shows why. Both paths refuse the pair by name.
    refusesDeclared(
      "a collection that is neither a list, a set nor a queue",
      subtype("SA", "java.util.AbstractCollection<String>", VALUES_BACKED),
      subtype("TB", "java.util.AbstractCollection<String>", VALUES_BACKED),
      DIFFERENT_SHAPES
    ),
    refusesDeclared(
      "a collection that is neither a list, a set nor a queue, with a property of its own",
      subtype("SA", "java.util.AbstractCollection<String>", VALUES_BACKED_TITLED),
      subtype("TB", "java.util.AbstractCollection<String>", VALUES_BACKED_TITLED),
      DIFFERENT_SHAPES
    ),
    // The same kind and the same elements, which an element copy would take if
    // both classes could be allocated. Either side has to be: the forward rebuild
    // allocates the target, and the backward one the source.
    refusesDeclared(
      "a list into a class that takes only a capacity",
      subtype("SA", "java.util.ArrayList<String>", ""),
      subtype("TB", "java.util.ArrayList<String>", CAPACITY_ONLY),
      NOT_ALLOCABLE
    ),
    refusesDeclared(
      "a list from a class that takes only a capacity",
      subtype("SA", "java.util.ArrayList<String>", CAPACITY_ONLY),
      subtype("TB", "java.util.ArrayList<String>", ""),
      NOT_ALLOCABLE
    ),
    refusesDeclared(
      "a map from a class that takes only a capacity",
      subtype("SA", "java.util.HashMap<String, String>", CAPACITY_ONLY),
      subtype("TB", "java.util.HashMap<String, String>", ""),
      NOT_ALLOCABLE
    ),
    // A class only its own package can allocate is allocated by a bridge generated into that
    // package, and by the runtime, which reaches every package.
    new ContainerClassPair(
      "a list from a class only its own package can allocate",
      subtype("SA", "java.util.ArrayList<String>", "").substring("public ".length()),
      subtype("TB", "java.util.ArrayList<String>", ""),
      "%sTgt[items=[b, a]]",
      "%sSrc[items=[b, a]]",
      null
    ),
    refusesDeclared(
      "a list from a class whose no-argument constructor is private",
      subtype("SA", "java.util.ArrayList<String>", "  private %s() {}\n"),
      subtype("TB", "java.util.ArrayList<String>", ""),
      NOT_ALLOCABLE
    )
  );

  @Test
  @DisplayName("two container classes no element copy takes convert each element or refuse, the same way on both paths")
  void containerClassesConvertOrRefuseOnBothPaths() throws ReflectiveOperationException {
    final var failures = new ArrayList<String>();
    var index = 0;
    for (final var shape : CONTAINER_CLASS_PAIRS) {
      final var prefix = "Kc" + index++;
      final var head = "package " + PACKAGE + ";\n";
      final var srcDeclaration = shape.srcDeclaration().replace("%s", prefix);
      final var sources = new JavaFileObject[] {
        source(prefix + "Leaf", head + comparableRecord(prefix + "Leaf")),
        source(prefix + "LeafDto", head + comparableRecord(prefix + "LeafDto")),
        source(prefix + "Plain", head + "public record " + prefix + "Plain(String v) {}\n"),
        source(prefix + "SA", head + srcDeclaration),
        source(prefix + "TB", head + shape.tgtDeclaration().replace("%s", prefix)),
        source(
          prefix + "Src",
          head +
            "import io.github.eschizoid.telescope.annotations.Bridge;\n@Bridge(" +
            prefix +
            "Tgt.class)\npublic record " +
            prefix +
            "Src(" +
            prefix +
            "SA items) {}\n"
        ),
        source(prefix + "Tgt", head + "public record " + prefix + "Tgt(" + prefix + "TB items) {}\n"),
      };
      final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources);
      assertTrue(plain.success(), () -> shape.name() + " should compile: " + plain.errorMessages());
      final var processed = bridged(sources);

      final var classes = plain.define(MethodHandles.lookup());
      final var src = classes.get(PACKAGE + "." + prefix + "Src");
      final var tgt = classes.get(PACKAGE + "." + prefix + "Tgt");
      final var sa = classes.get(PACKAGE + "." + prefix + "SA");
      // Looked up as declared rather than as public, and opened: the source of a row may be a class
      // only this package can allocate, or one whose constructor is private.
      final var ctor = srcDeclaration.contains("final int capacity")
        ? sa.getDeclaredConstructor(int.class)
        : sa.getDeclaredConstructor();
      ctor.setAccessible(true);
      final var input = srcDeclaration.contains("final int capacity")
        ? ctor.newInstance(VALUES.size())
        : ctor.newInstance();
      final var leaf = srcDeclaration.contains(prefix + "Leaf>")
        ? classes.get(PACKAGE + "." + prefix + "Leaf").getConstructor(String.class)
        : srcDeclaration.contains(prefix + "Plain>")
          ? classes.get(PACKAGE + "." + prefix + "Plain").getConstructor(String.class)
          : null;
      final var key = srcDeclaration.contains(prefix + "Plain,")
        ? classes.get(PACKAGE + "." + prefix + "Plain").getConstructor(String.class)
        : null;
      for (final var value : VALUES) {
        final var element = leaf == null ? value : leaf.newInstance(value);
        if (input instanceof Map<?, ?>) {
          final var k = key == null ? value : key.newInstance(value);
          Map.class.getMethod("put", Object.class, Object.class).invoke(input, k, element);
        } else {
          Collection.class.getMethod("add", Object.class).invoke(input, element);
        }
      }
      final var source = src.getConstructors()[0].newInstance(input);

      final var nested = nestedBridgesFirst(processed, plain, prefix);
      final var reflective = run(tgt.getMethod("items"), () -> Telescope.mapper(cast(src), cast(tgt)).forward(source));
      final var reflectiveBack = run(src.getMethod("items"), () -> {
        final var mapper = Telescope.mapper(cast(src), cast(tgt));
        return mapper.backward(mapper.forward(source));
      });
      final Outcome generated;
      final Outcome generatedBack;
      if (processed.success()) {
        final var bridge = emitted(processed, plain, prefix, nested);
        final var forward = bridge.getMethod("forward", src);
        final var backward = bridge.getMethod("backward", tgt);
        generated = run(tgt.getMethod("items"), () -> forward.invoke(null, source));
        generatedBack = run(src.getMethod("items"), () -> backward.invoke(null, forward.invoke(null, source)));
      } else {
        generated = Outcome.refused(processed.errorMessages().strip());
        generatedBack = generated;
      }

      if (shape.refused() != null) {
        if (generated.refusal() == null || !generated.refusal().contains(shape.refused().generatedSays())) {
          failures.add(shape.name() + ": generated gave " + generated);
        }
        if (reflective.refusal() == null || !reflective.refusal().contains(shape.refused().reflectiveSays())) {
          failures.add(shape.name() + ": reflective gave " + reflective);
        }
        continue;
      }
      final var forwardOwed = shape.forward().replace("%s", prefix) + " in " + PACKAGE + "." + prefix + "TB";
      final var backwardOwed = shape.backward().replace("%s", prefix) + " in " + PACKAGE + "." + prefix + "SA";
      for (final var side : List.of(
        Map.entry("generated forward", Map.entry(generated, forwardOwed)),
        Map.entry("reflective forward", Map.entry(reflective, forwardOwed)),
        Map.entry("generated backward", Map.entry(generatedBack, backwardOwed)),
        Map.entry("reflective backward", Map.entry(reflectiveBack, backwardOwed))
      )) {
        final var outcome = side.getValue().getKey();
        final var owed = side.getValue().getValue();
        if (!owed.equals(outcome.toString())) {
          failures.add(shape.name() + ": " + side.getKey() + " gave " + outcome + ", owed " + owed);
        }
      }
    }
    assertTrue(failures.isEmpty(), () -> failures.size() + " pair(s) failed:\n  " + String.join("\n  ", failures));
  }

  /**
   * A container the shared allocation rules decide how to build: the classes the cell declares
   * beside the pair, the two field types, the expression that builds the source's field, and what
   * both paths owe forward — the target's rendering and the class its field holds, or a fragment of
   * each path's refusal. A converting row also owes the same round trip from both paths.
   *
   * <p>{@code %s} is the cell's prefix throughout, and each declaration sits on a line of its own.
   * {@code Leaf} and {@code LeafDto} are declared for every cell.
   */
  private record Allocated(
    String name,
    String declarations,
    String srcField,
    String tgtField,
    String sample,
    String converted,
    Refusal refused
  ) {}

  private static Allocated allocates(
    final String name,
    final String declarations,
    final String srcField,
    final String tgtField,
    final String sample,
    final String converted
  ) {
    return new Allocated(name, declarations, srcField, tgtField, sample, converted, null);
  }

  private static Allocated refusesAllocation(
    final String name,
    final String declarations,
    final String srcField,
    final String tgtField,
    final String sample,
    final Refusal refused
  ) {
    return new Allocated(name, declarations, srcField, tgtField, sample, null, refused);
  }

  private static final String TWO_LEAVES =
    "new java.util.ArrayList<>(java.util.List.of(new %sLeaf(\"b\"), new %sLeaf(\"a\")))";

  private static final String TWO_STRINGS = "new java.util.ArrayList<>(java.util.List.of(\"b\", \"a\"))";

  /** A generic list subtype whose no-argument constructor has {@code %%s} as its modifiers. */
  private static final String BOX =
    "public class %%sBox<E> extends java.util.ArrayList<E> {" +
    " private static final long serialVersionUID = 1L; %s %%sBox() {} }";

  private static final String COPY_ONLY =
    "public class %sCo<E> extends java.util.ArrayList<E> { private static final long serialVersionUID = 1L;" +
    " public %sCo(final java.util.Collection<? extends E> c) { super(c); } }";

  private static final String DAY = "public enum %sDay { MON, TUE }";

  private static final Refusal NO_INSTANCE = new Refusal("has no instance of its own", "has no instance of its own");

  private static final List<Allocated> ALLOCATIONS = List.of(
    allocates(
      "a class whose no-argument constructor is package-private",
      BOX.formatted(""),
      "java.util.List<%sLeaf>",
      "%sBox<%sLeafDto>",
      TWO_LEAVES,
      "%sTgt[items=[%sLeafDto[v=b], %sLeafDto[v=a]]] in " + PACKAGE + ".%sBox"
    ),
    allocates(
      "a class whose no-argument constructor is protected",
      BOX.formatted("protected"),
      "java.util.List<%sLeaf>",
      "%sBox<%sLeafDto>",
      TWO_LEAVES,
      "%sTgt[items=[%sLeafDto[v=b], %sLeafDto[v=a]]] in " + PACKAGE + ".%sBox"
    ),
    allocates(
      "a class whose no-argument constructor is package-private, elements unchanged",
      BOX.formatted(""),
      "java.util.List<String>",
      "%sBox<String>",
      TWO_STRINGS,
      "%sTgt[items=[b, a]] in " + PACKAGE + ".%sBox"
    ),
    refusesAllocation(
      "a class whose no-argument constructor is private",
      BOX.formatted("private"),
      "java.util.List<%sLeaf>",
      "%sBox<%sLeafDto>",
      TWO_LEAVES,
      NOT_ALLOCABLE
    ),
    // A copy constructor builds a pair whose elements pass through, which is the one
    // thing it can be handed. Where the elements are converted there is nothing to
    // hand it.
    allocates(
      "a class with only a copy constructor, elements unchanged",
      COPY_ONLY,
      "java.util.List<String>",
      "%sCo<String>",
      TWO_STRINGS,
      "%sTgt[items=[b, a]] in " + PACKAGE + ".%sCo"
    ),
    refusesAllocation(
      "a class with only a copy constructor, elements converted",
      COPY_ONLY,
      "java.util.List<%sLeaf>",
      "%sCo<%sLeafDto>",
      TWO_LEAVES,
      NOT_ALLOCABLE
    ),
    // An EnumMap is built from the key class its declaration names. Its copy
    // constructor learns the class from the map it is handed, which an empty map that
    // is not an EnumMap cannot tell it.
    allocates(
      "an EnumMap, values unchanged",
      DAY,
      "java.util.Map<%sDay, String>",
      "java.util.EnumMap<%sDay, String>",
      "new java.util.LinkedHashMap<>(java.util.Map.of(%sDay.TUE, \"b\", %sDay.MON, \"a\"))",
      "%sTgt[items={MON=a, TUE=b}] in java.util.EnumMap"
    ),
    allocates(
      "an EnumMap from an empty map",
      DAY,
      "java.util.Map<%sDay, String>",
      "java.util.EnumMap<%sDay, String>",
      "new java.util.LinkedHashMap<>()",
      "%sTgt[items={}] in java.util.EnumMap"
    ),
    allocates(
      "an EnumMap, values converted",
      DAY,
      "java.util.Map<%sDay, %sLeaf>",
      "java.util.EnumMap<%sDay, %sLeafDto>",
      "new java.util.LinkedHashMap<>(java.util.Map.of(%sDay.MON, new %sLeaf(\"a\")))",
      "%sTgt[items={MON=%sLeafDto[v=a]}] in java.util.EnumMap"
    ),
    allocates(
      "an abstract class whose family default is one of it",
      "",
      "java.util.List<%sLeaf>",
      "java.util.AbstractList<%sLeafDto>",
      TWO_LEAVES,
      "%sTgt[items=[%sLeafDto[v=b], %sLeafDto[v=a]]] in java.util.ArrayList"
    ),
    refusesAllocation(
      "an abstract class whose family default is not one of it",
      "",
      "java.util.List<%sLeaf>",
      "java.util.AbstractSequentialList<%sLeafDto>",
      TWO_LEAVES,
      NO_INSTANCE
    ),
    refusesAllocation(
      "an interface of the adopter's own",
      "public interface %sMine<E> extends java.util.List<E> {}",
      "java.util.List<%sLeaf>",
      "%sMine<%sLeafDto>",
      TWO_LEAVES,
      NO_INSTANCE
    ),
    // A raw EnumMap names no key class, so neither path can build one, and both say
    // so in the shared rules' words rather than one of them calling the pair a shape
    // mismatch.
    refusesAllocation(
      "a raw EnumMap from a raw map",
      "",
      "java.util.Map",
      "java.util.EnumMap",
      "new java.util.LinkedHashMap<>()",
      new Refusal("is built from the class of its keys", "is built from the class of its keys")
    ),
    // The same decisions one container down, where each inner container is rebuilt by
    // its own helper on the generated path and by a nested lift on the reflective
    // one.
    allocates(
      "a list of classes with only a copy constructor, elements unchanged",
      COPY_ONLY,
      "java.util.List<java.util.List<String>>",
      "java.util.List<%sCo<String>>",
      "new java.util.ArrayList<>(java.util.List.of(" + TWO_STRINGS + "))",
      "%sTgt[items=[[b, a]]] in java.util.ArrayList"
    ),
    allocates(
      "a map of classes with only a copy constructor, elements unchanged",
      COPY_ONLY,
      "java.util.Map<String, java.util.List<String>>",
      "java.util.Map<String, %sCo<String>>",
      "new java.util.LinkedHashMap<>(java.util.Map.of(\"k\", " + TWO_STRINGS + "))",
      "%sTgt[items={k=[b, a]}] in java.util.LinkedHashMap"
    ),
    refusesAllocation(
      "a list of classes with only a copy constructor, elements converted",
      COPY_ONLY,
      "java.util.List<java.util.List<%sLeaf>>",
      "java.util.List<%sCo<%sLeafDto>>",
      "new java.util.ArrayList<>(java.util.List.of(" + TWO_LEAVES + "))",
      NOT_ALLOCABLE
    ),
    allocates(
      "a list of classes whose no-argument constructor is package-private",
      BOX.formatted(""),
      "java.util.List<java.util.List<String>>",
      "java.util.List<%sBox<String>>",
      "new java.util.ArrayList<>(java.util.List.of(" + TWO_STRINGS + "))",
      "%sTgt[items=[[b, a]]] in java.util.ArrayList"
    ),
    refusesAllocation(
      "a list of classes whose no-argument constructor is private",
      BOX.formatted("private"),
      "java.util.List<java.util.List<String>>",
      "java.util.List<%sBox<String>>",
      "new java.util.ArrayList<>(java.util.List.of(" + TWO_STRINGS + "))",
      NOT_ALLOCABLE
    ),
    refusesAllocation(
      "a list of an abstract class whose family default is not one of it",
      "",
      "java.util.List<java.util.List<%sLeaf>>",
      "java.util.List<java.util.AbstractSequentialList<%sLeafDto>>",
      "new java.util.ArrayList<>(java.util.List.of(" + TWO_LEAVES + "))",
      NO_INSTANCE
    ),
    // A raw EnumMap on both sides is one type, which passes through as itself: there
    // is nothing to build, so the key class it does not name is never asked for.
    allocates(
      "a raw EnumMap into itself",
      DAY,
      "java.util.EnumMap",
      "java.util.EnumMap",
      "new java.util.EnumMap<>(java.util.Map.of(%sDay.MON, \"a\"))",
      "%sTgt[items={MON=a}] in java.util.EnumMap"
    ),
    // A copied pair one side of which keeps an order: that side is filled by the
    // helper, which reads the order from the value, and the other is copied.
    allocates(
      "a list of copy-only sets into a list of sorted sets",
      "public class %sCs<E> extends java.util.LinkedHashSet<E> { private static final long serialVersionUID = 1L;" +
        " public %sCs(final java.util.Collection<? extends E> c) { super(c); } }",
      "java.util.List<%sCs<String>>",
      "java.util.List<java.util.TreeSet<String>>",
      "new java.util.ArrayList<>(java.util.List.of(new %sCs<>(java.util.List.of(\"b\", \"a\"))))",
      "%sTgt[items=[[a, b]]] in java.util.ArrayList"
    ),
    allocates(
      "a list of EnumMaps from empty maps",
      DAY,
      "java.util.List<java.util.Map<%sDay, String>>",
      "java.util.List<java.util.EnumMap<%sDay, String>>",
      "new java.util.ArrayList<>(java.util.List.of(new java.util.LinkedHashMap<%sDay, String>()))",
      "%sTgt[items=[{}]] in java.util.ArrayList"
    )
  );

  @Test
  @DisplayName("a container is built the same way on both paths, or refused by both in the same words")
  void bothPathsAllocateTheSameContainer() throws ReflectiveOperationException {
    final var failures = new ArrayList<String>();
    var index = 0;
    for (final var row : ALLOCATIONS) {
      final var prefix = "Al" + index++;
      final var head = "package " + PACKAGE + ";\n";
      final var files = new ArrayList<JavaFileObject>();
      files.add(source(prefix + "Leaf", head + "public record " + prefix + "Leaf(String v) {}\n"));
      files.add(source(prefix + "LeafDto", head + "public record " + prefix + "LeafDto(String v) {}\n"));
      for (final var declaration : row.declarations().replace("%s", prefix).split("\n")) {
        if (declaration.isBlank()) continue;
        final var named = Pattern.compile("(?:class|interface|enum) (\\w+)").matcher(declaration);
        if (!named.find()) throw new IllegalStateException(row.name() + " declares nothing it names");
        files.add(source(named.group(1), head + declaration + "\n"));
      }
      files.add(
        source(
          prefix + "Src",
          head +
            "import io.github.eschizoid.telescope.annotations.Bridge;\n@Bridge(" +
            prefix +
            "Tgt.class)\npublic record " +
            prefix +
            "Src(" +
            row.srcField().replace("%s", prefix) +
            " items) {\n  public static " +
            prefix +
            "Src sample() { return new " +
            prefix +
            "Src(" +
            row.sample().replace("%s", prefix) +
            "); }\n}\n"
        )
      );
      files.add(
        source(
          prefix + "Tgt",
          head + "public record " + prefix + "Tgt(" + row.tgtField().replace("%s", prefix) + " items) {}\n"
        )
      );
      final var sources = files.toArray(JavaFileObject[]::new);
      final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources);
      assertTrue(plain.success(), () -> row.name() + " should compile: " + plain.errorMessages());
      final var processed = bridged(sources);

      final var classes = plain.define(MethodHandles.lookup());
      final var src = classes.get(PACKAGE + "." + prefix + "Src");
      final var tgt = classes.get(PACKAGE + "." + prefix + "Tgt");
      final var input = src.getMethod("sample").invoke(null);

      final var nested = nestedBridgesFirst(processed, plain, prefix);
      final var reflective = run(tgt.getMethod("items"), () -> Telescope.mapper(cast(src), cast(tgt)).forward(input));
      final var reflectiveBack = run(src.getMethod("items"), () -> {
        final var mapper = Telescope.mapper(cast(src), cast(tgt));
        return mapper.backward(mapper.forward(input));
      });
      final Outcome generated;
      final Outcome generatedBack;
      if (processed.success()) {
        final var bridge = emitted(processed, plain, prefix, nested);
        final var forward = bridge.getMethod("forward", src);
        final var backward = bridge.getMethod("backward", tgt);
        generated = run(tgt.getMethod("items"), () -> forward.invoke(null, input));
        generatedBack = run(src.getMethod("items"), () -> backward.invoke(null, forward.invoke(null, input)));
      } else {
        generated = Outcome.refused(processed.errorMessages().strip());
        generatedBack = generated;
      }

      if (row.refused() != null) {
        if (generated.refusal() == null || !generated.refusal().contains(row.refused().generatedSays())) {
          failures.add(row.name() + ": generated gave " + generated);
        }
        if (reflective.refusal() == null || !reflective.refusal().contains(row.refused().reflectiveSays())) {
          failures.add(row.name() + ": reflective gave " + reflective);
        }
        continue;
      }
      final var owed = row.converted().replace("%s", prefix);
      if (!owed.equals(generated.toString())) failures.add(
        row.name() + ": generated gave " + generated + ", owed " + owed
      );
      if (!owed.equals(reflective.toString())) {
        failures.add(row.name() + ": reflective gave " + reflective + ", owed " + owed);
      }
      if (generatedBack.refusal() != null || !generatedBack.toString().equals(reflectiveBack.toString())) {
        failures.add(
          row.name() + ": the round trip differs — generated " + generatedBack + ", reflective " + reflectiveBack
        );
      }
    }
    assertTrue(failures.isEmpty(), () -> failures.size() + " row(s) failed:\n  " + String.join("\n  ", failures));
  }

  /**
   * Pairs the generated path refuses and the runtime builds, each with the fragment the generated
   * path refuses it with. A bridge is code in one package, so a constructor only another package
   * can call is out of its reach; the runtime binds the same constructor through a lookup with
   * private access to the class, which asks nothing of the caller's package.
   *
   * <p>An entry keeps its row from failing and nothing else: a row both paths start to agree on
   * fails, so the register cannot outlive what it describes.
   */
  private static final Map<String, String> GENERATED_PATH_LIMITS = Map.of(
    // Inherent: source outside a package cannot call a package-private constructor in it.
    "a package-private constructor in another package",
    "has no no-argument constructor a rebuild can call",
    // Inherent: from another package only a subclass reaches a protected constructor,
    // and that builds the subclass rather than the target.
    "a protected constructor in another package",
    "has no no-argument constructor a rebuild can call"
  );

  /**
   * One registered limit, as real files: the container and the target holding it in one package,
   * the source in another, and what the runtime builds from a source holding {@code b, a}.
   */
  private record Limit(String name, List<String> fixtures, Callable<Object> reflective, String owed) {}

  @Test
  @DisplayName("a pair out of the generated path's reach is a registered limit of it, which the runtime passes")
  void aPairOutOfTheGeneratedPathsReachIsARegisteredLimit() throws Exception {
    final var limits = List.of(
      new Limit(
        "a package-private constructor in another package",
        List.of("ctorbox/PackageBag", "ctorbox/BaggedDst", "ctorpair/BaggedSrc"),
        () -> Telescope.mapper(BaggedSrc.class, BaggedDst.class).forward(new BaggedSrc(List.of("b", "a"))).items(),
        "[b, a] in " + PACKAGE + ".ctorbox.PackageBag"
      ),
      new Limit(
        "a protected constructor in another package",
        List.of("ctorbox/ProtectedBag", "ctorbox/GuardedDst", "ctorpair/GuardedSrc"),
        () -> Telescope.mapper(GuardedSrc.class, GuardedDst.class).forward(new GuardedSrc(List.of("b", "a"))).items(),
        "[b, a] in " + PACKAGE + ".ctorbox.ProtectedBag"
      )
    );
    final var fixtures = Path.of("src/test/java/io/github/eschizoid/telescope/codegen");
    final var checked = new LinkedHashSet<String>();
    for (final var limit : limits) {
      final var sources = new ArrayList<JavaFileObject>();
      for (final var fixture : limit.fixtures()) {
        final var code = Files.readString(fixtures.resolve(fixture + ".java"));
        sources.add(ProcessorHarness.source(PACKAGE + "." + fixture.replace('/', '.'), code));
      }
      final var generated = bridged(sources.toArray(JavaFileObject[]::new));
      final var refusal = GENERATED_PATH_LIMITS.get(limit.name());
      assertTrue(
        !generated.success() && generated.hasError(refusal),
        () -> limit.name() + ": the generated path should refuse it by name; saw " + generated.errorMessages()
      );
      final var items = limit.reflective().call();
      assertTrue(
        limit.owed().equals(items + " in " + items.getClass().getName()),
        () -> limit.name() + ": the runtime should build it; gave " + items
      );
      checked.add(limit.name());
    }
    // A register entry nothing here exercises is a note about something nobody checks.
    assertTrue(
      checked.equals(GENERATED_PATH_LIMITS.keySet()),
      () -> "registered limits with no row: " + GENERATED_PATH_LIMITS.keySet()
    );
  }

  /** A record of one string, ordered by it. */
  private static String comparableRecord(final String name) {
    return (
      "public record " +
      name +
      "(String v) implements Comparable<" +
      name +
      "> {\n  public int compareTo(final " +
      name +
      " o) { return v.compareTo(o.v()); }\n}\n"
    );
  }

  /**
   * A public class named {@code %s} followed by {@code suffix}, declaring no type parameters and
   * fixing its supertype's, with {@code members} as its body beside the serial version. A {@code
   * %s} in the members is the class's own name.
   */
  private static String subtype(final String suffix, final String supertype, final String members) {
    return (
      "public class %s" +
      suffix +
      " extends " +
      supertype +
      " {\n  private static final long serialVersionUID = 1L;\n" +
      members.replace("%s", "%s" + suffix) +
      "}\n"
    );
  }

  /**
   * What an attempt throws, as its simple class name and message followed by the class of each
   * cause after {@code <-}, or a note that it threw nothing.
   */
  private static String thrown(final Attempt attempt) {
    try {
      attempt.get();
      return "nothing";
    } catch (final InvocationTargetException e) {
      return rendered(e.getCause());
    } catch (final ReflectiveOperationException | RuntimeException e) {
      return rendered(e);
    }
  }

  /**
   * A set that yields a different sequence of elements on each pass over it, the last sequence
   * repeating. Its size is that of the first.
   */
  private static final class ShiftingSet extends AbstractSet<Object> {

    private final List<List<Object>> passes;
    private int pass;

    ShiftingSet(final List<List<Object>> passes) {
      this.passes = passes;
    }

    @Override
    public Iterator<Object> iterator() {
      return passes.get(Math.min(pass++, passes.size() - 1)).iterator();
    }

    @Override
    public int size() {
      return passes.getFirst().size();
    }
  }

  @Test
  @DisplayName("a sorted rebuild from a source that does not yield the same elements twice refuses on both paths")
  void aSortedRebuildFromAShiftingSourceRefusesOnBothPaths() throws ReflectiveOperationException {
    final var head = "package " + PACKAGE + ";\n";
    // Base cannot be ordered, and Sub can be ordered against a Base:
    // a Sub alone fills a sorted set, and a Base inserted after it
    // fails on the cast to Comparable.
    final var sources = new JavaFileObject[] {
      source("SsBase", head + "public class SsBase {}\n"),
      source(
        "SsSub",
        head +
          "public class SsSub extends SsBase implements Comparable<SsBase> {\n" +
          "  public int compareTo(final SsBase o) { return 0; }\n}\n"
      ),
      source(
        "SsSrc",
        head +
          "import io.github.eschizoid.telescope.annotations.Bridge;\n@Bridge(SsTgt.class)\n" +
          "public record SsSrc(java.util.SortedSet<SsBase> items) {}\n"
      ),
      source("SsTgt", head + "public record SsTgt(java.util.Set<SsBase> items) {}\n"),
    };
    final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources);
    assertTrue(plain.success(), () -> "the pair should compile: " + plain.errorMessages());
    final var processed = bridged(sources);
    assertTrue(processed.success(), () -> "the pair should bridge: " + processed.errorMessages());
    final var classes = plain.define(MethodHandles.lookup());
    final var src = classes.get(PACKAGE + ".SsSrc");
    final var tgt = classes.get(PACKAGE + ".SsTgt");
    final var base = classes.get(PACKAGE + ".SsBase").getConstructor();
    final var sub = classes.get(PACKAGE + ".SsSub").getConstructor();
    final var nested = nestedBridgesFirst(processed, plain, "Ss");
    final var mapper = Telescope.mapper(cast(src), cast(tgt));
    final var backward = emitted(processed, plain, "Ss", nested).getMethod("backward", tgt);

    final var refusal = "IllegalStateException: Deep map: java.util.SortedSet keeps its elements in order, and ";
    final var cause = " <- ClassCastException";
    final var failures = new ArrayList<String>();
    final Map<String, List<List<Object>>> shapes = new LinkedHashMap<>();
    shapes.put("a source whose second pass yields nothing", List.of(List.of(base.newInstance()), List.of()));
    final var kept = sub.newInstance();
    shapes.put(
      "a source whose unorderable element is gone on the second pass",
      List.of(List.of(kept, base.newInstance()), List.of(kept))
    );
    for (final var shape : shapes.entrySet()) {
      final var forGenerated = tgt.getConstructors()[0].newInstance(new ShiftingSet(shape.getValue()));
      final var forReflective = tgt.getConstructors()[0].newInstance(new ShiftingSet(shape.getValue()));
      final var generated = thrown(() -> backward.invoke(null, forGenerated));
      final var reflective = thrown(() -> mapper.backward(forReflective));
      if (!generated.startsWith(refusal) || !generated.contains(cause)) {
        failures.add(shape.getKey() + ": generated threw " + generated);
      }
      if (!reflective.startsWith(refusal) || !reflective.contains(cause)) {
        failures.add(shape.getKey() + ": reflective threw " + reflective);
      }
    }
    assertTrue(failures.isEmpty(), () -> String.join("\n  ", failures));
  }

  /**
   * A throwable as its simple class name and message, and each of its causes as its class alone.
   * HotSpot replaces an implicit exception that compiled code throws often with a preallocated one
   * whose message is null, so a cause's message can differ from one call to the next while its
   * class cannot.
   */
  private static String rendered(final Throwable thrown) {
    final var out = new StringBuilder(thrown.getClass().getSimpleName()).append(": ").append(thrown.getMessage());
    for (var cause = thrown.getCause(); cause != null; cause = cause.getCause()) {
      out.append(" <- ").append(cause.getClass().getSimpleName());
    }
    return out.toString();
  }

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

  /**
   * One container pairing whose element or key types are found somewhere other than a plain
   * argument list: on the supertype of a class that declares no type parameters, nowhere at all for
   * a generic class used raw, or inside a parameterized map key. It carries the two declared field
   * types, what each input element is built as, and what both paths owe.
   *
   * <p>{@code %1$s} is the cell's prefix and {@code %2$s} the same prefix qualified by the package,
   * for the class a rebuilt container is reported as. {@code input} is {@code str} for the element
   * values as strings, {@code leaf} for each as a record, {@code leafList} and {@code leafOpt} for
   * each record inside a list or an optional, {@code strList} for each string inside a list, {@code
   * leafListKey} for each record under a map key that is a one-element list, {@code strReversed}
   * for the strings in a sorted source ordered by a reversing comparator, and {@code genList} or
   * {@code objectList} for each string inside its own raw {@code Gen} or {@code ObjectList}. An
   * owed value of {@link #REFUSED} and a fragment of the runtime's message means both paths refuse.
   */
  private record FixedPairing(String name, String src, String tgt, String input, String owed) {}

  /**
   * The classes a fixed pairing may name, by simple name without the prefix. A cell declares only
   * those its two fields mention.
   */
  private static final Map<String, String> FIXED_DECLARATIONS = Map.ofEntries(
    Map.entry("StrList", "extends java.util.ArrayList<String>"),
    Map.entry("StrList2", "extends java.util.ArrayList<String>"),
    Map.entry("LeafList", "extends java.util.ArrayList<%1$sLeaf>"),
    Map.entry("LeafDtoList", "extends java.util.ArrayList<%1$sLeafDto>"),
    Map.entry("Tagged", "<T, E> extends java.util.ArrayList<E>"),
    Map.entry("StrSet", "extends java.util.LinkedHashSet<String>"),
    Map.entry("StrSet2", "extends java.util.LinkedHashSet<String>"),
    Map.entry("LeafSet", "extends java.util.LinkedHashSet<%1$sLeaf>"),
    Map.entry("LeafDtoSet", "extends java.util.LinkedHashSet<%1$sLeafDto>"),
    Map.entry("GSet", "<T, E> extends java.util.LinkedHashSet<E>"),
    Map.entry("StrMap", "extends java.util.LinkedHashMap<String, String>"),
    Map.entry("StrMap2", "extends java.util.LinkedHashMap<String, String>"),
    Map.entry("LeafMap", "extends java.util.LinkedHashMap<String, %1$sLeaf>"),
    Map.entry("LeafDtoMap", "extends java.util.LinkedHashMap<String, %1$sLeafDto>"),
    Map.entry("GMap", "<T, V> extends java.util.LinkedHashMap<String, V>"),
    Map.entry("StrTree", "extends java.util.TreeSet<String>"),
    Map.entry("StrTree2", "extends java.util.TreeSet<String>"),
    Map.entry("LeafTree", "extends java.util.TreeSet<%1$sLeaf>"),
    Map.entry("LeafDtoTree", "extends java.util.TreeSet<%1$sLeafDto>"),
    Map.entry("LeafTreeMap", "extends java.util.TreeMap<String, %1$sLeaf>"),
    Map.entry("LeafDtoTreeMap", "extends java.util.TreeMap<String, %1$sLeafDto>"),
    Map.entry("Groups", "extends java.util.ArrayList<java.util.List<%1$sLeaf>>"),
    Map.entry("MapOfLists", "extends java.util.LinkedHashMap<String, java.util.List<%1$sLeaf>>"),
    Map.entry("OptList", "extends java.util.ArrayList<java.util.Optional<%1$sLeaf>>"),
    Map.entry("WildMap", "extends java.util.LinkedHashMap<String, java.util.List<?>>"),
    Map.entry("Gen", "<E> extends java.util.ArrayList<E>"),
    Map.entry("Gen2", "<E> extends java.util.ArrayList<E>"),
    Map.entry(
      "GTree",
      "<E> extends java.util.TreeSet<E> {\n" +
        "  public %1$sGTree() {}\n" +
        "  %1$sGTree(final java.util.Comparator<? super E> c) { super(c); }\n}"
    ),
    Map.entry("ObjectList", "extends java.util.ArrayList<Object>"),
    Map.entry("ObjectMap", "extends java.util.LinkedHashMap<Object, Object>"),
    Map.entry(
      "ObjectTree",
      "extends java.util.TreeSet<Object> {\n" +
        "  public %1$sObjectTree() {}\n" +
        "  public %1$sObjectTree(final java.util.Comparator<Object> c) { super(c); }\n}"
    ),
    Map.entry(
      "ObjectTreeMap",
      "extends java.util.TreeMap<Object, Object> {\n" +
        "  public %1$sObjectTreeMap() {}\n" +
        "  public %1$sObjectTreeMap(final java.util.Comparator<Object> c) { super(c); }\n}"
    ),
    Map.entry("ListKeyLeafMap", "extends java.util.LinkedHashMap<java.util.List<String>, %1$sLeaf>"),
    Map.entry("ListKeyLeafDtoMap", "extends java.util.LinkedHashMap<java.util.List<String>, %1$sLeafDto>")
  );

  private static final String DTOS = "[%1$sLeafDto[v=b], %1$sLeafDto[v=a]]";
  private static final String DTO_MAP = "{k1=%1$sLeafDto[v=b], k2=%1$sLeafDto[v=a]}";
  private static final String SORTED_DTOS = "[%1$sLeafDto[v=a], %1$sLeafDto[v=b]]";
  private static final String LIST_KEYED_DTOS = "{[k1]=%1$sLeafDto[v=b], [k2]=%1$sLeafDto[v=a]}";
  private static final String REFUSED = "refused: ";

  /**
   * A class declaring no type parameters against an interface, a JDK class, a generic subtype and
   * another such class, for a list, a set, a map and the sorted families, with scalar and record
   * elements, and with fixed arguments that are themselves parameterized.
   */
  private static final List<FixedPairing> FIXED_PAIRINGS = List.of(
    new FixedPairing(
      "StrList -> List",
      "%1$sStrList",
      "java.util.List<String>",
      "str",
      "[b, a] in java.util.ArrayList"
    ),
    new FixedPairing("List -> StrList", "java.util.List<String>", "%1$sStrList", "str", "[b, a] in %2$sStrList"),
    new FixedPairing(
      "LeafList -> List",
      "%1$sLeafList",
      "java.util.List<%1$sLeafDto>",
      "leaf",
      DTOS + " in java.util.ArrayList"
    ),
    new FixedPairing(
      "List -> LeafDtoList",
      "java.util.List<%1$sLeaf>",
      "%1$sLeafDtoList",
      "leaf",
      DTOS + " in %2$sLeafDtoList"
    ),
    new FixedPairing(
      "StrList -> ArrayList",
      "%1$sStrList",
      "java.util.ArrayList<String>",
      "str",
      "[b, a] in java.util.ArrayList"
    ),
    new FixedPairing(
      "LeafList -> LinkedList",
      "%1$sLeafList",
      "java.util.LinkedList<%1$sLeafDto>",
      "leaf",
      DTOS + " in java.util.LinkedList"
    ),
    new FixedPairing("StrList -> Tagged", "%1$sStrList", "%1$sTagged<Long, String>", "str", "[b, a] in %2$sTagged"),
    new FixedPairing("Tagged -> StrList", "%1$sTagged<String, String>", "%1$sStrList", "str", "[b, a] in %2$sStrList"),
    new FixedPairing(
      "LeafList -> Tagged",
      "%1$sLeafList",
      "%1$sTagged<Long, %1$sLeafDto>",
      "leaf",
      DTOS + " in %2$sTagged"
    ),
    new FixedPairing(
      "Tagged -> LeafDtoList",
      "%1$sTagged<String, %1$sLeaf>",
      "%1$sLeafDtoList",
      "leaf",
      DTOS + " in %2$sLeafDtoList"
    ),
    new FixedPairing("StrList -> StrList2", "%1$sStrList", "%1$sStrList2", "str", "[b, a] in %2$sStrList2"),
    new FixedPairing(
      "LeafList -> LeafDtoList",
      "%1$sLeafList",
      "%1$sLeafDtoList",
      "leaf",
      DTOS + " in %2$sLeafDtoList"
    ),
    new FixedPairing(
      "StrSet -> Set",
      "%1$sStrSet",
      "java.util.Set<String>",
      "str",
      "[b, a] in java.util.LinkedHashSet"
    ),
    new FixedPairing(
      "Set -> LeafDtoSet",
      "java.util.Set<%1$sLeaf>",
      "%1$sLeafDtoSet",
      "leaf",
      DTOS + " in %2$sLeafDtoSet"
    ),
    new FixedPairing(
      "LeafSet -> Set",
      "%1$sLeafSet",
      "java.util.Set<%1$sLeafDto>",
      "leaf",
      DTOS + " in java.util.LinkedHashSet"
    ),
    new FixedPairing(
      "LeafSet -> LinkedHashSet",
      "%1$sLeafSet",
      "java.util.LinkedHashSet<%1$sLeafDto>",
      "leaf",
      DTOS + " in java.util.LinkedHashSet"
    ),
    new FixedPairing("LeafSet -> GSet", "%1$sLeafSet", "%1$sGSet<Long, %1$sLeafDto>", "leaf", DTOS + " in %2$sGSet"),
    new FixedPairing(
      "GSet -> LeafDtoSet",
      "%1$sGSet<String, %1$sLeaf>",
      "%1$sLeafDtoSet",
      "leaf",
      DTOS + " in %2$sLeafDtoSet"
    ),
    new FixedPairing("StrSet -> StrSet2", "%1$sStrSet", "%1$sStrSet2", "str", "[b, a] in %2$sStrSet2"),
    new FixedPairing("LeafSet -> LeafDtoSet", "%1$sLeafSet", "%1$sLeafDtoSet", "leaf", DTOS + " in %2$sLeafDtoSet"),
    new FixedPairing(
      "StrMap -> Map",
      "%1$sStrMap",
      "java.util.Map<String, String>",
      "str",
      "{k1=b, k2=a} in java.util.LinkedHashMap"
    ),
    new FixedPairing(
      "Map -> LeafDtoMap",
      "java.util.Map<String, %1$sLeaf>",
      "%1$sLeafDtoMap",
      "leaf",
      DTO_MAP + " in %2$sLeafDtoMap"
    ),
    new FixedPairing(
      "LeafMap -> Map",
      "%1$sLeafMap",
      "java.util.Map<String, %1$sLeafDto>",
      "leaf",
      DTO_MAP + " in java.util.LinkedHashMap"
    ),
    new FixedPairing(
      "LeafMap -> LinkedHashMap",
      "%1$sLeafMap",
      "java.util.LinkedHashMap<String, %1$sLeafDto>",
      "leaf",
      DTO_MAP + " in java.util.LinkedHashMap"
    ),
    new FixedPairing("LeafMap -> GMap", "%1$sLeafMap", "%1$sGMap<Long, %1$sLeafDto>", "leaf", DTO_MAP + " in %2$sGMap"),
    new FixedPairing(
      "GMap -> LeafDtoMap",
      "%1$sGMap<Long, %1$sLeaf>",
      "%1$sLeafDtoMap",
      "leaf",
      DTO_MAP + " in %2$sLeafDtoMap"
    ),
    new FixedPairing("StrMap -> StrMap2", "%1$sStrMap", "%1$sStrMap2", "str", "{k1=b, k2=a} in %2$sStrMap2"),
    new FixedPairing("LeafMap -> LeafDtoMap", "%1$sLeafMap", "%1$sLeafDtoMap", "leaf", DTO_MAP + " in %2$sLeafDtoMap"),
    new FixedPairing(
      "StrTree -> SortedSet",
      "%1$sStrTree",
      "java.util.SortedSet<String>",
      "str",
      "[a, b] in java.util.TreeSet"
    ),
    new FixedPairing(
      "SortedSet -> StrTree",
      "java.util.SortedSet<String>",
      "%1$sStrTree",
      "str",
      "[a, b] in %2$sStrTree"
    ),
    new FixedPairing("StrTree -> StrTree2", "%1$sStrTree", "%1$sStrTree2", "str", "[a, b] in %2$sStrTree2"),
    new FixedPairing(
      "LeafTree -> SortedSet",
      "%1$sLeafTree",
      "java.util.SortedSet<%1$sLeafDto>",
      "leaf",
      SORTED_DTOS + " in java.util.TreeSet"
    ),
    new FixedPairing(
      "LeafTree -> LeafDtoTree",
      "%1$sLeafTree",
      "%1$sLeafDtoTree",
      "leaf",
      SORTED_DTOS + " in %2$sLeafDtoTree"
    ),
    new FixedPairing(
      "LeafTreeMap -> SortedMap",
      "%1$sLeafTreeMap",
      "java.util.SortedMap<String, %1$sLeafDto>",
      "leaf",
      DTO_MAP + " in java.util.TreeMap"
    ),
    new FixedPairing(
      "SortedMap -> LeafDtoTreeMap",
      "java.util.SortedMap<String, %1$sLeaf>",
      "%1$sLeafDtoTreeMap",
      "leaf",
      DTO_MAP + " in %2$sLeafDtoTreeMap"
    ),
    new FixedPairing(
      "LeafTreeMap -> LeafDtoTreeMap",
      "%1$sLeafTreeMap",
      "%1$sLeafDtoTreeMap",
      "leaf",
      DTO_MAP + " in %2$sLeafDtoTreeMap"
    ),
    new FixedPairing(
      "Groups -> List of lists",
      "%1$sGroups",
      "java.util.List<java.util.List<%1$sLeafDto>>",
      "leafList",
      "[[%1$sLeafDto[v=b]], [%1$sLeafDto[v=a]]] in java.util.ArrayList"
    ),
    new FixedPairing(
      "MapOfLists -> Map of lists",
      "%1$sMapOfLists",
      "java.util.Map<String, java.util.List<%1$sLeafDto>>",
      "leafList",
      "{k1=[%1$sLeafDto[v=b]], k2=[%1$sLeafDto[v=a]]} in java.util.LinkedHashMap"
    ),
    // The optionals hold the same element on both sides, so this row asks only whether the
    // element type is found. Converting a record inside an optional that is itself a
    // container's element is a separate question, which the two paths answer differently.
    new FixedPairing(
      "OptList -> List of optionals",
      "%1$sOptList",
      "java.util.List<java.util.Optional<%1$sLeaf>>",
      "leafOpt",
      "[Optional[%1$sLeaf[v=b]], Optional[%1$sLeaf[v=a]]] in java.util.ArrayList"
    ),
    new FixedPairing(
      "WildMap -> Map of wildcard lists",
      "%1$sWildMap",
      "java.util.Map<String, java.util.List<?>>",
      "strList",
      "{k1=[b], k2=[a]} in java.util.LinkedHashMap"
    ),
    // A generic class used raw names no element type, so nothing shows its elements are of
    // the
    // type the other side fixes, and neither a copy nor a conversion can be planned.
    new FixedPairing(
      "raw Gen -> LeafDtoList",
      "%1$sGen",
      "%1$sLeafDtoList",
      "leaf",
      REFUSED + "a generic container used raw"
    ),
    new FixedPairing("raw Gen -> StrList", "%1$sGen", "%1$sStrList", "str", REFUSED + "a generic container used raw"),
    new FixedPairing(
      "LeafList -> raw Gen",
      "%1$sLeafList",
      "%1$sGen",
      "leaf",
      REFUSED + "a generic container used raw"
    ),
    // A raw use against another raw use, or against a side holding Object, has
    // nothing its elements could fail to fit, so both paths copy them into the
    // class the target allocates. The sorted rows carry a reversing comparator,
    // which the copy has to hand to the target.
    new FixedPairing("raw Gen -> ObjectList", "%1$sGen", "%1$sObjectList", "str", "[b, a] in %2$sObjectList"),
    new FixedPairing("ObjectList -> raw Gen", "%1$sObjectList", "%1$sGen", "str", "[b, a] in %2$sGen"),
    new FixedPairing(
      "raw HashMap -> ObjectMap",
      "java.util.HashMap",
      "%1$sObjectMap",
      "str",
      "{k1=b, k2=a} in %2$sObjectMap"
    ),
    new FixedPairing("raw Gen -> raw Gen2", "%1$sGen", "%1$sGen2", "str", "[b, a] in %2$sGen2"),
    new FixedPairing(
      "raw ArrayList -> raw LinkedList",
      "java.util.ArrayList",
      "java.util.LinkedList",
      "str",
      "[b, a] in java.util.LinkedList"
    ),
    new FixedPairing(
      "raw TreeSet -> raw ConcurrentSkipListSet",
      "java.util.TreeSet",
      "java.util.concurrent.ConcurrentSkipListSet",
      "strReversed",
      "[b, a] in java.util.concurrent.ConcurrentSkipListSet"
    ),
    new FixedPairing(
      "raw ConcurrentSkipListSet -> raw TreeSet",
      "java.util.concurrent.ConcurrentSkipListSet",
      "java.util.TreeSet",
      "strReversed",
      "[b, a] in java.util.TreeSet"
    ),
    new FixedPairing(
      "raw TreeMap -> raw ConcurrentSkipListMap",
      "java.util.TreeMap",
      "java.util.concurrent.ConcurrentSkipListMap",
      "strReversed",
      "{k2=a, k1=b} in java.util.concurrent.ConcurrentSkipListMap"
    ),
    // One side raw, the other a sorted subtype fixing Object: the comparator is
    // read without a type argument, since a raw source cannot be tested against
    // one, and handed to the subtype.
    new FixedPairing(
      "raw TreeSet -> ObjectTree",
      "java.util.TreeSet",
      "%1$sObjectTree",
      "strReversed",
      "[b, a] in %2$sObjectTree"
    ),
    new FixedPairing(
      "ObjectTree -> raw TreeSet",
      "%1$sObjectTree",
      "java.util.TreeSet",
      "strReversed",
      "[b, a] in java.util.TreeSet"
    ),
    new FixedPairing(
      "raw TreeMap -> ObjectTreeMap",
      "java.util.TreeMap",
      "%1$sObjectTreeMap",
      "strReversed",
      "{k2=a, k1=b} in %2$sObjectTreeMap"
    ),
    // An interface used raw is built as its family's default implementation, on both paths.
    new FixedPairing(
      "raw List -> raw ArrayList",
      "java.util.List",
      "java.util.ArrayList",
      "str",
      "[b, a] in java.util.ArrayList"
    ),
    new FixedPairing(
      "raw Set -> raw LinkedHashSet",
      "java.util.Set",
      "java.util.LinkedHashSet",
      "str",
      "[b, a] in java.util.LinkedHashSet"
    ),
    new FixedPairing(
      "raw Deque -> raw ArrayDeque",
      "java.util.Deque",
      "java.util.ArrayDeque",
      "str",
      "[b, a] in java.util.ArrayDeque"
    ),
    new FixedPairing(
      "raw ArrayDeque -> raw Deque",
      "java.util.ArrayDeque",
      "java.util.Deque",
      "str",
      "[b, a] in java.util.ArrayDeque"
    ),
    new FixedPairing(
      "raw SortedSet -> raw TreeSet",
      "java.util.SortedSet",
      "java.util.TreeSet",
      "strReversed",
      "[b, a] in java.util.TreeSet"
    ),
    new FixedPairing(
      "raw TreeMap -> raw SortedMap",
      "java.util.TreeMap",
      "java.util.SortedMap",
      "strReversed",
      "{k2=a, k1=b} in java.util.TreeMap"
    ),
    // An abstract class the allocation table names no default for cannot be built
    // by a copy, and two containers are never rebuilt as beans, so both paths
    // refuse the pair by name.
    new FixedPairing(
      "raw AbstractList -> raw ArrayList",
      "java.util.AbstractList",
      "java.util.ArrayList",
      "str",
      REFUSED + "incompatible source/target shapes — java.util.AbstractList vs java.util.ArrayList"
    ),
    // Two interfaces used raw, each built as its default, carrying the source's comparator.
    new FixedPairing(
      "raw SortedSet -> raw NavigableSet",
      "java.util.SortedSet",
      "java.util.NavigableSet",
      "strReversed",
      "[b, a] in java.util.TreeSet"
    ),
    new FixedPairing(
      "raw ArrayList -> raw AbstractList",
      "java.util.ArrayList",
      "java.util.AbstractList",
      "str",
      REFUSED + "incompatible source/target shapes — java.util.ArrayList vs java.util.AbstractList"
    ),
    // A sorted generic class used raw whose comparator constructor is not public
    // cannot be told the source's order, so a source carrying one is refused at
    // conversion.
    new FixedPairing(
      "raw TreeSet -> raw sorted subtype with no public comparator constructor",
      "java.util.TreeSet",
      "%1$sGTree",
      "strReversed",
      REFUSED + "declares no public constructor taking a Comparator"
    ),
    // A container whose elements are raw uses copies each element as a nested container does.
    new FixedPairing(
      "List of raw Gen -> List of ObjectList",
      "java.util.List<%1$sGen>",
      "java.util.List<%1$sObjectList>",
      "genList",
      "[[b], [a]] in java.util.ArrayList"
    ),
    new FixedPairing(
      "Set of raw Gen -> Set of ObjectList",
      "java.util.Set<%1$sGen>",
      "java.util.Set<%1$sObjectList>",
      "genList",
      "[[b], [a]] in java.util.LinkedHashSet"
    ),
    new FixedPairing(
      "Map of ObjectList -> Map of raw Gen",
      "java.util.Map<String, %1$sObjectList>",
      "java.util.Map<String, %1$sGen>",
      "objectList",
      "{k1=[b], k2=[a]} in java.util.LinkedHashMap"
    ),
    new FixedPairing(
      "Map of raw Gen -> Map of raw Gen2",
      "java.util.Map<String, %1$sGen>",
      "java.util.Map<String, %1$sGen2>",
      "genList",
      "{k1=[b], k2=[a]} in java.util.LinkedHashMap"
    ),
    // A map keyed by a parameterized type: the key is the same type on both sides, so the
    // keys
    // carry across and the values convert.
    new FixedPairing(
      "ListKeyLeafMap -> ListKeyLeafDtoMap",
      "%1$sListKeyLeafMap",
      "%1$sListKeyLeafDtoMap",
      "leafListKey",
      LIST_KEYED_DTOS + " in %2$sListKeyLeafDtoMap"
    ),
    new FixedPairing(
      "ListKeyLeafMap -> Map",
      "%1$sListKeyLeafMap",
      "java.util.Map<java.util.List<String>, %1$sLeafDto>",
      "leafListKey",
      LIST_KEYED_DTOS + " in java.util.LinkedHashMap"
    ),
    new FixedPairing(
      "Map -> Map, both keyed by a list",
      "java.util.Map<java.util.List<String>, %1$sLeaf>",
      "java.util.Map<java.util.List<String>, %1$sLeafDto>",
      "leafListKey",
      LIST_KEYED_DTOS + " in java.util.LinkedHashMap"
    ),
    new FixedPairing(
      "Map -> Map, both keyed by a wildcard list",
      "java.util.Map<java.util.List<?>, %1$sLeaf>",
      "java.util.Map<java.util.List<?>, %1$sLeafDto>",
      "leafListKey",
      LIST_KEYED_DTOS + " in java.util.LinkedHashMap"
    ),
    new FixedPairing(
      "Map -> Map, keyed by different lists",
      "java.util.Map<java.util.List<String>, %1$sLeaf>",
      "java.util.Map<java.util.List<Integer>, %1$sLeafDto>",
      "leafListKey",
      REFUSED + "incompatible Map key types"
    )
  );

  @Test
  @DisplayName("a class declaring no type parameters converts the same way on both paths, whatever it is paired with")
  void aFixedArgumentSubtypeConvertsTheSameWayOnBothPaths() throws ReflectiveOperationException {
    // Such a class is written without type arguments and keeps its element types on a supertype,
    // so each path has to find them there. Pairing it against every other way of declaring the
    // same container, and against another of its own kind with different elements, is what shows
    // whether the elements are found and converted rather than carried across as they are.
    final var failures = new ArrayList<String>();
    final var diverged = new LinkedHashSet<String>();
    var index = 0;
    for (final var pairing : FIXED_PAIRINGS) {
      final var prefix = "Fx" + index++;
      final var qualified = PACKAGE + "." + prefix;
      final var srcField = pairing.src().formatted(prefix);
      final var tgtField = pairing.tgt().formatted(prefix);
      final var sources = fixedSources(prefix, srcField, tgtField);
      final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources);
      assertTrue(
        plain.success(),
        () -> pairing.name() + " should compile without the processor: " + plain.errorMessages()
      );
      final var processed = bridged(sources);

      final var classes = plain.define(MethodHandles.lookup());
      final var src = classes.get(qualified + "Src");
      final var tgt = classes.get(qualified + "Tgt");
      final var source = src.getConstructors()[0].newInstance(fixedInput(classes, prefix, srcField, pairing.input()));
      final var items = tgt.getMethod("items");

      final var nested = nestedBridgesFirst(processed, plain, prefix);
      final var reflective = run(items, () -> Telescope.mapper(cast(src), cast(tgt)).forward(source));
      final var forward = processed.success()
        ? emitted(processed, plain, prefix, nested).getMethod("forward", src)
        : null;
      final var generated =
        forward == null
          ? Outcome.refused(processed.errorMessages().strip())
          : run(items, () -> forward.invoke(null, source));

      // A refusal is owed as `refused: ` and a fragment of the runtime's message. The generated
      // path refuses either while compiling, where only the processor's own diagnostic counts, or
      // while converting, where it has to give the same fragment the runtime gives. A javac error
      // inside a generated file is a compile refusal without that diagnostic, so it cannot pass
      // for one, whatever its text. A registered divergence owes the runtime's outcome from the
      // runtime and the processor's diagnostic from the generated path.
      final var refusal = pairing.owed().startsWith(REFUSED) ? pairing.owed().substring(REFUSED.length()) : null;
      final var owed =
        refusal != null
          ? pairing.owed()
          : prefix + "Tgt[items=" + pairing.owed().formatted(prefix, qualified).replaceFirst(" in ", "] in ");
      final var divergence = FIXED_KNOWN_DIVERGENCES.get(pairing.name());
      for (final var side : List.of(Map.entry("generated", generated), Map.entry("reflective", reflective))) {
        final var outcome = side.getValue();
        final var generatedSide = side.getKey().equals("generated");
        final var refuses =
          refusal != null ||
          (divergence != null && !(generatedSide ? divergence.generated() : divergence.reflective()));
        final var refusedWhileCompiling = generatedSide && forward == null;
        final var met = refuses
          ? outcome.refusal() != null &&
            (refusedWhileCompiling
              ? outcome.refusal().contains("ERROR: @Bridge")
              : refusal != null && outcome.refusal().contains(refusal))
          : owed.equals(outcome.toString());
        if (!met) failures.add(pairing.name() + ": " + side.getKey() + " gave " + outcome + ", owed " + owed);
      }
      if (divergence != null) diverged.add(pairing.name());
    }
    assertTrue(
      failures.isEmpty(),
      () -> failures.size() + " fixed pairing(s) failed:\n  " + String.join("\n  ", failures)
    );
    final var stale = new LinkedHashSet<>(FIXED_KNOWN_DIVERGENCES.keySet());
    stale.removeAll(diverged);
    assertTrue(stale.isEmpty(), () -> "registered divergences with no pairing in the table:\n  " + stale);
  }

  /**
   * Fixed-argument pairings the two paths are known to answer differently, each recorded by which
   * path converts. An entry holds the converting path to the table's outcome and the other to its
   * refusal, so a change on either side fails the pairing rather than passing silently, and an
   * entry whose pairing stops diverging or leaves the table fails too.
   */
  private static final Map<String, Verdict> FIXED_KNOWN_DIVERGENCES = Map.of();

  private static JavaFileObject[] fixedSources(final String prefix, final String srcField, final String tgtField) {
    final var head = "package " + PACKAGE + ";\n";
    final var files = new ArrayList<JavaFileObject>();
    for (final var leaf : List.of("Leaf", "LeafDto")) {
      final var name = prefix + leaf;
      files.add(
        source(
          name,
          head +
            "public record " +
            name +
            "(String v) implements Comparable<" +
            name +
            "> {\n  public int compareTo(final " +
            name +
            " o) { return v.compareTo(o.v); }\n}\n"
        )
      );
    }
    for (final var declaration : FIXED_DECLARATIONS.entrySet()) {
      final var name = prefix + declaration.getKey();
      final var mentioned = Pattern.compile("\\b" + name + "\\b");
      if (!mentioned.matcher(srcField).find() && !mentioned.matcher(tgtField).find()) continue;
      // A declaration that writes its own body, such as one declaring constructors, is taken whole.
      final var body = declaration.getValue().formatted(prefix);
      files.add(source(name, head + "public class " + name + " " + body + (body.endsWith("}") ? "\n" : " {}\n")));
    }
    files.add(
      source(
        prefix + "Src",
        head +
          "import io.github.eschizoid.telescope.annotations.Bridge;\n@Bridge(" +
          prefix +
          "Tgt.class)\npublic record " +
          prefix +
          "Src(" +
          srcField +
          " items) {}\n"
      )
    );
    files.add(source(prefix + "Tgt", head + "public record " + prefix + "Tgt(" + tgtField + " items) {}\n"));
    return files.toArray(JavaFileObject[]::new);
  }

  /**
   * The source container for a fixed pairing: the declared class itself where the cell declares it,
   * and otherwise the JDK class a field of that interface would hold, filled with {@link #VALUES}.
   */
  private static Object fixedInput(
    final Map<String, Class<?>> classes,
    final String prefix,
    final String declared,
    final String input
  ) throws ReflectiveOperationException {
    final var raw = declared.contains("<") ? declared.substring(0, declared.indexOf('<')) : declared;
    final Class<?> type = raw.startsWith(prefix)
      ? classes.get(PACKAGE + "." + raw)
      : switch (raw) {
          case "java.util.Set" -> LinkedHashSet.class;
          case "java.util.SortedSet" -> TreeSet.class;
          case "java.util.Map" -> LinkedHashMap.class;
          case "java.util.SortedMap" -> TreeMap.class;
          case "java.util.HashMap" -> LinkedHashMap.class;
          case "java.util.TreeSet" -> TreeSet.class;
          case "java.util.TreeMap" -> TreeMap.class;
          case "java.util.Deque", "java.util.ArrayDeque" -> ArrayDeque.class;
          case "java.util.concurrent.ConcurrentSkipListSet" -> ConcurrentSkipListSet.class;
          default -> ArrayList.class;
        };
    final var instance = input.equals("strReversed")
      ? type.getConstructor(Comparator.class).newInstance(Comparator.reverseOrder())
      : type.getConstructor().newInstance();
    final var leaf = classes.get(PACKAGE + "." + prefix + "Leaf").getConstructor(String.class);
    var key = 0;
    for (final var value : VALUES) {
      final Object element = switch (input) {
        case "leaf", "leafListKey" -> leaf.newInstance(value);
        case "objectList" -> {
          final var list = classes.get(PACKAGE + "." + prefix + "ObjectList").getConstructor().newInstance();
          Collection.class.getMethod("add", Object.class).invoke(list, value);
          yield list;
        }
        case "genList" -> {
          final var gen = classes.get(PACKAGE + "." + prefix + "Gen").getConstructor().newInstance();
          Collection.class.getMethod("add", Object.class).invoke(gen, value);
          yield gen;
        }
        case "leafList" -> List.of(leaf.newInstance(value));
        case "leafOpt" -> Optional.of(leaf.newInstance(value));
        case "strList" -> List.of(value);
        default -> value;
      };
      if (instance instanceof Map<?, ?>) {
        final var name = "k" + ++key;
        final Object mapKey = input.equals("leafListKey") ? List.of(name) : name;
        Map.class.getMethod("put", Object.class, Object.class).invoke(instance, mapKey, element);
      } else {
        Collection.class.getMethod("add", Object.class).invoke(instance, element);
      }
    }
    return instance;
  }

  /**
   * A target built through a member of narrower access than public: its name, the write strategy
   * both paths are asked for, the target's body after its name, and what both paths owe — the built
   * target's rendering, or {@link #REFUSED} and a fragment of the runtime's message.
   *
   * <p>{@code %1$s} is the cell's prefix. Source and target share a package, as every cell here
   * does, so a package-private or protected member is one the generated bridge can call and a
   * private one is not.
   */
  private record Construction(String name, String strategy, String target, String owed) {}

  private static final String NAME_PROPERTY = """
      private String name;
      public String getName() { return name; }
      public void setName(final String name) { this.name = name; }
      public String toString() { return "built " + name; }
    """;

  private static final String FINAL_NAME = """
      private final String name;
      public String getName() { return name; }
      public String toString() { return "built " + name; }
    """;

  private static final String TGT_BUILDER = """
      public static final class Builder {
        private String name;
        public Builder name(final String name) { this.name = name; return this; }
        public %1$sTgt build() { return new %1$sTgt(name); }
      }
    """;

  private static final List<Construction> CONSTRUCTIONS = List.of(
    new Construction(
      "package-private class, implicit no-arg constructor",
      "AUTO",
      "class %1$sTgt {\n" + NAME_PROPERTY + "}\n",
      "built x"
    ),
    new Construction(
      "package-private no-arg constructor",
      "AUTO",
      "public class %1$sTgt {\n  %1$sTgt() {}\n" + NAME_PROPERTY + "}\n",
      "built x"
    ),
    new Construction(
      "package-private no-arg constructor, setters asked for",
      "SETTERS",
      "public class %1$sTgt {\n  %1$sTgt() {}\n" + NAME_PROPERTY + "}\n",
      "built x"
    ),
    new Construction(
      "private no-arg constructor, setters asked for",
      "SETTERS",
      "public class %1$sTgt {\n  private %1$sTgt() {}\n" + NAME_PROPERTY + "}\n",
      "built x"
    ),
    new Construction(
      "package-private name-matched constructor, constructor asked for",
      "CONSTRUCTOR",
      "public class %1$sTgt {\n  %1$sTgt(final String name) { this.name = name; }\n" + FINAL_NAME + "}\n",
      "built x"
    ),
    new Construction(
      "private name-matched constructor, constructor asked for",
      "CONSTRUCTOR",
      "public class %1$sTgt {\n  private %1$sTgt(final String name) { this.name = name; }\n" + FINAL_NAME + "}\n",
      "built x"
    ),
    new Construction(
      "package-private name-matched constructor",
      "AUTO",
      "public class %1$sTgt {\n  %1$sTgt(final String name) { this.name = name; }\n" + FINAL_NAME + "}\n",
      REFUSED + "No name-based write strategy"
    ),
    new Construction(
      "package-private static builder()",
      "AUTO",
      "public class %1$sTgt {\n  private %1$sTgt(final String name) { this.name = name; }\n" +
        "  static Builder builder() { return new Builder(); }\n" +
        FINAL_NAME +
        TGT_BUILDER +
        "}\n",
      REFUSED + "No name-based write strategy"
    ),
    new Construction(
      "package-private static builder(), builder asked for",
      "BUILDER",
      "public class %1$sTgt {\n  private %1$sTgt(final String name) { this.name = name; }\n" +
        "  static Builder builder() { return new Builder(); }\n" +
        FINAL_NAME +
        TGT_BUILDER +
        "}\n",
      REFUSED + "requires a static builder()"
    ),
    new Construction(
      "public static builder() returning a package-private builder",
      "AUTO",
      "public class %1$sTgt {\n  private %1$sTgt(final String name) { this.name = name; }\n" +
        "  public static Builder builder() { return new Builder(); }\n" +
        FINAL_NAME +
        TGT_BUILDER.replace("public static final class", "static final class") +
        "}\n",
      "built x"
    )
  );

  /**
   * Constructions the two paths are known to answer differently, each recorded by which path builds
   * the target. The runtime writer calls a private constructor through a private lookup; the
   * generated bridge is ordinary source in another class and cannot, so it refuses with a
   * diagnostic. Each entry holds the runtime to its build and the generated path to that
   * diagnostic.
   */
  private static final Map<String, Verdict> CONSTRUCTION_KNOWN_DIVERGENCES = Map.of(
    // Inherent: no class but the target itself can call its private constructor from source.
    "private no-arg constructor, setters asked for",
    new Verdict(false, true),
    // Inherent: no class but the target itself can call its private constructor from source.
    "private name-matched constructor, constructor asked for",
    new Verdict(false, true)
  );

  @Test
  @DisplayName("a target reachable only through a narrower-than-public member is built the same way on both paths")
  void aNarrowerThanPublicMemberBuildsTheSameWayOnBothPaths() throws ReflectiveOperationException {
    final var failures = new ArrayList<String>();
    final var diverged = new LinkedHashSet<String>();
    var index = 0;
    for (final var construction : CONSTRUCTIONS) {
      final var prefix = "Ct" + index++;
      final var qualified = PACKAGE + "." + prefix;
      final var head = "package " + PACKAGE + ";\n";
      final var asked = construction.strategy().equals("AUTO")
        ? ""
        : ", writeStrategy = io.github.eschizoid.telescope.annotations.WriteStrategy." + construction.strategy();
      final var sources = new JavaFileObject[] {
        source(
          prefix + "Src",
          head +
            "import io.github.eschizoid.telescope.annotations.Bridge;\n@Bridge(value = " +
            prefix +
            "Tgt.class" +
            asked +
            ")\npublic record " +
            prefix +
            "Src(String name) {}\n"
        ),
        source(prefix + "Tgt", head + construction.target().formatted(prefix)),
      };
      final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources);
      assertTrue(
        plain.success(),
        () -> construction.name() + " should compile without the processor: " + plain.errorMessages()
      );
      final var processed = bridged(sources);

      final var classes = plain.define(MethodHandles.lookup());
      final Class<Object> src = cast(classes.get(qualified + "Src"));
      final Class<Object> tgt = cast(classes.get(qualified + "Tgt"));
      final var source = src.getConstructors()[0].newInstance("x");
      final var nested = nestedBridgesFirst(processed, plain, prefix);
      final var reflective = built(() ->
        (construction.strategy().equals("AUTO")
          ? Telescope.mapper(src, tgt)
          : Telescope.mapper(src, tgt, WriteHint.writeBean(tgt, WriteStrategy.valueOf(construction.strategy())))
        ).forward(source)
      );
      final var forward = processed.success()
        ? emitted(processed, plain, prefix, nested).getMethod("forward", src)
        : null;
      final var generated =
        forward == null ? REFUSED + processed.errorMessages().strip() : built(() -> forward.invoke(null, source));

      // A refusal is owed as `refused: ` and a fragment of the runtime's message. The generated
      // path's refusal has to be the processor's own diagnostic, so a javac error inside a
      // generated file cannot pass for one.
      final var refusal = construction.owed().startsWith(REFUSED)
        ? construction.owed().substring(REFUSED.length())
        : null;
      final var divergence = CONSTRUCTION_KNOWN_DIVERGENCES.get(construction.name());
      for (final var side : List.of(Map.entry("generated", generated), Map.entry("reflective", reflective))) {
        final var generatedSide = side.getKey().equals("generated");
        final var refuses =
          refusal != null ||
          (divergence != null && !(generatedSide ? divergence.generated() : divergence.reflective()));
        final var met = refuses
          ? side.getValue().startsWith(REFUSED) && side.getValue().contains(generatedSide ? "ERROR: @Bridge" : refusal)
          : construction.owed().equals(side.getValue());
        if (!met) {
          failures.add(
            construction.name() + ": " + side.getKey() + " gave " + side.getValue() + ", owed " + construction.owed()
          );
        }
      }
      if (divergence != null) diverged.add(construction.name());
    }
    assertTrue(
      failures.isEmpty(),
      () -> failures.size() + " construction(s) failed:\n  " + String.join("\n  ", failures)
    );
    final var stale = new LinkedHashSet<>(CONSTRUCTION_KNOWN_DIVERGENCES.keySet());
    stale.removeAll(diverged);
    assertTrue(stale.isEmpty(), () -> "registered divergences with no construction in the table:\n  " + stale);
  }

  /** What building a target rendered as, or {@link #REFUSED} and why it was not built. */
  private static String built(final Attempt attempt) {
    try {
      return String.valueOf(attempt.get());
    } catch (final InvocationTargetException e) {
      final var cause = e.getCause() == null ? e : e.getCause();
      return REFUSED + cause.getClass().getSimpleName() + ": " + cause.getMessage();
    } catch (final ReflectiveOperationException | RuntimeException e) {
      return REFUSED + e.getClass().getSimpleName() + ": " + e.getMessage();
    }
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

  /**
   * A component of one declared type on both sides, or a converted container holding one, given an
   * input whose own class or contents a copy into the declared type's default would lose: the field
   * types, the input, and what both paths owe for it, forward and backward alike.
   *
   * <p>{@code owed} is {@link #copyOutcome}'s description of the result against the input, so a
   * shared container, a copy of the wrong class, a lost comparator, a collapsed entry and a shared
   * or copied inner container all read differently.
   */
  private record SameTypedInput(String name, String srcField, String tgtField, Supplier<Object> input, String owed) {}

  private static final List<SameTypedInput> SAME_TYPED_INPUTS = List.of(
    new SameTypedInput(
      "nested list",
      "java.util.List<java.util.List<String>>",
      "java.util.List<java.util.List<String>>",
      () -> new ArrayList<>(List.of(new ArrayList<>(List.of("b", "a")))),
      "copy java.util.ArrayList [[b, a]], inner shared"
    ),
    new SameTypedInput(
      "nested map",
      "java.util.Map<String, java.util.List<String>>",
      "java.util.Map<String, java.util.List<String>>",
      () -> new LinkedHashMap<>(Map.of("k", new ArrayList<>(List.of("b", "a")))),
      "copy java.util.LinkedHashMap {k=[b, a]}, inner shared"
    ),
    new SameTypedInput(
      "same-typed inner of a converted list",
      "java.util.List<java.util.List<String>>",
      "java.util.ArrayList<java.util.List<String>>",
      () -> new ArrayList<>(List.of(new LinkedList<>(List.of("b", "a")))),
      "copy java.util.ArrayList [[b, a]], inner copied as java.util.LinkedList"
    ),
    new SameTypedInput(
      "List.of",
      "java.util.List<String>",
      "java.util.List<String>",
      () -> List.of("b", "a"),
      "shared"
    ),
    new SameTypedInput(
      "unmodifiableList",
      "java.util.List<String>",
      "java.util.List<String>",
      () -> Collections.unmodifiableList(new ArrayList<>(List.of("b", "a"))),
      "shared"
    ),
    new SameTypedInput(
      "Map.of",
      "java.util.Map<String, String>",
      "java.util.Map<String, String>",
      () -> Map.of("k", "v"),
      "shared"
    ),
    new SameTypedInput(
      "PriorityQueue behind Queue",
      "java.util.Queue<String>",
      "java.util.Queue<String>",
      () -> {
        final var queue = new PriorityQueue<String>(Comparator.reverseOrder());
        queue.addAll(List.of("a", "c", "b"));
        return queue;
      },
      "copy java.util.PriorityQueue [c, b, a], comparator kept"
    ),
    new SameTypedInput(
      "case-insensitive TreeSet behind Set",
      "java.util.Set<String>",
      "java.util.Set<String>",
      () -> {
        final var set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        set.addAll(List.of("b", "A"));
        return set;
      },
      "copy java.util.TreeSet [A, b], comparator kept"
    ),
    new SameTypedInput(
      "IdentityHashMap behind Map",
      "java.util.Map<String, String>",
      "java.util.Map<String, String>",
      () -> {
        final var map = new IdentityHashMap<String, String>();
        map.put(new String("k"), "1");
        map.put(new String("k"), "2");
        return map;
      },
      "copy java.util.IdentityHashMap of 2"
    ),
    new SameTypedInput(
      "LinkedList holding null behind Deque",
      "java.util.Deque<String>",
      "java.util.Deque<String>",
      () -> new LinkedList<>(Arrays.asList("b", null)),
      "copy java.util.LinkedList [b, null]"
    ),
    new SameTypedInput(
      "subclass behind Set",
      "java.util.Set<String>",
      "java.util.Set<String>",
      () -> {
        final var set = new SubclassSet<String>();
        set.addAll(List.of("b", "a"));
        return set;
      },
      "copy java.util.LinkedHashSet [b, a]"
    ),
    new SameTypedInput(
      "reversed PriorityBlockingQueue behind Queue",
      "java.util.Queue<String>",
      "java.util.Queue<String>",
      () -> {
        final var queue = new PriorityBlockingQueue<String>(11, Comparator.reverseOrder());
        queue.addAll(List.of("a", "c", "b"));
        return queue;
      },
      "copy java.util.concurrent.PriorityBlockingQueue [c, b, a], comparator kept"
    ),
    new SameTypedInput(
      "reversed TreeSet subclass behind Set",
      "java.util.Set<String>",
      "java.util.Set<String>",
      () -> {
        final var set = new SubclassTreeSet(Comparator.<String>reverseOrder());
        set.addAll(List.of("a", "b"));
        return set;
      },
      "copy java.util.TreeSet [b, a], comparator kept"
    ),
    new SameTypedInput(
      "reversed TreeMap subclass behind Map",
      "java.util.Map<String, String>",
      "java.util.Map<String, String>",
      () -> {
        final var map = new SubclassTreeMap(Comparator.<String>reverseOrder());
        map.putAll(Map.of("a", "1", "b", "2"));
        return map;
      },
      "copy java.util.TreeMap [{b=2, a=1}], comparator kept"
    ),
    new SameTypedInput(
      "reversed PriorityQueue subclass behind Queue",
      "java.util.Queue<String>",
      "java.util.Queue<String>",
      () -> {
        final var queue = new SubclassPriorityQueue(Comparator.<String>reverseOrder());
        queue.addAll(List.of("a", "c", "b"));
        return queue;
      },
      "copy java.util.PriorityQueue [c, b, a], comparator kept"
    ),
    new SameTypedInput(
      "Arrays.asList behind List",
      "java.util.List<String>",
      "java.util.List<String>",
      () -> Arrays.asList("b", "a"),
      "copy java.util.ArrayList [b, a]"
    )
  );

  @Test
  @DisplayName("a same-typed container is copied the same way by both paths, whatever its own class")
  void aSameTypedContainerIsCopiedTheSameWayOnBothPaths() throws ReflectiveOperationException {
    final var failures = new ArrayList<String>();
    var index = 0;
    for (final var row : SAME_TYPED_INPUTS) {
      final var prefix = "St" + index++;
      final var sources = new JavaFileObject[] {
        ProcessorHarness.source(
          PACKAGE + "." + prefix + "Src",
          "package " +
            PACKAGE +
            ";\n" +
            "@io.github.eschizoid.telescope.annotations.Bridge(" +
            prefix +
            "Tgt.class)\npublic record " +
            prefix +
            "Src(" +
            row.srcField() +
            " items) {}\n"
        ),
        ProcessorHarness.source(
          PACKAGE + "." + prefix + "Tgt",
          "package " + PACKAGE + ";\n" + "public record " + prefix + "Tgt(" + row.tgtField() + " items) {}\n"
        ),
      };
      final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources);
      assertTrue(plain.success(), plain::errorMessages);
      final var processed = bridged(sources);
      assertTrue(processed.success(), processed::errorMessages);
      final var classes = plain.define(MethodHandles.lookup());
      final var src = classes.get(PACKAGE + "." + prefix + "Src");
      final var tgt = classes.get(PACKAGE + "." + prefix + "Tgt");
      final var nested = nestedBridgesFirst(processed, plain, prefix);
      final var mapper = Telescope.mapper(cast(src), cast(tgt));
      final var bridge = emitted(processed, plain, prefix, nested);
      final var srcItems = src.getMethod("items");
      final var tgtItems = tgt.getMethod("items");
      final var forwardIn = row.input().get();
      final var backwardIn = row.input().get();
      final var forwardMethod = bridge.getMethod("forward", src);
      final var backwardMethod = bridge.getMethod("backward", tgt);
      final var outcomes = Map.of(
        "generated forward",
        attempt(forwardIn, () -> tgtItems.invoke(forwardMethod.invoke(null, newRecord(src, forwardIn)))),
        "reflective forward",
        attempt(forwardIn, () -> tgtItems.invoke(mapper.forward(newRecord(src, forwardIn)))),
        "generated backward",
        attempt(backwardIn, () -> srcItems.invoke(backwardMethod.invoke(null, newRecord(tgt, backwardIn)))),
        "reflective backward",
        attempt(backwardIn, () -> srcItems.invoke(mapper.backward(newRecord(tgt, backwardIn))))
      );
      outcomes.forEach((side, outcome) -> {
        if (!row.owed().equals(outcome)) failures.add(
          row.name() + ", " + side + ": " + outcome + ", owed " + row.owed()
        );
      });
    }
    assertTrue(failures.isEmpty(), () -> failures.size() + " outcome(s) failed:\n  " + String.join("\n  ", failures));
  }

  /** One direction of one path, which either produces the converted container or throws. */
  private interface Conversion {
    Object run() throws ReflectiveOperationException;
  }

  /** {@link #copyOutcome} of what {@code conversion} returns, or the exception it ends in. */
  private static String attempt(final Object in, final Conversion conversion) {
    try {
      return copyOutcome(in, conversion.run());
    } catch (final InvocationTargetException e) {
      return "threw " + e.getCause().getClass().getName();
    } catch (final ReflectiveOperationException | RuntimeException e) {
      return "threw " + e.getClass().getName();
    }
  }

  /**
   * A container class outside the JDK with a public no-argument constructor, which is what a
   * framework's own collection looks like from outside: a copy is not made in it.
   */
  public static final class SubclassSet<E> extends LinkedHashSet<E> {

    private static final long serialVersionUID = 1L;

    public SubclassSet() {}
  }

  /** Sorted and priority containers outside the JDK, each carrying a comparator of its own. */
  public static final class SubclassTreeSet extends TreeSet<String> {

    private static final long serialVersionUID = 1L;

    public SubclassTreeSet(final Comparator<String> order) {
      super(order);
    }
  }

  public static final class SubclassTreeMap extends TreeMap<String, String> {

    private static final long serialVersionUID = 1L;

    public SubclassTreeMap(final Comparator<String> order) {
      super(order);
    }
  }

  public static final class SubclassPriorityQueue extends PriorityQueue<String> {

    private static final long serialVersionUID = 1L;

    public SubclassPriorityQueue(final Comparator<String> order) {
      super(order);
    }
  }

  private static Object newRecord(final Class<?> type, final Object items) throws ReflectiveOperationException {
    return type.getConstructors()[0].newInstance(items);
  }

  /**
   * What a conversion did with {@code in}: handed it across, or copied it into some class with some
   * contents. A sorted or priority container says whether the copy kept the input's comparator, an
   * identity map says how many entries survived, and a container whose first element is itself a
   * container says whether that inner one was shared or copied, and as what.
   */
  private static String copyOutcome(final Object in, final Object out) {
    if (in == out) return "shared";
    final var sb = new StringBuilder("copy ").append(out.getClass().getName());
    if (out instanceof IdentityHashMap<?, ?> map) return sb.append(" of ").append(map.size()).toString();
    final var comparator = comparatorOf(out);
    if (comparator != null) {
      final var drained = new ArrayList<Object>();
      if (out instanceof PriorityQueue<?> queue) {
        final var copy = new PriorityQueue<>(queue);
        while (!copy.isEmpty()) drained.add(copy.poll());
      } else if (out instanceof PriorityBlockingQueue<?> queue) {
        final var copy = new PriorityBlockingQueue<>(queue);
        while (!copy.isEmpty()) drained.add(copy.poll());
      } else if (out instanceof Map<?, ?> map) {
        drained.add(map);
      } else {
        drained.addAll((Collection<?>) out);
      }
      sb.append(' ').append(drained).append(comparator == comparatorOf(in) ? ", comparator kept" : ", comparator lost");
      return sb.toString();
    }
    sb.append(' ').append(out);
    final var inner = firstElement(in);
    if (inner instanceof Collection<?> innerIn) {
      final var innerOut = firstElement(out);
      sb.append(innerOut == innerIn ? ", inner shared" : ", inner copied as " + innerOut.getClass().getName());
    }
    return sb.toString();
  }

  private static Comparator<?> comparatorOf(final Object container) {
    if (container instanceof SortedSet<?> set) return set.comparator();
    if (container instanceof PriorityQueue<?> queue) return queue.comparator();
    if (container instanceof PriorityBlockingQueue<?> queue) return queue.comparator();
    if (container instanceof SortedMap<?, ?> map) return map.comparator();
    return null;
  }

  private static Object firstElement(final Object container) {
    if (container instanceof Map<?, ?> map) return map.isEmpty() ? null : map.values().iterator().next();
    final var collection = (Collection<?>) container;
    return collection.isEmpty() ? null : collection.iterator().next();
  }

  /**
   * An object graph that leads back to an object still being converted: the declarations, each with
   * {@code %1$s} as the cell's prefix, and the shape both paths owe forward, backward and in {@code
   * patch}. {@code Src} carries the {@code @Bridge}; {@code Src.sample()} and {@code Tgt.sample()}
   * build the cyclic inputs, and {@code patch} lays {@code Tgt.sample()} over {@code Src.sample()}.
   *
   * <p>A shape names each distinct target object {@code #n} where it first appears and {@code @n}
   * wherever it appears again, so the identity relationships between targets are part of what both
   * paths owe, not only the values.
   */
  private record Cycle(String name, List<String> declarations, String forward, String backward, String patch) {}

  /**
   * A public class with a no-arg constructor, a getter and setter per property, and {@code extra}.
   */
  private static String bean(final String name, final String extra, final String... properties) {
    final var sb = new StringBuilder("public class ").append(name).append(" {\n");
    for (final var property : properties) {
      final var type = property.substring(0, property.lastIndexOf(' '));
      final var field = property.substring(property.lastIndexOf(' ') + 1);
      final var cap = Character.toUpperCase(field.charAt(0)) + field.substring(1);
      sb.append("  private ").append(type).append(' ').append(field).append(";\n");
      sb.append("  public ").append(type).append(" get").append(cap).append("() { return ").append(field);
      sb.append("; }\n  public void set").append(cap).append("(final ").append(type).append(" v) { ");
      sb.append(field).append(" = v; }\n");
    }
    return sb.append(extra).append("}\n").toString();
  }

  private static final String BRIDGE_TO_TGT = "@io.github.eschizoid.telescope.annotations.Bridge(%1$sTgt.class)\n";

  /** Two people who manage each other, as {@code type}. */
  private static String pair(final String type) {
    return (
      "  public static " +
      type +
      " sample() {\n" +
      "    final var alice = new " +
      type +
      "();\n" +
      "    final var bob = new " +
      type +
      "();\n" +
      "    alice.setName(\"alice\");\n" +
      "    bob.setName(\"bob\");\n" +
      "    alice.setManager(bob);\n" +
      "    bob.setManager(alice);\n" +
      "    return alice;\n" +
      "  }\n"
    );
  }

  /** One person who manages themself, as {@code type}. */
  private static String loop(final String type) {
    return (
      "  public static " +
      type +
      " sample() {\n" +
      "    final var self = new " +
      type +
      "();\n" +
      "    self.setName(\"self\");\n" +
      "    self.setManager(self);\n" +
      "    return self;\n" +
      "  }\n"
    );
  }

  private static final List<Cycle> CYCLES = List.of(
    new Cycle(
      "an object that refers to itself",
      List.of(
        BRIDGE_TO_TGT + bean("%1$sSrc", loop("%1$sSrc"), "String name", "%1$sSrc manager"),
        bean("%1$sTgt", loop("%1$sTgt"), "String name", "%1$sTgt manager")
      ),
      "#0(manager=null, name=\"self\")",
      "#0(manager=null, name=\"self\")",
      "#0(manager=#1(manager=null, name=\"self\"), name=\"self\")"
    ),
    new Cycle(
      "two objects of one type that refer to each other",
      List.of(
        BRIDGE_TO_TGT + bean("%1$sSrc", pair("%1$sSrc"), "String name", "%1$sSrc manager"),
        bean("%1$sTgt", pair("%1$sTgt"), "String name", "%1$sTgt manager")
      ),
      "#0(manager=#1(manager=null, name=\"bob\"), name=\"alice\")",
      "#0(manager=#1(manager=null, name=\"bob\"), name=\"alice\")",
      "#0(manager=#1(manager=#2(manager=null, name=\"alice\"), name=\"bob\"), name=\"alice\")"
    ),
    new Cycle(
      "two objects of two types that refer to each other",
      List.of(
        BRIDGE_TO_TGT +
          bean(
            "%1$sSrc",
            "  public static %1$sSrc sample() {\n" +
              "    final var person = new %1$sSrc();\n" +
              "    final var desk = new %1$sDesk();\n" +
              "    person.setName(\"person\");\n" +
              "    desk.setLabel(\"desk\");\n" +
              "    person.setDesk(desk);\n" +
              "    desk.setOwner(person);\n" +
              "    return person;\n" +
              "  }\n",
            "String name",
            "%1$sDesk desk"
          ),
        bean("%1$sDesk", "", "String label", "%1$sSrc owner"),
        bean(
          "%1$sTgt",
          "  public static %1$sTgt sample() {\n" +
            "    final var person = new %1$sTgt();\n" +
            "    final var desk = new %1$sDeskDto();\n" +
            "    person.setName(\"person\");\n" +
            "    desk.setLabel(\"desk\");\n" +
            "    person.setDesk(desk);\n" +
            "    desk.setOwner(person);\n" +
            "    return person;\n" +
            "  }\n",
          "String name",
          "%1$sDeskDto desk"
        ),
        bean("%1$sDeskDto", "", "String label", "%1$sTgt owner")
      ),
      "#0(desk=#1(label=\"desk\", owner=null), name=\"person\")",
      "#0(desk=#1(label=\"desk\", owner=null), name=\"person\")",
      "#0(desk=#1(label=\"desk\", owner=#2(desk=null, name=\"person\")), name=\"person\")"
    ),
    new Cycle(
      "a record that holds itself in a list",
      List.of(
        BRIDGE_TO_TGT +
          "public record %1$sSrc(String name, java.util.List<%1$sSrc> children) {\n" +
          "  public static %1$sSrc sample() {\n" +
          "    final var children = new java.util.ArrayList<%1$sSrc>();\n" +
          "    final var root = new %1$sSrc(\"root\", children);\n" +
          "    children.add(new %1$sSrc(\"child\", java.util.List.of(root)));\n" +
          "    return root;\n" +
          "  }\n" +
          "}\n",
        "public record %1$sTgt(String name, java.util.List<%1$sTgt> children) {\n" +
          "  public static %1$sTgt sample() {\n" +
          "    final var children = new java.util.ArrayList<%1$sTgt>();\n" +
          "    final var root = new %1$sTgt(\"root\", children);\n" +
          "    children.add(root);\n" +
          "    return root;\n" +
          "  }\n" +
          "}\n"
      ),
      "#0(name=\"root\", children=[#1(name=\"child\", children=[null])])",
      "#0(name=\"root\", children=[null])",
      "#0(name=\"root\", children=[#1(name=\"root\", children=[null])])"
    ),
    new Cycle(
      "a record that holds itself in a list of lists",
      List.of(
        BRIDGE_TO_TGT +
          "public record %1$sSrc(String name, java.util.List<java.util.List<%1$sSrc>> grid) {\n" +
          "  public static %1$sSrc sample() {\n" +
          "    final var row = new java.util.ArrayList<%1$sSrc>();\n" +
          "    final var root = new %1$sSrc(\"root\", java.util.List.of(row));\n" +
          "    row.add(root);\n" +
          "    return root;\n" +
          "  }\n" +
          "}\n",
        "public record %1$sTgt(String name, java.util.List<java.util.List<%1$sTgt>> grid) {\n" +
          "  public static %1$sTgt sample() {\n" +
          "    final var row = new java.util.ArrayList<%1$sTgt>();\n" +
          "    final var root = new %1$sTgt(\"root\", java.util.List.of(row));\n" +
          "    row.add(root);\n" +
          "    return root;\n" +
          "  }\n" +
          "}\n"
      ),
      "#0(name=\"root\", grid=[[null]])",
      "#0(name=\"root\", grid=[[null]])",
      "#0(name=\"root\", grid=[[#1(name=\"root\", grid=[[null]])]])"
    ),
    new Cycle(
      "a record that holds itself in a container class of its own",
      List.of(
        BRIDGE_TO_TGT +
          "public record %1$sSrc(String name, %1$sKids kids) {\n" +
          "  public static %1$sSrc sample() {\n" +
          "    final var kids = new %1$sKids();\n" +
          "    final var root = new %1$sSrc(\"root\", kids);\n" +
          "    kids.add(root);\n" +
          "    return root;\n" +
          "  }\n" +
          "}\n",
        "public class %1$sKids extends java.util.ArrayList<%1$sSrc> {\n" +
          "  private static final long serialVersionUID = 1L;\n" +
          "}\n",
        "public record %1$sTgt(String name, %1$sKidsDto kids) {\n" +
          "  public static %1$sTgt sample() {\n" +
          "    final var kids = new %1$sKidsDto();\n" +
          "    final var root = new %1$sTgt(\"root\", kids);\n" +
          "    kids.add(root);\n" +
          "    return root;\n" +
          "  }\n" +
          "}\n",
        "public class %1$sKidsDto extends java.util.ArrayList<%1$sTgt> {\n" +
          "  private static final long serialVersionUID = 1L;\n" +
          "}\n"
      ),
      "#0(name=\"root\", kids=[null])",
      "#0(name=\"root\", kids=[null])",
      "#0(name=\"root\", kids=[#1(name=\"root\", kids=[null])])"
    ),
    new Cycle(
      "a record that holds itself as a map value",
      List.of(
        BRIDGE_TO_TGT +
          "public record %1$sSrc(String name, java.util.Map<String, %1$sSrc> kids) {\n" +
          "  public static %1$sSrc sample() {\n" +
          "    final var kids = new java.util.LinkedHashMap<String, %1$sSrc>();\n" +
          "    final var root = new %1$sSrc(\"root\", kids);\n" +
          "    kids.put(\"child\", new %1$sSrc(\"child\", java.util.Map.of(\"up\", root)));\n" +
          "    return root;\n" +
          "  }\n" +
          "}\n",
        "public record %1$sTgt(String name, java.util.Map<String, %1$sTgt> kids) {\n" +
          "  public static %1$sTgt sample() {\n" +
          "    final var kids = new java.util.LinkedHashMap<String, %1$sTgt>();\n" +
          "    final var root = new %1$sTgt(\"root\", kids);\n" +
          "    kids.put(\"self\", root);\n" +
          "    return root;\n" +
          "  }\n" +
          "}\n"
      ),
      "#0(name=\"root\", kids={child=#1(name=\"child\", kids={up=null})})",
      "#0(name=\"root\", kids={self=null})",
      "#0(name=\"root\", kids={self=#1(name=\"root\", kids={self=null})})"
    ),
    new Cycle(
      "an object that refers to itself through an Optional",
      List.of(
        BRIDGE_TO_TGT +
          bean(
            "%1$sSrc",
            "  public static %1$sSrc sample() {\n" +
              "    final var self = new %1$sSrc();\n" +
              "    self.setName(\"self\");\n" +
              "    self.setManager(java.util.Optional.of(self));\n" +
              "    return self;\n" +
              "  }\n",
            "String name",
            "java.util.Optional<%1$sSrc> manager"
          ),
        bean(
          "%1$sTgt",
          "  public static %1$sTgt sample() {\n" +
            "    final var self = new %1$sTgt();\n" +
            "    self.setName(\"self\");\n" +
            "    self.setManager(java.util.Optional.of(self));\n" +
            "    return self;\n" +
            "  }\n",
          "String name",
          "java.util.Optional<%1$sTgt> manager"
        )
      ),
      "#0(manager=empty, name=\"self\")",
      "#0(manager=empty, name=\"self\")",
      "#0(manager=of #1(manager=empty, name=\"self\"), name=\"self\")"
    ),
    new Cycle(
      "one object reached twice along separate branches",
      List.of(
        BRIDGE_TO_TGT +
          bean(
            "%1$sSrc",
            "  public static %1$sSrc sample() {\n" +
              "    final var lead = new %1$sSrc();\n" +
              "    final var shared = new %1$sSrc();\n" +
              "    lead.setName(\"lead\");\n" +
              "    shared.setName(\"shared\");\n" +
              "    lead.setManager(shared);\n" +
              "    lead.setMentor(shared);\n" +
              "    return lead;\n" +
              "  }\n",
            "String name",
            "%1$sSrc manager",
            "%1$sSrc mentor"
          ),
        bean(
          "%1$sTgt",
          "  public static %1$sTgt sample() {\n" +
            "    final var lead = new %1$sTgt();\n" +
            "    final var shared = new %1$sTgt();\n" +
            "    lead.setName(\"lead\");\n" +
            "    shared.setName(\"shared\");\n" +
            "    lead.setManager(shared);\n" +
            "    lead.setMentor(shared);\n" +
            "    return lead;\n" +
            "  }\n",
          "String name",
          "%1$sTgt manager",
          "%1$sTgt mentor"
        )
      ),
      "#0(manager=#1(manager=null, mentor=null, name=\"shared\"), mentor=#2(manager=null, mentor=null, name=\"shared\"), name=\"lead\")",
      "#0(manager=#1(manager=null, mentor=null, name=\"shared\"), mentor=#2(manager=null, mentor=null, name=\"shared\"), name=\"lead\")",
      "#0(manager=#1(manager=null, mentor=null, name=\"shared\"), mentor=#2(manager=null, mentor=null, name=\"shared\"), name=\"lead\")"
    )
  );

  @Test
  @DisplayName("an object graph that leads back to an object being converted maps to the same shape on both paths")
  void aCyclicGraphMapsToTheSameShapeOnBothPaths() throws ReflectiveOperationException {
    final var failures = new ArrayList<String>();
    var index = 0;
    for (final var cycle : CYCLES) {
      final var prefix = "Cy" + index++;
      final var head = "package " + PACKAGE + ";\n";
      final var sources = cycle
        .declarations()
        .stream()
        .map(declaration -> declaration.formatted(prefix))
        .map(code -> source(declaredName(code), head + code))
        .toArray(JavaFileObject[]::new);
      final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources);
      assertTrue(
        plain.success(),
        () -> cycle.name() + " should compile without the processor: " + plain.errorMessages()
      );
      final var processed = bridged(sources);
      assertTrue(processed.success(), () -> cycle.name() + " should compile: " + processed.errorMessages());
      final var classes = plain.define(MethodHandles.lookup());
      final Class<Object> src = cast(classes.get(PACKAGE + "." + prefix + "Src"));
      final Class<Object> tgt = cast(classes.get(PACKAGE + "." + prefix + "Tgt"));
      final var nested = nestedBridgesFirst(processed, plain, prefix);
      final var mapper = Telescope.mapper(src, tgt);
      final var bridge = emitted(processed, plain, prefix, nested);
      final Attempt srcSample = () -> src.getMethod("sample").invoke(null);
      final Attempt tgtSample = () -> tgt.getMethod("sample").invoke(null);
      final var owed = List.of(
        Map.entry("forward", cycle.forward()),
        Map.entry("backward", cycle.backward()),
        Map.entry("patch", cycle.patch())
      );
      for (final var operation : owed) {
        final Attempt generated = switch (operation.getKey()) {
          case "forward" -> () -> bridge.getMethod("forward", src).invoke(null, srcSample.get());
          case "backward" -> () -> bridge.getMethod("backward", tgt).invoke(null, tgtSample.get());
          default -> () -> bridge.getMethod("patch", src, tgt).invoke(null, srcSample.get(), tgtSample.get());
        };
        final Attempt reflective = switch (operation.getKey()) {
          case "forward" -> () -> mapper.forward(srcSample.get());
          case "backward" -> () -> mapper.backward(tgtSample.get());
          default -> () -> mapper.patch(srcSample.get(), tgtSample.get());
        };
        for (final var side : List.of(Map.entry("generated", generated), Map.entry("reflective", reflective))) {
          final var shape = shapeOf(side.getValue());
          if (!shape.equals(operation.getValue())) {
            failures.add(
              cycle.name() +
                ", " +
                operation.getKey() +
                ": " +
                side.getKey() +
                " gave " +
                shape +
                ", owed " +
                operation.getValue()
            );
          }
        }
      }
    }
    assertTrue(
      failures.isEmpty(),
      () -> failures.size() + " cycle cell(s) failed:\n  " + String.join("\n  ", failures)
    );
  }

  @Test
  @DisplayName("a field mapped through a bridge the same compilation generates converts the same way on both paths")
  void aFieldThroughAGeneratedBridgeConvertsTheSameWayOnBothPaths() throws ReflectiveOperationException {
    // The @ViaMapper names a bridge the processor writes in the same compilation, so the source
    // cannot compile without the processor and every class comes from the processed compilation.
    final var head = "package " + PACKAGE + ";\n";
    final var processed = bridged(
      source(
        "VmAddress",
        head +
          "@io.github.eschizoid.telescope.annotations.Bridge(VmAddressDto.class)\n" +
          "public record VmAddress(String line) {}\n"
      ),
      source("VmAddressDto", head + "public record VmAddressDto(String line) {}\n"),
      source(
        "VmSrc",
        head +
          "@io.github.eschizoid.telescope.annotations.Bridge(value = VmTgt.class, viaMappers = {\n" +
          "  @io.github.eschizoid.telescope.annotations.ViaMapper(field = \"address\", using = VmAddressBridge.class)\n" +
          "})\n" +
          "public record VmSrc(String id, VmAddress address) {}\n"
      ),
      source("VmTgt", head + "public record VmTgt(String id, VmAddressDto address) {}\n")
    );
    assertTrue(processed.success(), () -> "the pair should compile: " + processed.errorMessages());
    final var classes = processed.define(MethodHandles.lookup());
    final Class<Object> src = cast(classes.get(PACKAGE + ".VmSrc"));
    final Class<Object> tgt = cast(classes.get(PACKAGE + ".VmTgt"));
    final var address = classes.get(PACKAGE + ".VmAddress");
    final var addressDto = classes.get(PACKAGE + ".VmAddressDto");
    final var bridge = classes.get(PACKAGE + ".VmSrcBridge");
    final Attempt source = () ->
      src
        .getConstructor(String.class, address)
        .newInstance("o1", address.getConstructor(String.class).newInstance("l1"));
    final Attempt target = () ->
      tgt
        .getConstructor(String.class, addressDto)
        .newInstance(null, addressDto.getConstructor(String.class).newInstance("m1"));
    final var mapper = Telescope.mapper(src, tgt);
    final var owed = List.of(
      Map.entry("forward", "#0(id=\"o1\", address=#1(line=\"l1\"))"),
      Map.entry("backward", "#0(id=null, address=#1(line=\"m1\"))"),
      Map.entry("patch", "#0(id=\"o1\", address=#1(line=\"m1\"))")
    );
    final var failures = new ArrayList<String>();
    for (final var operation : owed) {
      final Attempt generated = switch (operation.getKey()) {
        case "forward" -> () -> bridge.getMethod("forward", src).invoke(null, source.get());
        case "backward" -> () -> bridge.getMethod("backward", tgt).invoke(null, target.get());
        default -> () -> bridge.getMethod("patch", src, tgt).invoke(null, source.get(), target.get());
      };
      final Attempt reflective = switch (operation.getKey()) {
        case "forward" -> () -> mapper.forward(source.get());
        case "backward" -> () -> mapper.backward(target.get());
        default -> () -> mapper.patch(source.get(), target.get());
      };
      for (final var side : List.of(Map.entry("generated", generated), Map.entry("reflective", reflective))) {
        final var shape = shapeOf(side.getValue());
        if (!shape.equals(operation.getValue())) {
          failures.add(operation.getKey() + ": " + side.getKey() + " gave " + shape + ", owed " + operation.getValue());
        }
      }
    }
    assertTrue(failures.isEmpty(), () -> String.join("\n  ", failures));
  }

  @Test
  @DisplayName(
    "a field a generic superclass declares converts the same way on both paths, through the type each subclass gives it"
  )
  void anInheritedTypeVariableFieldConvertsTheSameWayOnBothPaths() throws ReflectiveOperationException {
    final var head = "package " + PACKAGE + ";\n";
    final var base =
      "public class TvBase<T extends TvBase<T>> {\n" +
      "  private String name;\n" +
      "  private T next;\n" +
      "  public String getName() { return name; }\n" +
      "  public void setName(final String name) { this.name = name; }\n" +
      "  public T getNext() { return next; }\n" +
      "  public void setNext(final T next) { this.next = next; }\n" +
      "}\n";
    final var sources = new JavaFileObject[] {
      source("TvBase", head + base),
      source(
        "TvSrc",
        head +
          "@io.github.eschizoid.telescope.annotations.Bridge(TvTgt.class)\n" +
          "public class TvSrc extends TvBase<TvSrc> {}\n"
      ),
      source("TvTgt", head + "public class TvTgt extends TvBase<TvTgt> {}\n"),
    };
    final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources);
    assertTrue(plain.success(), () -> "the pair should compile without the processor: " + plain.errorMessages());
    final var processed = bridged(sources);
    assertTrue(processed.success(), () -> "the pair should compile: " + processed.errorMessages());
    final var classes = plain.define(MethodHandles.lookup());
    final Class<Object> src = cast(classes.get(PACKAGE + ".TvSrc"));
    final Class<Object> tgt = cast(classes.get(PACKAGE + ".TvTgt"));
    final var nested = nestedBridgesFirst(processed, plain, "Tv");
    final var mapper = Telescope.mapper(src, tgt);
    final var bridge = emitted(processed, plain, "Tv", nested);
    // Two nodes that name each other as next, so the field is both inherited and part of a cycle.
    final Attempt srcSample = () -> chain(src, "alice", "bob");
    final Attempt tgtSample = () -> chain(tgt, "carol", "dave");
    final var owed = List.of(
      Map.entry("forward", "#0(name=\"alice\", next=#1(name=\"bob\", next=null))"),
      Map.entry("backward", "#0(name=\"carol\", next=#1(name=\"dave\", next=null))"),
      Map.entry("patch", "#0(name=\"carol\", next=#1(name=\"dave\", next=#2(name=\"carol\", next=null)))")
    );
    final var failures = new ArrayList<String>();
    for (final var operation : owed) {
      final Attempt generated = switch (operation.getKey()) {
        case "forward" -> () -> bridge.getMethod("forward", src).invoke(null, srcSample.get());
        case "backward" -> () -> bridge.getMethod("backward", tgt).invoke(null, tgtSample.get());
        default -> () -> bridge.getMethod("patch", src, tgt).invoke(null, srcSample.get(), tgtSample.get());
      };
      final Attempt reflective = switch (operation.getKey()) {
        case "forward" -> () -> mapper.forward(srcSample.get());
        case "backward" -> () -> mapper.backward(tgtSample.get());
        default -> () -> mapper.patch(srcSample.get(), tgtSample.get());
      };
      for (final var side : List.of(Map.entry("generated", generated), Map.entry("reflective", reflective))) {
        final var shape = linked(side.getValue());
        if (!shape.equals(operation.getValue())) {
          failures.add(operation.getKey() + ": " + side.getKey() + " gave " + shape + ", owed " + operation.getValue());
        }
      }
    }
    assertTrue(failures.isEmpty(), () -> String.join("\n  ", failures));
  }

  /**
   * A superclass whose key is a type variable every row fixes to {@code String} and whose first and
   * items are typed by a variable the subclasses pass through or fix. {@code %s} is the row's
   * prefix.
   */
  private static final String PASS_THROUGH_BASE =
    "public class %sBase<K, V> {\n" +
    "  private K key;\n" +
    "  private V first;\n" +
    "  private java.util.List<V> items;\n" +
    "  public K getKey() { return key; }\n" +
    "  public void setKey(final K key) { this.key = key; }\n" +
    "  public V getFirst() { return first; }\n" +
    "  public void setFirst(final V first) { this.first = first; }\n" +
    "  public java.util.List<V> getItems() { return items; }\n" +
    "  public void setItems(final java.util.List<V> items) { this.items = items; }\n" +
    "}\n";

  /**
   * The base, the source {@code srcDecl} bridged to the target {@code tgtDecl}, compiled without
   * the processor and with it. Each declaration names its class {@code %sSrc} or {@code %sTgt}.
   */
  private static List<ProcessorHarness.Compilation> passThroughPair(
    final String prefix,
    final String srcDecl,
    final String tgtDecl
  ) {
    final var head = "package " + PACKAGE + ";\n";
    final var sources = new JavaFileObject[] {
      source(prefix + "Base", head + PASS_THROUGH_BASE.replace("%s", prefix)),
      source(
        prefix + "Src",
        head + "@io.github.eschizoid.telescope.annotations.Bridge(%sTgt.class)\n".replace("%s", prefix) + srcDecl
      ),
      source(prefix + "Tgt", head + tgtDecl),
    };
    final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources);
    assertTrue(plain.success(), () -> prefix + ": the pair should compile: " + plain.errorMessages());
    return List.of(plain, bridged(sources));
  }

  @Test
  @DisplayName(
    "a property a generic subclass passes through to its superclass converts the same way on both paths, with no warning"
  )
  void aPropertyASubclassPassesThroughConvertsTheSameWayOnBothPaths() throws ReflectiveOperationException {
    // Seen from a subclass that passes its own variable through, first and items are typed by that
    // variable, which no static method of the bridge can name. Both paths read them as the
    // superclass declares them: one V, so first passes through and items is copied into a fresh
    // list. In each row one side passes its own variable through, and the other passes its own
    // through as well or fixes the variable to String. The bridge names a generic class raw, so
    // the key, which every row fixes to String, reads back from a generic side as Object.
    final var pairs = List.of(
      List.of(
        "Ptv",
        "public class PtvSrc<X> extends PtvBase<String, X> {}\n",
        "public class PtvTgt<Y> extends PtvBase<String, Y> {}\n"
      ),
      List.of(
        "Ptx",
        "public class PtxSrc<X> extends PtxBase<String, X> {}\n",
        "public class PtxTgt extends PtxBase<String, String> {}\n"
      ),
      List.of(
        "Pts",
        "public class PtsSrc extends PtsBase<String, String> {}\n",
        "public class PtsTgt<Y> extends PtsBase<String, Y> {}\n"
      )
    );
    final var owed = List.of(
      Map.entry("forward", "key=k, first=a, items=[b, c] in a fresh ArrayList"),
      Map.entry("backward", "key=q, first=x, items=[y] in a fresh ArrayList"),
      Map.entry("patch", "key=q, first=x, items=[y] in a fresh ArrayList")
    );
    final var failures = new ArrayList<String>();
    for (final var pair : pairs) {
      final var prefix = pair.get(0);
      final var compiled = passThroughPair(prefix, pair.get(1), pair.get(2));
      final var plain = compiled.get(0);
      final var processed = compiled.get(1);
      if (!processed.success()) {
        failures.add(prefix + ": the generated bridge should compile: " + processed.errorMessages());
        continue;
      }
      final var classes = plain.define(MethodHandles.lookup());
      final Class<Object> source = cast(classes.get(PACKAGE + "." + prefix + "Src"));
      final Class<Object> target = cast(classes.get(PACKAGE + "." + prefix + "Tgt"));
      final var nested = nestedBridgesFirst(processed, plain, prefix);
      final var mapper = Telescope.mapper(source, target);
      final var bridge = emitted(processed, plain, prefix, nested);
      for (final var operation : owed) {
        for (final var side : List.of("generated", "reflective")) {
          final var srcSample = passThroughSample(source, "k", "a", List.of("b", "c"));
          final var tgtSample = passThroughSample(target, "q", "x", List.of("y"));
          final Attempt attempt = switch (operation.getKey() + " " + side) {
            case "forward generated" -> () -> bridge.getMethod("forward", source).invoke(null, srcSample);
            case "backward generated" -> () -> bridge.getMethod("backward", target).invoke(null, tgtSample);
            case "patch generated" -> () ->
              bridge.getMethod("patch", source, target).invoke(null, srcSample, tgtSample);
            case "forward reflective" -> () -> mapper.forward(srcSample);
            case "backward reflective" -> () -> mapper.backward(tgtSample);
            default -> () -> mapper.patch(srcSample, tgtSample);
          };
          final var shape = passedThrough(attempt, srcSample, tgtSample);
          if (!shape.equals(operation.getValue())) {
            failures.add(
              prefix + " " + operation.getKey() + ": " + side + " gave " + shape + ", owed " + operation.getValue()
            );
          }
        }
      }
    }
    assertTrue(failures.isEmpty(), () -> String.join("\n  ", failures));
  }

  @Test
  @DisplayName("a dropped property a generic subclass passes through compiles with no warning")
  void aDroppedPassedThroughPropertyCompilesWithNoWarning() {
    final var head = "package " + PACKAGE + ";\n";
    // Backward fills the dropped property with a default and patch copies it from base, both
    // through the raw source, whose setter takes the erasure of List<T>.
    final var processed = bridged(
      source(
        "PtdBase",
        head +
          "public class PtdBase<T> {\n" +
          "  private String name;\n" +
          "  private java.util.List<T> extra;\n" +
          "  public String getName() { return name; }\n" +
          "  public void setName(final String name) { this.name = name; }\n" +
          "  public java.util.List<T> getExtra() { return extra; }\n" +
          "  public void setExtra(final java.util.List<T> extra) { this.extra = extra; }\n" +
          "}\n"
      ),
      source(
        "PtdSrc",
        head +
          "@io.github.eschizoid.telescope.annotations.Bridge(value = PtdTgt.class, drops = {\"extra\"})\n" +
          "public class PtdSrc<X> extends PtdBase<X> {}\n"
      ),
      source(
        "PtdTgt",
        head +
          "public class PtdTgt {\n" +
          "  private String name;\n" +
          "  public String getName() { return name; }\n" +
          "  public void setName(final String name) { this.name = name; }\n" +
          "}\n"
      )
    );
    assertTrue(processed.success(), () -> "the generated bridge should compile: " + processed.errorMessages());
  }

  /** A bean declaring {@code %s}, with one {@code String} property. */
  private static final String NAMED_BEAN =
    "public class %s {\n" +
    "  private String name;\n" +
    "  public String getName() { return name; }\n" +
    "  public void setName(final String name) { this.name = name; }\n" +
    "}\n";

  @Test
  @DisplayName(
    "a bridge over a generic class compiles with no warning and converts as the runtime does, in every form a bridge takes"
  )
  void aBridgeOverAGenericClassCompilesWithNoWarning() throws ReflectiveOperationException {
    final var head = "package " + PACKAGE + ";\n";
    final var bridge = "@io.github.eschizoid.telescope.annotations.Bridge(";
    final var pairs = List.of(
      List.of("GwsSrc<X>", "GwsTgt"),
      List.of("GwtSrc", "GwtTgt<Y>"),
      List.of("GwbSrc<X>", "GwbTgt<Y>")
    );
    final var failures = new ArrayList<String>();
    for (final var pair : pairs) {
      final var src = pair.get(0).replaceAll("<.*", "");
      final var tgt = pair.get(1).replaceAll("<.*", "");
      final var sources = new JavaFileObject[] {
        source(src, head + bridge + tgt + ".class)\n" + NAMED_BEAN.formatted(pair.get(0))),
        source(tgt, head + NAMED_BEAN.formatted(pair.get(1))),
      };
      final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources);
      assertTrue(plain.success(), () -> src + ": the pair should compile: " + plain.errorMessages());
      final var processed = bridged(sources);
      assertTrue(processed.success(), () -> src + ": the bridge should compile: " + processed.errorMessages());
      final var classes = plain.define(MethodHandles.lookup());
      final Class<Object> source = cast(classes.get(PACKAGE + "." + src));
      final Class<Object> target = cast(classes.get(PACKAGE + "." + tgt));
      final var sample = source.getConstructor().newInstance();
      source.getMethod("setName", String.class).invoke(sample, "a");
      final var cellPrefix = src.replace("Src", "");
      final var nested = nestedBridgesFirst(processed, plain, cellPrefix);
      final var getName = target.getMethod("getName");
      final var viaRuntime = getName.invoke(Telescope.mapper(source, target).forward(sample));
      final var generated = emitted(processed, plain, cellPrefix, nested).getMethod("forward", source);
      final var viaBridge = getName.invoke(generated.invoke(null, sample));
      if (!"a".equals(viaBridge) || !"a".equals(viaRuntime)) {
        failures.add(src + ": the bridge gave " + viaBridge + ", the runtime " + viaRuntime);
      }
    }
    assertTrue(failures.isEmpty(), () -> String.join("\n  ", failures));

    // The carrier form and a sealed root reach the same emission through their own paths.
    final var carried = bridged(
      source("GwcSrc", head + NAMED_BEAN.formatted("GwcSrc<X>")),
      source("GwcTgt", head + NAMED_BEAN.formatted("GwcTgt<Y>")),
      source(
        "GwcCarrier",
        head + bridge + "source = GwcSrc.class, target = GwcTgt.class)\npublic final class GwcCarrier {}\n"
      )
    );
    assertTrue(carried.success(), () -> "the carrier bridge should compile: " + carried.errorMessages());
    final var sealed = bridged(
      source(
        "GwrSrc",
        head + bridge + "GwrTgt.class)\npublic sealed interface GwrSrc<T> permits GwrCase, GwrFixed {}\n"
      ),
      source(
        "GwrCase",
        head + bridge + "GwrCaseDto.class)\npublic record GwrCase<T>(String name) implements GwrSrc<T> {}\n"
      ),
      source(
        "GwrFixed",
        head + bridge + "GwrFixedDto.class)\npublic record GwrFixed(String name) implements GwrSrc<String> {}\n"
      ),
      source("GwrTgt", head + "public sealed interface GwrTgt<U> permits GwrCaseDto, GwrFixedDto {}\n"),
      source("GwrCaseDto", head + "public record GwrCaseDto<U>(String name) implements GwrTgt<U> {}\n"),
      source("GwrFixedDto", head + "public record GwrFixedDto(String name) implements GwrTgt<String> {}\n")
    );
    assertTrue(sealed.success(), () -> "the sealed bridge should compile: " + sealed.errorMessages());
    // A root declaring no type parameters can still permit a generic case, which its patch names.
    final var genericCase = bridged(
      source("GwkSrc", head + bridge + "GwkTgt.class)\npublic sealed interface GwkSrc permits GwkCase {}\n"),
      source(
        "GwkCase",
        head + bridge + "GwkCaseDto.class)\npublic record GwkCase<T>(String name) implements GwkSrc {}\n"
      ),
      source("GwkTgt", head + "public sealed interface GwkTgt permits GwkCaseDto {}\n"),
      source("GwkCaseDto", head + "public record GwkCaseDto<U>(String name) implements GwkTgt {}\n")
    );
    assertTrue(genericCase.success(), () -> "the sealed bridge should compile: " + genericCase.errorMessages());
    // Only the source's case is generic, and the patch names it in its case tests.
    final var genericSourceCase = bridged(
      source("BsSrc", head + bridge + "BsTgt.class)\npublic sealed interface BsSrc permits BsCase {}\n"),
      source("BsCase", head + bridge + "BsCaseDto.class)\npublic record BsCase<T>(String name) implements BsSrc {}\n"),
      source("BsTgt", head + "public sealed interface BsTgt permits BsCaseDto {}\n"),
      source("BsCaseDto", head + "public record BsCaseDto(String name) implements BsTgt {}\n")
    );
    assertTrue(
      genericSourceCase.success(),
      () -> "the sealed bridge should compile: " + genericSourceCase.errorMessages()
    );
    // Only the target's case is generic, and the patch names it in its case tests.
    final var genericTargetCase = bridged(
      source("BcSrc", head + bridge + "BcTgt.class)\npublic sealed interface BcSrc permits BcCase {}\n"),
      source("BcCase", head + bridge + "BcCaseDto.class)\npublic record BcCase(String name) implements BcSrc {}\n"),
      source("BcTgt", head + "public sealed interface BcTgt permits BcCaseDto {}\n"),
      source("BcCaseDto", head + "public record BcCaseDto<U>(String name) implements BcTgt {}\n")
    );
    assertTrue(
      genericTargetCase.success(),
      () -> "the sealed bridge should compile: " + genericTargetCase.errorMessages()
    );
  }

  @Test
  @DisplayName("a caller holding a generic class with or without arguments uses each member of its bridge")
  void aCallerUsesEveryMemberOfABridgeOverAGenericClass() {
    final var head = "package " + PACKAGE + ";\n";
    // Each member names both classes raw, which a caller can assign from and pass a value of either
    // form to. A wildcard in a field's type or a method's return type would refuse a line below.
    final var caller =
      head +
      "import io.github.eschizoid.telescope.Telescope;\n" +
      "import io.github.eschizoid.telescope.conversion.BridgeFn;\n" +
      "@SuppressWarnings({\"rawtypes\", \"unchecked\"})\n" +
      "public class GwuCaller {\n" +
      "  static void use(final GwuSrc<String> typed, final GwuSrc raw, final GwuTgt<Integer> dto) {\n" +
      "    final BridgeFn<GwuSrc, GwuTgt> fn = GwuSrcBridge.BRIDGE_FN;\n" +
      "    final Telescope<GwuSrc, GwuTgt> path = GwuSrcBridge.BRIDGE;\n" +
      "    final GwuTgt<Integer> forward = GwuSrcBridge.forward(typed);\n" +
      "    final GwuTgt rawForward = GwuSrcBridge.forward(raw);\n" +
      "    final GwuSrc<String> backward = GwuSrcBridge.backward(dto);\n" +
      "    final GwuSrc<String> patched = GwuSrcBridge.patch(typed, dto);\n" +
      "    final java.util.function.Function<GwuSrc<String>, GwuTgt<Integer>> ref = GwuSrcBridge::forward;\n" +
      "    final GwuTgt<Integer> read = path.read(typed);\n" +
      "    final GwuTgt<Integer> applied = fn.forward(typed);\n" +
      "  }\n" +
      "}\n";
    final var compilation = bridged(
      source(
        "GwuSrc",
        head + "@io.github.eschizoid.telescope.annotations.Bridge(GwuTgt.class)\n" + NAMED_BEAN.formatted("GwuSrc<X>")
      ),
      source("GwuTgt", head + NAMED_BEAN.formatted("GwuTgt<Y>")),
      source("GwuCaller", caller)
    );
    assertTrue(compilation.success(), () -> "the caller should compile: " + compilation.errorMessages());
  }

  /**
   * A fresh {@code type} holding {@code key}, {@code first} and a mutable copy of {@code items}.
   */
  private static Object passThroughSample(
    final Class<?> type,
    final Object key,
    final Object first,
    final List<String> items
  ) throws ReflectiveOperationException {
    final var sample = type.getConstructor().newInstance();
    type.getMethod("setKey", Object.class).invoke(sample, key);
    type.getMethod("setFirst", Object.class).invoke(sample, first);
    type.getMethod("setItems", List.class).invoke(sample, new ArrayList<>(items));
    return sample;
  }

  /**
   * What {@code attempt} produced, read through the inherited getters: the values, the class of the
   * items list, and whether that list is one of the inputs' rather than a fresh one.
   */
  private static String passedThrough(final Attempt attempt, final Object... inputs) {
    try {
      final var out = attempt.get();
      final var key = out.getClass().getMethod("getKey").invoke(out);
      final var first = out.getClass().getMethod("getFirst").invoke(out);
      final var items = out.getClass().getMethod("getItems").invoke(out);
      boolean shared = false;
      for (final var input : inputs) shared |= input.getClass().getMethod("getItems").invoke(input) == items;
      final var held = shared ? "an input's " : "a fresh ";
      return (
        "key=" + key + ", first=" + first + ", items=" + items + " in " + held + items.getClass().getSimpleName()
      );
    } catch (final InvocationTargetException e) {
      return REFUSED + (e.getCause() == null ? e : e.getCause()).getClass().getSimpleName();
    } catch (final ReflectiveOperationException | RuntimeException e) {
      return REFUSED + e.getClass().getSimpleName();
    }
  }

  /**
   * Inputs on which the two paths convert a pass-through pair differently, each with what the
   * generated path and then the runtime give. Neither path checks a value against the class the
   * receiving side fixes the variable to. The generated path writes through a cast to that class,
   * which throws a bare {@code ClassCastException}; the runtime writes the value as it is, and the
   * property then holds a value of a class its type does not admit.
   *
   * <p>An entry no row exercises, or whose row both paths start to agree on, fails the test.
   */
  private static final Map<String, List<String>> UNCHECKED_WRITE_DIVERGENCES = Map.of(
    // A defect still to close: neither path names the property when the value does not fit.
    "an Integer into a property the target fixes to String",
    List.of(REFUSED + "ClassCastException", "first=42, a java.lang.Integer")
  );

  @Test
  @DisplayName("a value that does not fit the class a side fixes the variable to is a registered divergence")
  void aValueThatDoesNotFitTheFixedClassIsARegisteredDivergence() throws ReflectiveOperationException {
    final var compiled = passThroughPair(
      "Ptw",
      "public class PtwSrc<X> extends PtwBase<String, X> {}\n",
      "public class PtwTgt extends PtwBase<String, String> {}\n"
    );
    final var plain = compiled.get(0);
    final var processed = compiled.get(1);
    assertTrue(processed.success(), () -> "the generated bridge should compile: " + processed.errorMessages());
    final var classes = plain.define(MethodHandles.lookup());
    final Class<Object> source = cast(classes.get(PACKAGE + ".PtwSrc"));
    final Class<Object> target = cast(classes.get(PACKAGE + ".PtwTgt"));
    final var nested = nestedBridgesFirst(processed, plain, "Ptw");
    final var mapper = Telescope.mapper(source, target);
    final var bridge = emitted(processed, plain, "Ptw", nested);
    final var name = "an Integer into a property the target fixes to String";
    final Attempt generated = () ->
      bridge.getMethod("forward", source).invoke(null, passThroughSample(source, "k", 42, List.of()));
    final Attempt reflective = () -> mapper.forward(passThroughSample(source, "k", 42, List.of()));
    final var seen = List.of(firstHeld(generated), firstHeld(reflective));
    assertTrue(
      UNCHECKED_WRITE_DIVERGENCES.keySet().equals(Set.of(name)),
      () -> "registered divergences with no row: " + UNCHECKED_WRITE_DIVERGENCES.keySet()
    );
    assertTrue(
      seen.equals(UNCHECKED_WRITE_DIVERGENCES.get(name)),
      () -> name + ": generated and runtime gave " + seen + ", registered " + UNCHECKED_WRITE_DIVERGENCES.get(name)
    );
  }

  /**
   * A bean {@code %1$s<name>} whose {@code value} is read as {@code String} and has two setters,
   * one for {@code String} and one for {@code Object}, each recording which one wrote it. Its
   * {@code sample()} holds {@code held}, set without either setter.
   */
  private static String overloadedValueBean(final String name, final String held) {
    return (
      "public class %1$s" +
      name +
      " {\n" +
      "  private String value;\n" +
      "  public %1$s" +
      name +
      "() {}\n" +
      "  public String getValue() { return value; }\n" +
      "  public void setValue(final String v) { this.value = \"String:\" + v; }\n" +
      "  public void setValue(final Object v) { this.value = \"Object:\" + v; }\n" +
      "  public static %1$s" +
      name +
      " sample() { final var b = new %1$s" +
      name +
      "(); b.value = \"" +
      held +
      "\"; return b; }\n}\n"
    );
  }

  /**
   * A transform result of a type wider than the property's, written into a bean whose setters are
   * overloaded for the property's type and for the result's: the declarations, each with {@code
   * %1$s} as the row's prefix and the last of them the runtime row, and what both paths owe.
   */
  private static final List<VariableTransform> SETTER_TRANSFORMS = List.of(
    new VariableTransform(
      "a forward result wider than the target property's type",
      true,
      List.of(
        transformedValue(true) + valueBean("%1$sSrc", "String", "\"x\""),
        overloadedValueBean("Tgt", "q"),
        transformFn("String", "Object", "a", "String.valueOf(b)"),
        runtimeRow(true, "getValue", "String", "Object")
      ),
      "#0(value=\"String:x\")",
      null,
      null
    ),
    new VariableTransform(
      "a backward result wider than the source property's type",
      false,
      List.of(
        transformedValue(false) + overloadedValueBean("Src", "x"),
        valueBean("%1$sTgt", "String", "\"q\""),
        transformFn("Object", "String", "String.valueOf(a)", "b"),
        runtimeRow(false, "getValue", "Object", "String")
      ),
      "#0(value=\"x\")",
      "#0(value=\"String:q\")",
      "#0(value=\"String:q\")"
    ),
    // The bridge names a generic class raw, where the inherited setValue(T) takes Object; a
    // String
    // argument would bind setValue(CharSequence) there instead.
    new VariableTransform(
      "a forward result wider than the property's type, into a generic target whose setter is inherited",
      true,
      List.of(
        transformedValue(true) + valueBean("%1$sSrc", "String", "\"x\""),
        "public class %1$sBase<T> {\n" +
          "  protected String value;\n" +
          "  public void setValue(final T v) { this.value = \"T:\" + v; }\n}\n",
        "public class %1$sTgt<Y> extends %1$sBase<String> {\n" +
          "  public String getValue() { return value; }\n" +
          "  public void setValue(final CharSequence v) { this.value = \"CharSequence:\" + v; }\n" +
          "  public static %1$sTgt<Object> sample() { final var t = new %1$sTgt<Object>(); t.value = \"q\"; return t; }\n}\n",
        transformFn("String", "CharSequence", "a", "String.valueOf(b)"),
        runtimeRow(true, "getValue", "String", "CharSequence")
      ),
      "#0(value=\"T:x\")",
      null,
      null
    ),
    new VariableTransform(
      "a forward result wider than a property whose type has type arguments",
      true,
      List.of(
        transformedValue(true) + valueBean("%1$sSrc", "String", "\"x\""),
        "public class %1$sTgt {\n" +
          "  private java.util.List<String> value;\n" +
          "  public %1$sTgt() {}\n" +
          "  public java.util.List<String> getValue() { return value; }\n" +
          "  public void setValue(final java.util.List<String> v) { this.value = v; }\n" +
          "  public void setValue(final Object v) {" +
          " this.value = java.util.List.of(\"Object:\" + v); }\n" +
          "  public static %1$sTgt sample() { return new %1$sTgt(); }\n}\n",
        transformFn("String", "Object", "java.util.List.of(a)", "String.valueOf(b)"),
        runtimeRow(true, "getValue", "String", "Object")
      ),
      "#0(value=[\"x\"])",
      null,
      null
    )
  );

  @Test
  @DisplayName("a transform result is written through the setter the property's type chooses, on both paths")
  void aTransformResultIsWrittenThroughTheSetterThePropertysTypeChooses() throws ReflectiveOperationException {
    final var failures = new ArrayList<String>();
    var index = 0;
    for (final var row : SETTER_TRANSFORMS) {
      final var prefix = "Ts" + index++;
      final var head = "package " + PACKAGE + ";\n";
      final var all = row
        .declarations()
        .stream()
        .map(declaration -> declaration.formatted(prefix))
        .map(code -> source(declaredName(code), head + code))
        .toList();
      final var plain = ProcessorHarness.compileFully(List.of(), List.of(), all.toArray(JavaFileObject[]::new));
      assertTrue(
        plain.success(),
        () -> row.name() + ": the pair and its runtime row should compile: " + plain.errorMessages()
      );
      final var processed = bridged(all.subList(0, all.size() - 1).toArray(JavaFileObject[]::new));
      if (!processed.success()) {
        failures.add(row.name() + ": the generated bridge should compile: " + processed.errorMessages());
        continue;
      }
      final var classes = plain.define(MethodHandles.lookup());
      final Class<Object> src = cast(classes.get(PACKAGE + "." + prefix + "Src"));
      final Class<Object> tgt = cast(classes.get(PACKAGE + "." + prefix + "Tgt"));
      final var nested = nestedBridgesFirst(processed, plain, prefix);
      final var runtime = classes.get(PACKAGE + "." + prefix + "Runtime").getMethod("mapper").invoke(null);
      @SuppressWarnings("unchecked")
      final Mapper<Object, Object> mapper = row.forwardOnly() ? null : (Mapper<Object, Object>) runtime;
      @SuppressWarnings("unchecked")
      final ForwardMapper<Object, Object> forwardMapper = row.forwardOnly()
        ? (ForwardMapper<Object, Object>) runtime
        : null;
      final var bridge = emitted(processed, plain, prefix, nested);
      final Attempt srcSample = () -> src.getMethod("sample").invoke(null);
      final Attempt tgtSample = () -> tgt.getMethod("sample").invoke(null);
      final var owed = new ArrayList<Map.Entry<String, String>>();
      owed.add(Map.entry("forward", row.forward()));
      if (row.backward() != null) owed.add(Map.entry("backward", row.backward()));
      if (row.patch() != null) owed.add(Map.entry("patch", row.patch()));
      for (final var operation : owed) {
        final Attempt generated = switch (operation.getKey()) {
          case "forward" -> () -> bridge.getMethod("forward", src).invoke(null, srcSample.get());
          case "backward" -> () -> bridge.getMethod("backward", tgt).invoke(null, tgtSample.get());
          default -> () -> bridge.getMethod("patch", src, tgt).invoke(null, srcSample.get(), tgtSample.get());
        };
        final Attempt reflective = switch (operation.getKey()) {
          case "forward" -> forwardMapper != null
            ? () -> forwardMapper.forward(srcSample.get())
            : () -> mapper.forward(srcSample.get());
          case "backward" -> () -> mapper.backward(tgtSample.get());
          default -> () -> mapper.patch(srcSample.get(), tgtSample.get());
        };
        for (final var side : List.of(Map.entry("generated", generated), Map.entry("reflective", reflective))) {
          final var shape = shapeOf(side.getValue());
          if (!shape.equals(operation.getValue())) {
            failures.add(
              row.name() +
                ", " +
                operation.getKey() +
                ": " +
                side.getKey() +
                " gave " +
                shape +
                ", owed " +
                operation.getValue()
            );
          }
        }
      }
    }
    assertTrue(failures.isEmpty(), () -> String.join("\n  ", failures));
  }

  /**
   * Shapes only the generated path can express, each with what the generated bridge writes. A
   * runtime row's function returns a type the property's own type reaches, so a result of any other
   * type has no runtime row to agree with; the row's test also holds javac to refusing that row.
   *
   * <p>An entry no row exercises, or whose row the generated path stops giving, fails the test.
   */
  private static final Map<String, String> GENERATED_ONLY_CAPABILITIES = Map.of(
    // Inherent to the runtime row's typing: an accessor reading a String property cannot be
    // typed
    // to an Integer, so no runtime row returns one. The bridge passes the Integer as it is,
    // and
    // javac binds the setter overload that takes it.
    "a transform result of a type the property's type does not reach",
    "Object:7"
  );

  @Test
  @DisplayName("a generated-only capability gives what its register records, and no runtime row can be typed for it")
  void aGeneratedOnlyCapabilityGivesWhatItsRegisterRecords() throws ReflectiveOperationException {
    final var name = "a transform result of a type the property's type does not reach";
    final var prefix = "Goc";
    final var head = "package " + PACKAGE + ";\n";
    final var declarations = List.of(
      transformedValue(true) + valueBean("%1$sSrc", "String", "\"x\""),
      overloadedValueBean("Tgt", "q"),
      transformFn("String", "Integer", "7", "String.valueOf(b)")
    );
    final var sources = declarations
      .stream()
      .map(declaration -> declaration.formatted(prefix))
      .map(code -> source(declaredName(code), head + code))
      .toList();
    final var compilation = bridged(sources.toArray(JavaFileObject[]::new));
    assertTrue(compilation.success(), compilation::errorMessages);
    final var classes = compilation.define(MethodHandles.lookup());
    final var source = classes.get(PACKAGE + ".GocSrc");
    final var target = classes.get(PACKAGE + ".GocTgt");
    final var built = classes
      .get(PACKAGE + ".GocSrcBridge")
      .getMethod("forward", source)
      .invoke(null, source.getMethod("sample").invoke(null));
    final var written = String.valueOf(target.getMethod("getValue").invoke(built));
    assertTrue(
      GENERATED_ONLY_CAPABILITIES.keySet().equals(Set.of(name)),
      () -> "registered capabilities with no row: " + GENERATED_ONLY_CAPABILITIES.keySet()
    );
    assertTrue(
      written.equals(GENERATED_ONLY_CAPABILITIES.get(name)),
      () -> name + ": the bridge wrote " + written + ", registered " + GENERATED_ONLY_CAPABILITIES.get(name)
    );
    final var withRow = new ArrayList<>(sources);
    withRow.add(source("GocRuntime", head + runtimeRow(true, "getValue", "String", "Integer").formatted(prefix)));
    final var runtime = ProcessorHarness.compileFully(List.of(), List.of(), withRow.toArray(JavaFileObject[]::new));
    assertTrue(
      !runtime.success() &&
        runtime
          .errors()
          .stream()
          .allMatch(e -> e.getSource() != null && e.getSource().getName().endsWith("GocRuntime.java")),
      () -> name + ": javac owed a refusal of the runtime row alone, saw " + describe(runtime)
    );
  }

  /** What {@code attempt}'s result holds as first, with that value's class, or the refusal. */
  private static String firstHeld(final Attempt attempt) {
    try {
      final var out = attempt.get();
      final var first = out.getClass().getMethod("getFirst").invoke(out);
      return "first=" + first + ", a " + first.getClass().getName();
    } catch (final InvocationTargetException e) {
      return REFUSED + (e.getCause() == null ? e : e.getCause()).getClass().getSimpleName();
    } catch (final ReflectiveOperationException | RuntimeException e) {
      return REFUSED + e.getClass().getSimpleName();
    }
  }

  /**
   * Two instances of {@code type}, named {@code first} and {@code second}, each the other's next.
   */
  private static Object chain(final Class<?> type, final String first, final String second)
    throws ReflectiveOperationException {
    final var a = type.getConstructor().newInstance();
    final var b = type.getConstructor().newInstance();
    type.getMethod("setName", String.class).invoke(a, first);
    type.getMethod("setName", String.class).invoke(b, second);
    type.getMethod("setNext", type.getSuperclass()).invoke(a, b);
    type.getMethod("setNext", type.getSuperclass()).invoke(b, a);
    return a;
  }

  /**
   * What {@code attempt} produced, rendered through the inherited {@code getName} and {@code
   * getNext}, with each node numbered where it first appears and referred back to by number.
   */
  private static String linked(final Attempt attempt) {
    try {
      final var numbered = new IdentityHashMap<Object, Integer>();
      return linked(attempt.get(), numbered);
    } catch (final InvocationTargetException e) {
      return REFUSED + (e.getCause() == null ? e : e.getCause()).getClass().getSimpleName();
    } catch (final ReflectiveOperationException | RuntimeException | StackOverflowError e) {
      return REFUSED + e.getClass().getSimpleName();
    }
  }

  private static String linked(final Object node, final IdentityHashMap<Object, Integer> numbered)
    throws ReflectiveOperationException {
    if (node == null) return "null";
    final var seen = numbered.get(node);
    if (seen != null) return "@" + seen;
    final var number = numbered.size();
    numbered.put(node, number);
    final var name = node.getClass().getMethod("getName").invoke(node);
    final var next = node.getClass().getMethod("getNext").invoke(node);
    return "#" + number + "(name=\"" + name + "\", next=" + linked(next, numbered) + ")";
  }

  /** The simple name of the one top-level type {@code code} declares. */
  private static String declaredName(final String code) {
    final var matcher = Pattern.compile(
      "public (?:sealed interface|interface|class|record|enum|@interface) (\\w+)"
    ).matcher(code);
    if (!matcher.find()) throw new IllegalArgumentException("no public type in " + code);
    return matcher.group(1);
  }

  /** The shape of what {@code attempt} produced, or {@link #REFUSED} and the throwable's class. */
  private static String shapeOf(final Attempt attempt) {
    try {
      return shape(attempt.get(), new IdentityHashMap<>());
    } catch (final InvocationTargetException e) {
      final var cause = e.getCause() == null ? e : e.getCause();
      return REFUSED + cause.getClass().getSimpleName();
    } catch (final ReflectiveOperationException | RuntimeException | StackOverflowError e) {
      return REFUSED + e.getClass().getSimpleName();
    }
  }

  /**
   * {@code value} rendered with each object of a cell's types numbered where it first appears and
   * referred back to by number afterwards, its properties in name order for a class and component
   * order for a record.
   */
  private static String shape(final Object value, final IdentityHashMap<Object, Integer> numbered)
    throws ReflectiveOperationException {
    if (value == null) return "null";
    if (value instanceof String text) return "\"" + text + "\"";
    if (value instanceof Number number) return number.toString();
    // A constant renders as its enum's name without the cell prefix, so a cell owes the enum a
    // conversion produced as well as the constant.
    if (value instanceof Enum<?> constant) {
      return constant.getDeclaringClass().getSimpleName().replaceFirst("^En\\d+", "") + "." + constant.name();
    }
    if (value instanceof Optional<?> present) {
      return present.isEmpty() ? "empty" : "of " + shape(present.get(), numbered);
    }
    if (value instanceof Map<?, ?> entries) {
      final var rendered = new ArrayList<String>();
      for (final var entry : entries.entrySet()) rendered.add(entry.getKey() + "=" + shape(entry.getValue(), numbered));
      return "{" + String.join(", ", rendered) + "}";
    }
    if (value instanceof Collection<?> elements) {
      final var rendered = new ArrayList<String>();
      for (final var element : elements) rendered.add(shape(element, numbered));
      return rendered.toString();
    }
    final var seen = numbered.get(value);
    if (seen != null) return "@" + seen;
    final var number = numbered.size();
    numbered.put(value, number);
    final var properties = new TreeMap<String, Method>();
    if (value.getClass().isRecord()) {
      for (final var component : value.getClass().getRecordComponents()) {
        properties.put(String.format("%03d", properties.size()) + component.getName(), component.getAccessor());
      }
    } else {
      for (final var method : value.getClass().getDeclaredMethods()) {
        if (method.getName().startsWith("get") && method.getParameterCount() == 0) {
          properties.put(Character.toLowerCase(method.getName().charAt(3)) + method.getName().substring(4), method);
        }
      }
    }
    final var parts = new ArrayList<String>();
    for (final var entry : properties.entrySet()) {
      final var key = value.getClass().isRecord() ? entry.getKey().substring(3) : entry.getKey();
      parts.add(key + "=" + shape(entry.getValue().invoke(value), numbered));
    }
    return "#" + number + "(" + String.join(", ", parts) + ")";
  }

  /**
   * A patch whose partial changes nothing: the declarations, each with {@code %1$s} as the cell's
   * prefix, and what both paths owe when {@code Tgt.blank()} is laid over {@code Src.sample()}. The
   * owed text is the result's {@link #shape} followed by every place in it that holds an object
   * {@code Src.sample()} also holds.
   *
   * <p>The root is always a new object. The rebuild is shallow, so a component the partial leaves
   * null is handed across from the base as it is, and a nested partial is written whole.
   */
  private record Unchanged(String name, List<String> declarations, String owed) {}

  private static final String SAMPLE_NAMED = """
      public static %1$sSrc sample() {
        final var s = new %1$sSrc();
        s.setName("ann");
        s.setEmail("ann@example.com");
        return s;
      }
    """;

  private static final String BLANK_BEAN = "  public static %1$sTgt blank() { return new %1$sTgt(); }\n";

  private static final List<Unchanged> UNCHANGED = List.of(
    new Unchanged(
      "a record",
      List.of(
        BRIDGE_TO_TGT +
          "public record %1$sSrc(String name, String email) {\n" +
          "  public static %1$sSrc sample() { return new %1$sSrc(\"ann\", \"ann@example.com\"); }\n" +
          "}\n",
        "public record %1$sTgt(String name, String email) {\n" +
          "  public static %1$sTgt blank() { return new %1$sTgt(null, null); }\n" +
          "}\n"
      ),
      "#0(name=\"ann\", email=\"ann@example.com\") sharing nothing"
    ),
    new Unchanged(
      "a class built through its setters",
      List.of(
        BRIDGE_TO_TGT + bean("%1$sSrc", SAMPLE_NAMED, "String name", "String email"),
        bean("%1$sTgt", BLANK_BEAN, "String name", "String email")
      ),
      "#0(email=\"ann@example.com\", name=\"ann\") sharing nothing"
    ),
    new Unchanged(
      "a class built through its builder",
      List.of(
        BRIDGE_TO_TGT +
          "public class %1$sSrc {\n" +
          "  private final String name;\n" +
          "  private final String email;\n" +
          "  private %1$sSrc(final String name, final String email) { this.name = name; this.email = email; }\n" +
          "  public String getName() { return name; }\n" +
          "  public String getEmail() { return email; }\n" +
          "  public static Builder builder() { return new Builder(); }\n" +
          "  public static %1$sSrc sample() { return builder().name(\"ann\").email(\"ann@example.com\").build(); }\n" +
          "  public static final class Builder {\n" +
          "    private String name;\n" +
          "    private String email;\n" +
          "    public Builder name(final String name) { this.name = name; return this; }\n" +
          "    public Builder email(final String email) { this.email = email; return this; }\n" +
          "    public %1$sSrc build() { return new %1$sSrc(name, email); }\n" +
          "  }\n" +
          "}\n",
        bean("%1$sTgt", BLANK_BEAN, "String name", "String email")
      ),
      "#0(email=\"ann@example.com\", name=\"ann\") sharing nothing"
    ),
    new Unchanged(
      "a record whose nested record the partial leaves null",
      List.of(
        BRIDGE_TO_TGT +
          "public record %1$sSrc(String name, %1$sHome home) {\n" +
          "  public static %1$sSrc sample() { return new %1$sSrc(\"ann\", new %1$sHome(\"Lima\")); }\n" +
          "}\n",
        "public record %1$sHome(String city) {}\n",
        "public record %1$sTgt(String name, %1$sHomeDto home) {\n" +
          "  public static %1$sTgt blank() { return new %1$sTgt(null, null); }\n" +
          "}\n",
        "public record %1$sHomeDto(String city) {}\n"
      ),
      "#0(name=\"ann\", home=#1(city=\"Lima\")) sharing [root.home]"
    ),
    new Unchanged(
      "a record whose nested partial has every field null",
      List.of(
        BRIDGE_TO_TGT +
          "public record %1$sSrc(String name, %1$sHome home) {\n" +
          "  public static %1$sSrc sample() { return new %1$sSrc(\"ann\", new %1$sHome(\"Lima\")); }\n" +
          "}\n",
        "public record %1$sHome(String city) {}\n",
        "public record %1$sTgt(String name, %1$sHomeDto home) {\n" +
          "  public static %1$sTgt blank() { return new %1$sTgt(null, new %1$sHomeDto(null)); }\n" +
          "}\n",
        "public record %1$sHomeDto(String city) {}\n"
      ),
      "#0(name=\"ann\", home=#1(city=null)) sharing nothing"
    ),
    new Unchanged(
      "a class whose nested class the partial leaves null",
      List.of(
        BRIDGE_TO_TGT +
          bean(
            "%1$sSrc",
            "  public static %1$sSrc sample() {\n" +
              "    final var s = new %1$sSrc();\n" +
              "    final var home = new %1$sHome();\n" +
              "    home.setCity(\"Lima\");\n" +
              "    s.setName(\"ann\");\n" +
              "    s.setHome(home);\n" +
              "    return s;\n" +
              "  }\n",
            "String name",
            "%1$sHome home"
          ),
        bean("%1$sHome", "", "String city"),
        bean("%1$sTgt", BLANK_BEAN, "String name", "%1$sHomeDto home"),
        bean("%1$sHomeDto", "", "String city")
      ),
      "#0(home=#1(city=\"Lima\"), name=\"ann\") sharing [root.home]"
    ),
    new Unchanged(
      "a record whose same-typed list the partial leaves null",
      List.of(
        BRIDGE_TO_TGT +
          "public record %1$sSrc(String name, java.util.List<String> tags) {\n" +
          "  public static %1$sSrc sample() {\n" +
          "    return new %1$sSrc(\"ann\", new java.util.ArrayList<>(java.util.List.of(\"a\")));\n" +
          "  }\n" +
          "}\n",
        "public record %1$sTgt(String name, java.util.List<String> tags) {\n" +
          "  public static %1$sTgt blank() { return new %1$sTgt(null, null); }\n" +
          "}\n"
      ),
      "#0(name=\"ann\", tags=[\"a\"]) sharing [root.tags]"
    ),
    new Unchanged(
      "a record and a null partial",
      List.of(
        BRIDGE_TO_TGT +
          "public record %1$sSrc(String name, int age) {\n" +
          "  public static %1$sSrc sample() { return new %1$sSrc(\"ann\", 7); }\n" +
          "}\n",
        "public record %1$sTgt(String name, int age) {\n" + "  public static %1$sTgt blank() { return null; }\n" + "}\n"
      ),
      "#0(name=\"ann\", age=7) sharing nothing"
    )
  );

  @Test
  @DisplayName("a patch whose partial changes nothing returns a new object on both paths")
  void aPatchThatChangesNothingReturnsANewObjectOnBothPaths() throws ReflectiveOperationException {
    final var failures = new ArrayList<String>();
    var index = 0;
    for (final var cell : UNCHANGED) {
      final var prefix = "Un" + index++;
      final var head = "package " + PACKAGE + ";\n";
      final var sources = cell
        .declarations()
        .stream()
        .map(declaration -> declaration.formatted(prefix))
        .map(code -> source(declaredName(code), head + code))
        .toArray(JavaFileObject[]::new);
      final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources);
      assertTrue(
        plain.success(),
        () -> cell.name() + " should compile without the processor: " + plain.errorMessages()
      );
      final var processed = bridged(sources);
      assertTrue(processed.success(), () -> cell.name() + " should compile: " + processed.errorMessages());
      final var classes = plain.define(MethodHandles.lookup());
      final Class<Object> src = cast(classes.get(PACKAGE + "." + prefix + "Src"));
      final Class<Object> tgt = cast(classes.get(PACKAGE + "." + prefix + "Tgt"));
      final var nested = nestedBridgesFirst(processed, plain, prefix);
      final var mapper = Telescope.mapper(src, tgt);
      final var bridge = emitted(processed, plain, prefix, nested);
      final var patch = bridge.getMethod("patch", src, tgt);
      final Map<String, Patch> sides = Map.of(
        "generated",
        (base, partial) -> patch.invoke(null, base, partial),
        "reflective",
        mapper::patch
      );
      for (final var side : sides.entrySet()) {
        final var base = src.getMethod("sample").invoke(null);
        final var partial = tgt.getMethod("blank").invoke(null);
        final var result = side.getValue().apply(base, partial);
        final var outcome = shape(result, new IdentityHashMap<>()) + " sharing " + shared(base, result);
        if (!outcome.equals(cell.owed())) failures.add(
          cell.name() + ": " + side.getKey() + " gave " + outcome + ", owed " + cell.owed()
        );
        if (result == base) failures.add(cell.name() + ": " + side.getKey() + " handed the base back");
      }
    }
    assertTrue(failures.isEmpty(), () -> failures.size() + " cell(s) failed:\n  " + String.join("\n  ", failures));
  }

  @Test
  @DisplayName("a sealed patch overlays only a partial of the same case, and returns a new object, on both paths")
  void aSealedPatchReturnsANewObjectWhenThePartialOverlaysNothing() throws ReflectiveOperationException {
    final var head = "package " + PACKAGE + ";\n";
    final var bridge = "@io.github.eschizoid.telescope.annotations.Bridge(";
    final var sources = new JavaFileObject[] {
      source("SpSrc", head + bridge + "SpTgt.class)\npublic sealed interface SpSrc permits SpCard, SpBank {}\n"),
      source("SpCard", head + bridge + "SpCardDto.class)\npublic record SpCard(String pan) implements SpSrc {}\n"),
      source("SpBank", head + bridge + "SpBankDto.class)\npublic record SpBank(String iban) implements SpSrc {}\n"),
      source("SpTgt", head + "public sealed interface SpTgt permits SpCardDto, SpBankDto {}\n"),
      source("SpCardDto", head + "public record SpCardDto(String pan) implements SpTgt {}\n"),
      source("SpBankDto", head + "public record SpBankDto(String iban) implements SpTgt {}\n"),
    };
    final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources);
    assertTrue(plain.success(), plain::errorMessages);
    final var processed = bridged(sources);
    assertTrue(processed.success(), processed::errorMessages);
    final var classes = plain.define(MethodHandles.lookup());
    final Class<Object> src = cast(classes.get(PACKAGE + ".SpSrc"));
    final Class<Object> tgt = cast(classes.get(PACKAGE + ".SpTgt"));
    final var nested = nestedBridgesFirst(processed, plain, "Sp");
    final var mapper = Telescope.mapper(src, tgt);
    final var patch = emitted(processed, plain, "Sp", nested).getMethod("patch", src, tgt);
    final var cardDto = classes.get(PACKAGE + ".SpCardDto").getConstructors()[0];
    final var bank = classes.get(PACKAGE + ".SpBankDto").getConstructors()[0].newInstance("DE89");
    // A partial of the same case overlays; another case or a null partial is ignored and the base
    // is rebuilt.
    final var partials = new LinkedHashMap<String, Object>();
    partials.put("the same case", cardDto.newInstance("5500"));
    partials.put("the same case, every field null", cardDto.newInstance((Object) null));
    partials.put("another case", bank);
    partials.put("null", null);
    final var owed = Map.of(
      "the same case",
      "#0(pan=\"5500\"), a new object",
      "the same case, every field null",
      "#0(pan=\"4111\"), a new object",
      "another case",
      "#0(pan=\"4111\"), a new object",
      "null",
      "#0(pan=\"4111\"), a new object"
    );
    final var failures = new ArrayList<String>();
    final Map<String, Patch> sides = Map.of(
      "generated",
      (base, partial) -> patch.invoke(null, base, partial),
      "reflective",
      mapper::patch
    );
    for (final var partial : partials.entrySet()) {
      for (final var side : sides.entrySet()) {
        final var card = classes.get(PACKAGE + ".SpCard").getConstructors()[0].newInstance("4111");
        String outcome;
        try {
          final var result = side.getValue().apply(card, partial.getValue());
          outcome = shape(result, new IdentityHashMap<>()) + (result == card ? ", the base itself" : ", a new object");
        } catch (final InvocationTargetException | RuntimeException e) {
          outcome = REFUSED + e;
        }
        if (!outcome.equals(owed.get(partial.getKey()))) failures.add(
          side.getKey() + ", " + partial.getKey() + ": " + outcome
        );
      }
    }
    assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
  }

  /**
   * A conversion between two sealed interfaces, at the root or below it: each declaration with
   * {@code %1$s} as the cell's prefix, and what both paths owe, rendered by {@link #shape}, for
   * {@code forward(Src.sample())}, {@code backward(Tgt.sample())} and {@code patch(Src.sample(),
   * Tgt.partial())}. Every case carries a {@code @Bridge} to the case it converts to, which is how
   * both paths pair the cases.
   */
  private record SealedCell(String name, List<String> declarations, String forward, String backward, String patch) {}

  private static final String SEALED_HEAD = "@io.github.eschizoid.telescope.annotations.Bridge(";

  /** The two cases of a sealed source {@code root} and their bridges to the target's cases. */
  private static List<String> sealedCases(final String root, final String bankBridge, final String bankDto) {
    return List.of(
      SEALED_HEAD + "%1$sCardDto.class)\npublic record %1$sCard(String pan) implements " + root + " {}\n",
      SEALED_HEAD + bankBridge + ")\npublic record %1$sBank(String iban, int rank) implements " + root + " {}\n",
      "public record %1$sCardDto(String pan) implements " + root + "Dto {}\n",
      "public record %1$sBankDto(" + bankDto + ", int rank) implements " + root + "Dto {}\n"
    );
  }

  /** A sealed source root and target root over {@link #sealedCases}, under the given names. */
  private static List<String> sealedRoots(final String source, final String target, final String extra) {
    return List.of(
      "public sealed interface " + source + " permits %1$sCard, %1$sBank {" + extra + "}\n",
      "public sealed interface " + target + " permits %1$sCardDto, %1$sBankDto {}\n"
    );
  }

  private static List<String> concat(final List<String> first, final List<String> second) {
    final var all = new ArrayList<>(first);
    all.addAll(second);
    return all;
  }

  private static final String BANK = "#0(iban=\"DE89\", rank=2)";

  private static final List<SealedCell> SEALED_CELLS = List.of(
    new SealedCell(
      "two sealed roots whose cases each carry a bridge to a case",
      concat(
        List.of(
          SEALED_HEAD +
            "%1$sTgt.class)\npublic sealed interface %1$sSrc permits %1$sCard, %1$sBank {\n" +
            "  static %1$sSrc sample() { return new %1$sBank(\"DE89\", 2); }\n}\n",
          "public sealed interface %1$sTgt permits %1$sCardDto, %1$sBankDto {\n" +
            "  static %1$sTgt sample() { return new %1$sCardDto(\"5500\"); }\n" +
            "  static %1$sTgt partial() { return new %1$sBankDto(null, 7); }\n}\n"
        ),
        sealedCases("%1$sSrc", "%1$sBankDto.class", "String iban")
          .stream()
          .map(d -> d.replace("%1$sSrcDto", "%1$sTgt"))
          .toList()
      ),
      BANK,
      "#0(pan=\"5500\")",
      "#0(iban=\"DE89\", rank=7)"
    ),
    new SealedCell(
      "two sealed roots, one case renaming a property through its own bridge",
      concat(
        List.of(
          SEALED_HEAD +
            "%1$sTgt.class)\npublic sealed interface %1$sSrc permits %1$sCard, %1$sBank {\n" +
            "  static %1$sSrc sample() { return new %1$sBank(\"DE89\", 2); }\n}\n",
          "public sealed interface %1$sTgt permits %1$sCardDto, %1$sBankDto {\n" +
            "  static %1$sTgt sample() { return new %1$sBankDto(\"NL01\", 1); }\n" +
            "  static %1$sTgt partial() { return new %1$sBankDto(\"NL02\", 7); }\n}\n"
        ),
        sealedCases(
          "%1$sSrc",
          "value = %1$sBankDto.class, renames = @io.github.eschizoid.telescope.annotations.Rename(" +
            "source = \"iban\", target = \"account\")",
          "String account"
        )
          .stream()
          .map(d -> d.replace("%1$sSrcDto", "%1$sTgt"))
          .toList()
      ),
      "#0(account=\"DE89\", rank=2)",
      "#0(iban=\"NL01\", rank=1)",
      "#0(iban=\"NL02\", rank=7)"
    ),
    new SealedCell(
      "two sealed roots, one case renaming a property through the bridge it declares beside one to another target",
      concat(
        List.of(
          SEALED_HEAD +
            "%1$sTgt.class)\npublic sealed interface %1$sSrc permits %1$sCard, %1$sBank {\n" +
            "  static %1$sSrc sample() { return new %1$sBank(\"DE89\", 2); }\n}\n",
          "public sealed interface %1$sTgt permits %1$sCardDto, %1$sBankDto {\n" +
            "  static %1$sTgt sample() { return new %1$sBankDto(\"NL01\", 1); }\n" +
            "  static %1$sTgt partial() { return new %1$sBankDto(\"NL02\", 7); }\n}\n",
          "public record %1$sBankView(String iban, int rank) {}\n"
        ),
        sealedCases(
          "%1$sSrc",
          "value = %1$sBankDto.class, renames = @io.github.eschizoid.telescope.annotations.Rename(" +
            "source = \"iban\", target = \"account\"))\n" +
            SEALED_HEAD +
            "%1$sBankView.class",
          "String account"
        )
          .stream()
          .map(d -> d.replace("%1$sSrcDto", "%1$sTgt"))
          .toList()
      ),
      "#0(account=\"DE89\", rank=2)",
      "#0(iban=\"NL01\", rank=1)",
      "#0(iban=\"NL02\", rank=7)"
    ),
    new SealedCell(
      "a sealed root in a field",
      concat(
        concat(
          sealedRoots("%1$sShape", "%1$sShapeDto", ""),
          sealedCases("%1$sShape", "%1$sBankDto.class", "String iban")
        ),
        List.of(
          BRIDGE_TO_TGT +
            "public record %1$sSrc(String title, %1$sShape main) {\n" +
            "  public static %1$sSrc sample() { return new %1$sSrc(\"t\", new %1$sBank(\"DE89\", 2)); }\n}\n",
          "public record %1$sTgt(String title, %1$sShapeDto main) {\n" +
            "  public static %1$sTgt sample() { return new %1$sTgt(\"u\", new %1$sCardDto(\"5500\")); }\n" +
            "  public static %1$sTgt partial() { return new %1$sTgt(null, new %1$sBankDto(\"NL01\", 1)); }\n}\n"
        )
      ),
      "#0(title=\"t\", main=#1(iban=\"DE89\", rank=2))",
      "#0(title=\"u\", main=#1(pan=\"5500\"))",
      "#0(title=\"t\", main=#1(iban=\"NL01\", rank=1))"
    ),
    new SealedCell(
      "sealed values in a List, an Optional and a Map",
      concat(
        concat(
          sealedRoots("%1$sShape", "%1$sShapeDto", ""),
          sealedCases("%1$sShape", "%1$sBankDto.class", "String iban")
        ),
        List.of(
          BRIDGE_TO_TGT +
            "public record %1$sSrc(java.util.List<%1$sShape> items, java.util.Optional<%1$sShape> spare," +
            " java.util.Map<String, %1$sShape> named) {\n" +
            "  public static %1$sSrc sample() {\n" +
            "    return new %1$sSrc(java.util.List.of(new %1$sBank(\"DE89\", 2), new %1$sCard(\"4111\"))," +
            " java.util.Optional.of(new %1$sCard(\"4222\")), java.util.Map.of(\"k\", new %1$sBank(\"FR76\", 3)));\n" +
            "  }\n}\n",
          "public record %1$sTgt(java.util.List<%1$sShapeDto> items, java.util.Optional<%1$sShapeDto> spare," +
            " java.util.Map<String, %1$sShapeDto> named) {\n" +
            "  public static %1$sTgt sample() {\n" +
            "    return new %1$sTgt(java.util.List.of(new %1$sCardDto(\"5500\")), java.util.Optional.empty()," +
            " java.util.Map.of(\"k\", new %1$sBankDto(\"NL01\", 1)));\n" +
            "  }\n" +
            "  public static %1$sTgt partial() {\n" +
            "    return new %1$sTgt(java.util.List.of(new %1$sBankDto(\"BE68\", 4)), null, null);\n" +
            "  }\n}\n"
        )
      ),
      "#0(items=[#1(iban=\"DE89\", rank=2), #2(pan=\"4111\")], spare=of #3(pan=\"4222\")," +
        " named={k=#4(iban=\"FR76\", rank=3)})",
      "#0(items=[#1(pan=\"5500\")], spare=empty, named={k=#2(iban=\"NL01\", rank=1)})",
      "#0(items=[#1(iban=\"BE68\", rank=4)], spare=of #2(pan=\"4222\"), named={k=#3(iban=\"FR76\", rank=3)})"
    ),
    new SealedCell(
      "a cycle through a case of a sealed field",
      List.of(
        "public sealed interface %1$sShape permits %1$sGroup, %1$sDot {}\n",
        "public sealed interface %1$sShapeDto permits %1$sGroupDto, %1$sDotDto {}\n",
        "@io.github.eschizoid.telescope.annotations.Bridge(%1$sGroupDto.class)\npublic final class %1$sGroup implements %1$sShape {\n  private %1$sSrc doc;\n  public %1$sSrc getDoc() { return doc; }\n  public void setDoc(final %1$sSrc doc) { this.doc = doc; }\n}\n",
        "@io.github.eschizoid.telescope.annotations.Bridge(%1$sDotDto.class)\npublic record %1$sDot(int x) implements %1$sShape {}\n",
        "public final class %1$sGroupDto implements %1$sShapeDto {\n  private %1$sTgt doc;\n  public %1$sTgt getDoc() { return doc; }\n  public void setDoc(final %1$sTgt doc) { this.doc = doc; }\n}\n",
        "public record %1$sDotDto(int x) implements %1$sShapeDto {}\n",
        "@io.github.eschizoid.telescope.annotations.Bridge(%1$sTgt.class)\npublic class %1$sSrc {\n  private %1$sShape s;\n  public %1$sShape getS() { return s; }\n  public void setS(final %1$sShape s) { this.s = s; }\n  public static %1$sSrc sample() {\n    final var d = new %1$sSrc();\n    final var g = new %1$sGroup();\n    g.setDoc(d);\n    d.setS(g);\n    return d;\n  }\n}\n",
        "public class %1$sTgt {\n  private %1$sShapeDto s;\n  public %1$sShapeDto getS() { return s; }\n  public void setS(final %1$sShapeDto s) { this.s = s; }\n  public static %1$sTgt sample() {\n    final var d = new %1$sTgt();\n    final var g = new %1$sGroupDto();\n    g.setDoc(d);\n    d.setS(g);\n    return d;\n  }\n  public static %1$sTgt partial() { return new %1$sTgt(); }\n}\n"
      ),
      "#0(s=#1(doc=null))",
      "#0(s=#1(doc=null))",
      "#0(s=#1(doc=#2(s=@1)))"
    ),
    new SealedCell(
      "a case holding its own sealed root",
      List.of(
        SEALED_HEAD +
          "%1$sTgt.class)\npublic sealed interface %1$sSrc permits %1$sLit, %1$sAdd {\n" +
          "  static %1$sSrc sample() {\n" +
          "    return new %1$sAdd(new %1$sLit(1), new %1$sAdd(new %1$sLit(2), new %1$sLit(3)));\n" +
          "  }\n}\n",
        "public sealed interface %1$sTgt permits %1$sLitDto, %1$sAddDto {\n" +
          "  static %1$sTgt sample() { return new %1$sAddDto(new %1$sLitDto(4), new %1$sLitDto(5)); }\n" +
          "  static %1$sTgt partial() { return new %1$sAddDto(new %1$sLitDto(9), null); }\n}\n",
        SEALED_HEAD + "%1$sLitDto.class)\npublic record %1$sLit(int value) implements %1$sSrc {}\n",
        SEALED_HEAD + "%1$sAddDto.class)\npublic record %1$sAdd(%1$sSrc left, %1$sSrc right) implements %1$sSrc {}\n",
        "public record %1$sLitDto(int value) implements %1$sTgt {}\n",
        "public record %1$sAddDto(%1$sTgt left, %1$sTgt right) implements %1$sTgt {}\n"
      ),
      "#0(left=#1(value=1), right=#2(left=#3(value=2), right=#4(value=3)))",
      "#0(left=#1(value=4), right=#2(value=5))",
      "#0(left=#1(value=9), right=#2(left=#3(value=2), right=#4(value=3)))"
    )
  );

  @Test
  @DisplayName("two sealed interfaces convert case by case to the same result on both paths, at the root and below it")
  void twoSealedInterfacesConvertTheSameOnBothPaths() throws ReflectiveOperationException {
    final var failures = new ArrayList<String>();
    var index = 0;
    for (final var cell : SEALED_CELLS) {
      final var prefix = "Sd" + index++;
      final var head = "package " + PACKAGE + ";\n";
      final var sources = cell
        .declarations()
        .stream()
        .map(declaration -> declaration.formatted(prefix))
        .map(code -> source(sealedName(code), head + code))
        .toArray(JavaFileObject[]::new);
      final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources);
      assertTrue(
        plain.success(),
        () -> cell.name() + " should compile without the processor: " + plain.errorMessages()
      );
      final var processed = bridged(sources);
      if (!processed.success()) {
        failures.add(cell.name() + ": the generated path owed a conversion, saw " + describe(processed));
        continue;
      }
      final var classes = plain.define(MethodHandles.lookup());
      final Class<Object> src = cast(classes.get(PACKAGE + "." + prefix + "Src"));
      final Class<Object> tgt = cast(classes.get(PACKAGE + "." + prefix + "Tgt"));
      final var nested = nestedBridgesFirst(processed, plain, prefix);
      final var refusal = refusalOf(() -> Telescope.mapper(src, tgt));
      if (refusal != null) {
        failures.add(cell.name() + ": the runtime owed a mapper, saw " + refusal);
        continue;
      }
      final var mapper = Telescope.mapper(src, tgt);
      final var bridge = emitted(processed, plain, prefix, nested);
      final Attempt srcSample = () -> src.getMethod("sample").invoke(null);
      final Attempt tgtSample = () -> tgt.getMethod("sample").invoke(null);
      final Attempt partial = () -> tgt.getMethod("partial").invoke(null);
      final var operations = List.of(
        Map.entry("forward", cell.forward()),
        Map.entry("backward", cell.backward()),
        Map.entry("patch", cell.patch())
      );
      for (final var operation : operations) {
        final Attempt generated = switch (operation.getKey()) {
          case "forward" -> () -> bridge.getMethod("forward", src).invoke(null, srcSample.get());
          case "backward" -> () -> bridge.getMethod("backward", tgt).invoke(null, tgtSample.get());
          default -> () -> bridge.getMethod("patch", src, tgt).invoke(null, srcSample.get(), partial.get());
        };
        final Attempt reflective = switch (operation.getKey()) {
          case "forward" -> () -> mapper.forward(srcSample.get());
          case "backward" -> () -> mapper.backward(tgtSample.get());
          default -> () -> mapper.patch(srcSample.get(), partial.get());
        };
        for (final var side : List.of(Map.entry("generated", generated), Map.entry("reflective", reflective))) {
          final var shape = shapeOf(side.getValue());
          if (!shape.equals(operation.getValue())) {
            failures.add(
              cell.name() +
                ", " +
                operation.getKey() +
                ": " +
                side.getKey() +
                " gave " +
                shape +
                ", owed " +
                operation.getValue()
            );
          }
        }
      }
    }
    assertTrue(
      failures.isEmpty(),
      () -> failures.size() + " sealed cell(s) failed:\n  " + String.join("\n  ", failures)
    );
  }

  /**
   * Sealed pairs both paths refuse where each should convert, by the words each path's refusal has
   * to contain: the generated path's first, then the runtime's.
   */
  private static final Map<String, List<String>> SEALED_REFUSALS = Map.of(
    // A defect still to close: neither path pairs the cases of a sealed interface given
    // type arguments, so both refuse the field by name.
    "a sealed field given type arguments",
    List.of("field 'r' has incompatible types", "component 'r' has incompatible source/target shapes")
  );

  @Test
  @DisplayName("a sealed field given type arguments is refused by name on both paths")
  void aGenericSealedFieldIsRefusedOnBothPaths() throws ReflectiveOperationException {
    final var prefix = "Sg";
    final var head = "package " + PACKAGE + ";\n";
    final var bridge = "@io.github.eschizoid.telescope.annotations.Bridge(";
    final var sources = List.of(
      "public sealed interface %1$sRes<T> permits %1$sOk {}\n",
      "public sealed interface %1$sResDto<T> permits %1$sOkDto {}\n",
      bridge + "%1$sOkDto.class)\npublic record %1$sOk<T>(T v) implements %1$sRes<T> {}\n",
      "public record %1$sOkDto<T>(T v) implements %1$sResDto<T> {}\n",
      bridge + "%1$sTgt.class)\npublic record %1$sSrc(%1$sRes<Integer> r) {}\n",
      "public record %1$sTgt(%1$sResDto<Integer> r) {}\n"
    )
      .stream()
      .map(declaration -> declaration.formatted(prefix))
      .map(code -> source(sealedName(code), head + code))
      .toArray(JavaFileObject[]::new);
    final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources);
    assertTrue(plain.success(), plain::errorMessages);
    final var processed = bridged(sources);
    final var classes = plain.define(MethodHandles.lookup());
    final Class<Object> src = cast(classes.get(PACKAGE + "." + prefix + "Src"));
    final Class<Object> tgt = cast(classes.get(PACKAGE + "." + prefix + "Tgt"));
    final var owed = SEALED_REFUSALS.get("a sealed field given type arguments");
    assertTrue(processed.hasError(owed.get(0)), () -> "the generated path owed a refusal, saw " + describe(processed));
    final var refusal = refusalOf(() -> Telescope.mapper(src, tgt));
    assertTrue(refusal != null && refusal.contains(owed.get(1)), () -> "the runtime owed a refusal, saw " + refusal);
  }

  /** The name of the type {@code code} declares, a sealed interface or a record. */
  private static String sealedName(final String code) {
    final var matcher = Pattern.compile("public (?:sealed interface|record|final class|class) (\\w+)").matcher(code);
    if (!matcher.find()) throw new IllegalArgumentException("no public type in " + code);
    return matcher.group(1);
  }

  /** One path's {@code patch}, called with a base and a partial. */
  private interface Patch {
    Object apply(Object base, Object partial) throws ReflectiveOperationException;
  }

  /**
   * Every place in {@code result} that holds an object {@code base} also holds, by the property
   * path that reaches it from the root, or {@code nothing}. A string is a value, so only the cell's
   * own types and containers count.
   */
  private static String shared(final Object base, final Object result) throws ReflectiveOperationException {
    final Set<Object> held = Collections.newSetFromMap(new IdentityHashMap<>());
    collect(base, held);
    final var places = new ArrayList<String>();
    locate(result, "root", held, Collections.newSetFromMap(new IdentityHashMap<>()), places);
    return places.isEmpty() ? "nothing" : places.toString();
  }

  private static void collect(final Object value, final Set<Object> held) throws ReflectiveOperationException {
    if (!isObject(value) || !held.add(value)) return;
    for (final var child : children(value).values()) collect(child, held);
  }

  private static void locate(
    final Object value,
    final String path,
    final Set<Object> held,
    final Set<Object> visited,
    final List<String> places
  ) throws ReflectiveOperationException {
    if (!isObject(value) || !visited.add(value)) return;
    if (held.contains(value)) places.add(path);
    for (final var child : children(value).entrySet()) {
      locate(child.getValue(), path + "." + child.getKey(), held, visited, places);
    }
  }

  /** Whether {@code value} is a container or one of a cell's own types, rather than a value. */
  private static boolean isObject(final Object value) {
    return (
      value instanceof Collection<?> ||
      value instanceof Map<?, ?> ||
      (value != null && value.getClass().getName().startsWith(PACKAGE + "."))
    );
  }

  /** {@code value}'s properties or elements, keyed by name or position. */
  private static Map<String, Object> children(final Object value) throws ReflectiveOperationException {
    final var children = new LinkedHashMap<String, Object>();
    if (value instanceof Map<?, ?> entries) {
      for (final var entry : entries.entrySet()) children.put(String.valueOf(entry.getKey()), entry.getValue());
    } else if (value instanceof Collection<?> elements) {
      var position = 0;
      for (final var element : elements) children.put(String.valueOf(position++), element);
    } else if (value.getClass().isRecord()) {
      for (final var component : value.getClass().getRecordComponents()) {
        children.put(component.getName(), component.getAccessor().invoke(value));
      }
    } else {
      for (final var method : value.getClass().getDeclaredMethods()) {
        if (method.getName().startsWith("get") && method.getParameterCount() == 0) {
          final var name = Character.toLowerCase(method.getName().charAt(3)) + method.getName().substring(4);
          children.put(name, method.invoke(value));
        }
      }
    }
    return children;
  }

  /**
   * Which directions an enum cell's mapper converts: both, for {@code Telescope.mapper} against a
   * strict {@code @Bridge}, or forward alone, for {@code Telescope.mapperForward} against a lenient
   * one.
   */
  private enum Directions {
    BOTH,
    FORWARD,
  }

  /**
   * Two enums on one pair, through both paths: the declarations, each with {@code %1$s} as the
   * cell's prefix, and the shape both paths owe forward, backward and in {@code patch}. {@code Src}
   * carries the {@code @Bridge}; {@code Src.sample()} and {@code Tgt.sample()} build the inputs,
   * and {@code patch} lays {@code Tgt.sample()} over {@code Src.sample()}. A forward cell owes no
   * backward and no patch, since the runtime's forward-only mapper has neither.
   *
   * <p>A refusal is owed as {@link #REFUSED} followed by the words both refusals have to contain,
   * which name the constants that have no counterpart.
   */
  private record EnumCell(
    String name,
    Directions directions,
    List<String> declarations,
    String forward,
    String backward,
    String patch
  ) {}

  private static final String LENIENT_BRIDGE_TO_TGT =
    "@io.github.eschizoid.telescope.annotations.Bridge(value = %1$sTgt.class, lenient = true)\n";

  /** A public enum named {@code name} with {@code constants}. */
  private static String enumOf(final String name, final String constants) {
    return "public enum " + name + " { " + constants + " }\n";
  }

  /** A public record with {@code components} and a {@code sample()} returning {@code sample}. */
  private static String sampled(final String name, final String components, final String sample) {
    return (
      "public record " +
      name +
      "(" +
      components +
      ") {\n  public static " +
      name +
      " sample() { return " +
      sample +
      "; }\n}\n"
    );
  }

  private static final List<EnumCell> ENUM_CELLS = List.of(
    new EnumCell(
      "two enums with the same constants",
      Directions.BOTH,
      List.of(
        BRIDGE_TO_TGT + sampled("%1$sSrc", "%1$sEa status, %1$sEa tier", "new %1$sSrc(%1$sEa.B, %1$sEa.A)"),
        sampled("%1$sTgt", "%1$sEb status, %1$sEb tier", "new %1$sTgt(%1$sEb.C, null)"),
        enumOf("%1$sEa", "A, B, C"),
        enumOf("%1$sEb", "A, B, C")
      ),
      "#0(status=Eb.B, tier=Eb.A)",
      "#0(status=Ea.C, tier=null)",
      "#0(status=Ea.C, tier=Ea.A)"
    ),
    new EnumCell(
      "two enums with the same constants on a bean",
      Directions.BOTH,
      List.of(
        BRIDGE_TO_TGT +
          bean(
            "%1$sSrc",
            "  public static %1$sSrc sample() {\n" +
              "    final var s = new %1$sSrc();\n" +
              "    s.setStatus(%1$sEa.B);\n" +
              "    return s;\n" +
              "  }\n",
            "%1$sEa status"
          ),
        bean(
          "%1$sTgt",
          "  public static %1$sTgt sample() {\n" +
            "    final var t = new %1$sTgt();\n" +
            "    t.setStatus(%1$sEb.A);\n" +
            "    return t;\n" +
            "  }\n",
          "%1$sEb status"
        ),
        enumOf("%1$sEa", "A, B"),
        enumOf("%1$sEb", "A, B")
      ),
      "#0(status=Eb.B)",
      "#0(status=Ea.A)",
      "#0(status=Ea.A)"
    ),
    new EnumCell(
      "enums in a list, a null among them",
      Directions.BOTH,
      List.of(
        BRIDGE_TO_TGT +
          sampled(
            "%1$sSrc",
            "java.util.List<%1$sEa> items",
            "new %1$sSrc(java.util.Arrays.asList(%1$sEa.C, null, %1$sEa.A))"
          ),
        sampled("%1$sTgt", "java.util.List<%1$sEb> items", "new %1$sTgt(java.util.List.of(%1$sEb.B))"),
        enumOf("%1$sEa", "A, B, C"),
        enumOf("%1$sEb", "A, B, C")
      ),
      "#0(items=[Eb.C, null, Eb.A])",
      "#0(items=[Ea.B])",
      "#0(items=[Ea.B])"
    ),
    new EnumCell(
      "enums in a set",
      Directions.BOTH,
      List.of(
        BRIDGE_TO_TGT +
          sampled(
            "%1$sSrc",
            "java.util.Set<%1$sEa> items",
            "new %1$sSrc(new java.util.LinkedHashSet<>(java.util.List.of(%1$sEa.C, %1$sEa.A)))"
          ),
        sampled(
          "%1$sTgt",
          "java.util.Set<%1$sEb> items",
          "new %1$sTgt(new java.util.LinkedHashSet<>(java.util.List.of(%1$sEb.B, %1$sEb.A)))"
        ),
        enumOf("%1$sEa", "A, B, C"),
        enumOf("%1$sEb", "A, B, C")
      ),
      "#0(items=[Eb.C, Eb.A])",
      "#0(items=[Ea.B, Ea.A])",
      "#0(items=[Ea.B, Ea.A])"
    ),
    new EnumCell(
      "enums as map values",
      Directions.BOTH,
      List.of(
        BRIDGE_TO_TGT +
          sampled(
            "%1$sSrc",
            "java.util.Map<String, %1$sEa> items",
            "new %1$sSrc(new java.util.LinkedHashMap<>(java.util.Map.of(\"x\", %1$sEa.B)))"
          ),
        sampled(
          "%1$sTgt",
          "java.util.Map<String, %1$sEb> items",
          "new %1$sTgt(new java.util.LinkedHashMap<>(java.util.Map.of(\"y\", %1$sEb.C)))"
        ),
        enumOf("%1$sEa", "A, B, C"),
        enumOf("%1$sEb", "A, B, C")
      ),
      "#0(items={x=Eb.B})",
      "#0(items={y=Ea.C})",
      "#0(items={y=Ea.C})"
    ),
    new EnumCell(
      "an enum in an Optional, and an Optional against a nullable enum",
      Directions.BOTH,
      List.of(
        BRIDGE_TO_TGT +
          sampled(
            "%1$sSrc",
            "java.util.Optional<%1$sEa> pick, java.util.Optional<%1$sEa> held",
            "new %1$sSrc(java.util.Optional.of(%1$sEa.B), java.util.Optional.of(%1$sEa.A))"
          ),
        sampled(
          "%1$sTgt",
          "java.util.Optional<%1$sEb> pick, %1$sEb held",
          "new %1$sTgt(java.util.Optional.empty(), %1$sEb.B)"
        ),
        enumOf("%1$sEa", "A, B"),
        enumOf("%1$sEb", "A, B")
      ),
      "#0(pick=of Eb.B, held=Eb.A)",
      "#0(pick=empty, held=of Ea.B)",
      "#0(pick=empty, held=of Ea.B)"
    ),
    new EnumCell(
      "enums under a type-use annotation, as a field and as list elements",
      Directions.BOTH,
      List.of(
        BRIDGE_TO_TGT +
          sampled(
            "%1$sSrc",
            "@%1$sTu %1$sEa status, java.util.List<@%1$sTu %1$sEa> items",
            "new %1$sSrc(%1$sEa.B, java.util.List.of(%1$sEa.A))"
          ),
        sampled(
          "%1$sTgt",
          "@%1$sTu %1$sEb status, java.util.List<@%1$sTu %1$sEb> items",
          "new %1$sTgt(%1$sEb.A, java.util.List.of(%1$sEb.B))"
        ),
        "@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)\npublic @interface %1$sTu {}\n",
        enumOf("%1$sEa", "A, B"),
        enumOf("%1$sEb", "A, B")
      ),
      "#0(status=Eb.B, items=[Eb.A])",
      "#0(status=Ea.A, items=[Ea.B])",
      "#0(status=Ea.A, items=[Ea.B])"
    ),
    new EnumCell(
      "enums in an EnumSet, which no rebuild can build",
      Directions.BOTH,
      List.of(
        // The enums come first: a cell's classes are defined in the order they are
        // declared here,
        // and a record holding an EnumSet of an enum is not defined ahead of that enum.
        enumOf("%1$sEa", "A, B"),
        enumOf("%1$sEb", "A, B"),
        BRIDGE_TO_TGT +
          sampled("%1$sSrc", "java.util.EnumSet<%1$sEa> items", "new %1$sSrc(java.util.EnumSet.of(%1$sEa.A))"),
        sampled("%1$sTgt", "java.util.EnumSet<%1$sEb> items", "new %1$sTgt(java.util.EnumSet.of(%1$sEb.A))")
      ),
      REFUSED + "java.util.EnumSet has no instance of its own",
      null,
      null
    ),
    new EnumCell(
      "a target enum with a constant the source lacks, below a sub-pair a lenient bridge shares, both ways",
      Directions.BOTH,
      List.of(
        "@io.github.eschizoid.telescope.annotations.Bridge(value = %1$sOtherDto.class, lenient = true)\n" +
          "public record %1$sOther(%1$sInner inner) {}\n",
        "public record %1$sOtherDto(%1$sInnerDto inner) {}\n",
        BRIDGE_TO_TGT + sampled("%1$sSrc", "%1$sInner inner", "new %1$sSrc(new %1$sInner(%1$sEa.A))"),
        sampled("%1$sTgt", "%1$sInnerDto inner", "new %1$sTgt(new %1$sInnerDto(%1$sEb.C))"),
        "public record %1$sInner(%1$sEa status) {}\n",
        "public record %1$sInnerDto(%1$sEb status) {}\n",
        enumOf("%1$sEa", "A, B"),
        enumOf("%1$sEb", "A, B, C")
      ),
      REFUSED + "%1$sEa has no constant named C, which the backward direction needs",
      null,
      null
    ),
    new EnumCell(
      "a target enum with a constant the source lacks, in a list below a sub-pair a lenient bridge shares, both ways",
      Directions.BOTH,
      List.of(
        "@io.github.eschizoid.telescope.annotations.Bridge(value = %1$sOtherDto.class, lenient = true)\n" +
          "public record %1$sOther(%1$sInner inner) {}\n",
        "public record %1$sOtherDto(%1$sInnerDto inner) {}\n",
        BRIDGE_TO_TGT + sampled("%1$sSrc", "java.util.List<%1$sInner> items", "new %1$sSrc(java.util.List.of())"),
        sampled("%1$sTgt", "java.util.List<%1$sInnerDto> items", "new %1$sTgt(java.util.List.of())"),
        "public record %1$sInner(%1$sEa status) {}\n",
        "public record %1$sInnerDto(%1$sEb status) {}\n",
        enumOf("%1$sEa", "A, B"),
        enumOf("%1$sEb", "A, B, C")
      ),
      REFUSED + "%1$sEa has no constant named C, which the backward direction needs",
      null,
      null
    ),
    new EnumCell(
      "an enum pair below a sub-pair whose own bridge converts it with a transform, under a strict parent",
      Directions.BOTH,
      List.of(
        BRIDGE_TO_TGT +
          sampled(
            "%1$sSrc",
            "%1$sInner inner, java.util.List<%1$sInner> items",
            "new %1$sSrc(new %1$sInner(%1$sEa.A), java.util.List.of(new %1$sInner(%1$sEa.B)))"
          ),
        sampled(
          "%1$sTgt",
          "%1$sInnerDto inner, java.util.List<%1$sInnerDto> items",
          "new %1$sTgt(new %1$sInnerDto(%1$sEb.C), java.util.List.of(new %1$sInnerDto(%1$sEb.C)))"
        ),
        "@io.github.eschizoid.telescope.annotations.Bridge(value = %1$sInnerDto.class, transforms =" +
          " @io.github.eschizoid.telescope.annotations.Transform(field = \"status\", using = %1$sFn.class))\n" +
          "public record %1$sInner(%1$sEa status) {}\n",
        "public record %1$sInnerDto(%1$sEb status) {}\n",
        "public class %1$sFn implements io.github.eschizoid.telescope.conversion.BridgeFn<%1$sEa, %1$sEb> {\n" +
          "  public %1$sEb forward(final %1$sEa a) { return a == null ? null : %1$sEb.valueOf(a.name()); }\n" +
          "  public %1$sEa backward(final %1$sEb b) {\n" +
          "    return b == null ? null : b == %1$sEb.C ? %1$sEa.A : %1$sEa.valueOf(b.name());\n" +
          "  }\n" +
          "}\n",
        enumOf("%1$sEa", "A, B"),
        enumOf("%1$sEb", "A, B, C")
      ),
      "#0(inner=#1(status=Eb.A), items=[#2(status=Eb.B)])",
      "#0(inner=#1(status=Ea.A), items=[#2(status=Ea.A)])",
      "#0(inner=#1(status=Ea.A), items=[#2(status=Ea.A)])"
    ),
    new EnumCell(
      "an enum pair below a sub-pair whose own lenient bridge converts it forward only, under a strict parent",
      Directions.BOTH,
      List.of(
        BRIDGE_TO_TGT + sampled("%1$sSrc", "%1$sInner i", "new %1$sSrc(new %1$sInner(%1$sEa.A))"),
        sampled("%1$sTgt", "%1$sInnerDto i", "new %1$sTgt(new %1$sInnerDto(%1$sEb.C))"),
        "@io.github.eschizoid.telescope.annotations.Bridge(value = %1$sInnerDto.class, lenient = true)\n" +
          "public record %1$sInner(%1$sEa status) {}\n",
        "public record %1$sInnerDto(%1$sEb status) {}\n",
        enumOf("%1$sEa", "A, B"),
        enumOf("%1$sEb", "A, B, C")
      ),
      "#0(i=#1(status=Eb.A))",
      "#0(i=#1(status=null))",
      "#0(i=#1(status=null))"
    ),
    new EnumCell(
      "an enum pair two levels below a sub-pair a lenient bridge shares, where the lower pair's own bridge converts it",
      Directions.BOTH,
      List.of(
        BRIDGE_TO_TGT + sampled("%1$sSrc", "%1$sMid x", "new %1$sSrc(new %1$sMid(new %1$sInner(%1$sEa.A)))"),
        sampled("%1$sTgt", "%1$sMidDto x", "new %1$sTgt(new %1$sMidDto(new %1$sInnerDto(%1$sEb.C)))"),
        "@io.github.eschizoid.telescope.annotations.Bridge(value = %1$sOtherDto.class, lenient = true)\n" +
          "public record %1$sOther(%1$sMid x) {}\n",
        "public record %1$sOtherDto(%1$sMidDto x) {}\n",
        "public record %1$sMid(%1$sInner i) {}\n",
        "public record %1$sMidDto(%1$sInnerDto i) {}\n",
        "@io.github.eschizoid.telescope.annotations.Bridge(value = %1$sInnerDto.class, transforms =" +
          " @io.github.eschizoid.telescope.annotations.Transform(field = \"status\", using = %1$sFn.class))\n" +
          "public record %1$sInner(%1$sEa status) {}\n",
        "public record %1$sInnerDto(%1$sEb status) {}\n",
        "public class %1$sFn implements io.github.eschizoid.telescope.conversion.BridgeFn<%1$sEa, %1$sEb> {\n" +
          "  public %1$sEb forward(final %1$sEa a) { return a == null ? null : %1$sEb.valueOf(a.name()); }\n" +
          "  public %1$sEa backward(final %1$sEb b) {\n" +
          "    return b == null ? null : b == %1$sEb.C ? %1$sEa.A : %1$sEa.valueOf(b.name());\n" +
          "  }\n" +
          "}\n",
        enumOf("%1$sEa", "A, B"),
        enumOf("%1$sEb", "A, B, C")
      ),
      "#0(x=#1(i=#2(status=Eb.A)))",
      "#0(x=#1(i=#2(status=Ea.A)))",
      "#0(x=#1(i=#2(status=Ea.A)))"
    ),
    new EnumCell(
      "an enum pair below a sub-pair whose source declares a bridge to two targets, one with a transform",
      Directions.BOTH,
      List.of(
        BRIDGE_TO_TGT + sampled("%1$sSrc", "%1$sInner i", "new %1$sSrc(new %1$sInner(%1$sEa.A))"),
        sampled("%1$sTgt", "%1$sInnerDto i", "new %1$sTgt(new %1$sInnerDto(%1$sEb.C))"),
        "@io.github.eschizoid.telescope.annotations.Bridge(value = %1$sInnerDto.class, transforms =" +
          " @io.github.eschizoid.telescope.annotations.Transform(field = \"status\", using = %1$sFn.class))\n" +
          "@io.github.eschizoid.telescope.annotations.Bridge(%1$sOtherDto.class)\n" +
          "public record %1$sInner(%1$sEa status) {}\n",
        "public record %1$sInnerDto(%1$sEb status) {}\n",
        "public record %1$sOtherDto(%1$sEa status) {}\n",
        "public class %1$sFn implements io.github.eschizoid.telescope.conversion.BridgeFn<%1$sEa, %1$sEb> {\n" +
          "  public %1$sEb forward(final %1$sEa a) { return a == null ? null : %1$sEb.valueOf(a.name()); }\n" +
          "  public %1$sEa backward(final %1$sEb b) {\n" +
          "    return b == null ? null : b == %1$sEb.C ? %1$sEa.A : %1$sEa.valueOf(b.name());\n" +
          "  }\n" +
          "}\n",
        enumOf("%1$sEa", "A, B"),
        enumOf("%1$sEb", "A, B, C")
      ),
      "#0(i=#1(status=Eb.A))",
      "#0(i=#1(status=Ea.A))",
      "#0(i=#1(status=Ea.A))"
    ),
    new EnumCell(
      "an enum pair below a sub-pair whose own bridge transforms it, where the sub-pair reaches back to the parent",
      Directions.BOTH,
      List.of(
        BRIDGE_TO_TGT + sampled("%1$sSrc", "%1$sMid m", "new %1$sSrc(new %1$sMid(%1$sEa.A, java.util.List.of()))"),
        sampled("%1$sTgt", "%1$sMidDto m", "new %1$sTgt(new %1$sMidDto(%1$sEb.C, java.util.List.of()))"),
        "@io.github.eschizoid.telescope.annotations.Bridge(value = %1$sMidDto.class, transforms =" +
          " @io.github.eschizoid.telescope.annotations.Transform(field = \"status\", using = %1$sFn.class))\n" +
          "public record %1$sMid(%1$sEa status, java.util.List<%1$sSrc> back) {}\n",
        "public record %1$sMidDto(%1$sEb status, java.util.List<%1$sTgt> back) {}\n",
        "public class %1$sFn implements io.github.eschizoid.telescope.conversion.BridgeFn<%1$sEa, %1$sEb> {\n" +
          "  public %1$sEb forward(final %1$sEa a) { return a == null ? null : %1$sEb.valueOf(a.name()); }\n" +
          "  public %1$sEa backward(final %1$sEb b) {\n" +
          "    return b == null ? null : b == %1$sEb.C ? %1$sEa.A : %1$sEa.valueOf(b.name());\n" +
          "  }\n" +
          "}\n",
        enumOf("%1$sEa", "A, B"),
        enumOf("%1$sEb", "A, B, C")
      ),
      "#0(m=#1(status=Eb.A, back=[]))",
      "#0(m=#1(status=Ea.A, back=[]))",
      "#0(m=#1(status=Ea.A, back=[]))"
    ),
    new EnumCell(
      "a target enum with a constant the source lacks, both ways",
      Directions.BOTH,
      List.of(
        BRIDGE_TO_TGT + sampled("%1$sSrc", "%1$sEa status", "new %1$sSrc(%1$sEa.A)"),
        sampled("%1$sTgt", "%1$sEb status", "new %1$sTgt(%1$sEb.C)"),
        enumOf("%1$sEa", "A, B"),
        enumOf("%1$sEb", "A, B, C")
      ),
      REFUSED + "%1$sEa has no constant named C, which the backward direction needs",
      null,
      null
    ),
    new EnumCell(
      "a target enum with a constant the source lacks, inside a list, both ways",
      Directions.BOTH,
      List.of(
        BRIDGE_TO_TGT + sampled("%1$sSrc", "java.util.List<%1$sEa> items", "new %1$sSrc(java.util.List.of())"),
        sampled("%1$sTgt", "java.util.List<%1$sEb> items", "new %1$sTgt(java.util.List.of())"),
        enumOf("%1$sEa", "A, B"),
        enumOf("%1$sEb", "A, B, C, D")
      ),
      REFUSED + "%1$sEa has no constant named C, D, which the backward direction needs",
      null,
      null
    ),
    new EnumCell(
      "a target enum with a constant the source lacks, forward only",
      Directions.FORWARD,
      List.of(
        LENIENT_BRIDGE_TO_TGT +
          sampled(
            "%1$sSrc",
            "%1$sEa status, java.util.List<%1$sEa> items, %1$sInner inner",
            "new %1$sSrc(%1$sEa.B, java.util.List.of(%1$sEa.A), new %1$sInner(%1$sEa.A))"
          ),
        sampled(
          "%1$sTgt",
          "%1$sEb status, java.util.List<%1$sEb> items, %1$sInnerDto inner",
          "new %1$sTgt(%1$sEb.C, java.util.List.of(), null)"
        ),
        "public record %1$sInner(%1$sEa status) {}\n",
        "public record %1$sInnerDto(%1$sEb status) {}\n",
        enumOf("%1$sEa", "A, B"),
        enumOf("%1$sEb", "A, B, C")
      ),
      "#0(status=Eb.B, items=[Eb.A], inner=#1(status=Eb.A))",
      null,
      null
    ),
    new EnumCell(
      "a source enum with a constant the target lacks, both ways",
      Directions.BOTH,
      List.of(
        BRIDGE_TO_TGT + sampled("%1$sSrc", "%1$sEa status", "new %1$sSrc(%1$sEa.A)"),
        sampled("%1$sTgt", "%1$sEb status", "new %1$sTgt(%1$sEb.A)"),
        enumOf("%1$sEa", "A, B, C"),
        enumOf("%1$sEb", "A, C")
      ),
      REFUSED + "%1$sEb has no constant named B",
      null,
      null
    ),
    new EnumCell(
      "a source enum with a constant the target lacks, in an Optional, forward only",
      Directions.FORWARD,
      List.of(
        LENIENT_BRIDGE_TO_TGT +
          sampled("%1$sSrc", "java.util.Optional<%1$sEa> pick", "new %1$sSrc(java.util.Optional.empty())"),
        sampled("%1$sTgt", "java.util.Optional<%1$sEb> pick", "new %1$sTgt(java.util.Optional.empty())"),
        enumOf("%1$sEa", "A, B, C"),
        enumOf("%1$sEb", "A")
      ),
      REFUSED + "%1$sEb has no constant named B, C",
      null,
      null
    )
  );

  /**
   * Enum cells where the runtime owes a refusal, by the words it has to contain, while the
   * generated path owes the cell's shapes.
   *
   * <p>An entry no cell exercises fails the test, so the register cannot outlive its cell.
   */
  private static final Map<String, String> ENUM_RUNTIME_REFUSALS = Map.of(
    // A defect still to close: the runtime walks a nested pair that reaches back to a pair
    // being converted around it, rather than using its own bridge, because its guard
    // against an object reaching itself does not pass through a generated bridge. Walked,
    // the enum pair has no transform and refuses the constant the bridge would have
    // converted.
    "an enum pair below a sub-pair whose own bridge transforms it, where the sub-pair reaches back to the parent",
    "%1$sEa has no constant named C, which the backward direction needs"
  );

  @Test
  @DisplayName("an enum maps to an enum by constant name on both paths, and both refuse the same mismatches")
  void anEnumMapsByConstantNameOnBothPaths() throws ReflectiveOperationException {
    final var failures = new ArrayList<String>();
    final var exercised = new LinkedHashSet<String>();
    var index = 0;
    for (final var cell : ENUM_CELLS) {
      final var prefix = "En" + index++;
      final var head = "package " + PACKAGE + ";\n";
      final var sources = cell
        .declarations()
        .stream()
        .map(declaration -> declaration.formatted(prefix))
        .map(code -> source(declaredName(code), head + code))
        .toArray(JavaFileObject[]::new);
      final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources);
      assertTrue(
        plain.success(),
        () -> cell.name() + " should compile without the processor: " + plain.errorMessages()
      );
      final var processed = bridged(sources);
      final var classes = plain.define(MethodHandles.lookup());
      final Class<Object> src = cast(classes.get(PACKAGE + "." + prefix + "Src"));
      final Class<Object> tgt = cast(classes.get(PACKAGE + "." + prefix + "Tgt"));
      final var forwardOnly = cell.directions() == Directions.FORWARD;
      // Every bridge but the cell's own is defined before the mapper is built, so a nested pair's
      // own bridge is there for the runtime to find. The cell's own bridge comes after: a
      // forward-only mapper given no rows routes through a bridge it finds beside the source, and
      // would then be the generated path a second time.
      final var subBridges = nestedBridgesFirst(processed, plain, prefix);
      final Attempt build = () -> forwardOnly ? Telescope.mapperForward(src, tgt) : Telescope.mapper(src, tgt);
      final var refusal = refusalOf(build);
      final var built = refusal == null;
      final Mapper<Object, Object> mapper = built && !forwardOnly ? Telescope.mapper(src, tgt) : null;
      final ForwardMapper<Object, Object> forwardMapper =
        built && forwardOnly ? Telescope.mapperForward(src, tgt) : null;
      if (cell.forward().startsWith(REFUSED)) {
        final var owed = cell.forward().formatted(prefix).substring(REFUSED.length());
        if (processed.success() || !processed.hasError(owed)) {
          failures.add(
            cell.name() + ": the generated path owed a refusal naming " + owed + ", saw " + describe(processed)
          );
        }
        if (built) {
          failures.add(cell.name() + ": the runtime built a mapper, where it owed a refusal naming " + owed);
        } else if (!refusal.contains(owed)) {
          failures.add(cell.name() + ": the runtime owed a refusal naming " + owed + ", saw " + refusal);
        }
        continue;
      }
      final var runtimeRefusal = ENUM_RUNTIME_REFUSALS.get(cell.name());
      if (runtimeRefusal != null) {
        exercised.add(cell.name());
        final var owed = runtimeRefusal.formatted(prefix);
        if (built || !refusal.contains(owed)) {
          failures.add(
            cell.name() + ": the runtime owed a refusal naming " + owed + ", saw " + (built ? "a mapper" : refusal)
          );
        }
      } else if (!built) {
        failures.add(cell.name() + ": the runtime owed a conversion, saw " + refusal);
        continue;
      }
      if (!processed.success()) {
        failures.add(cell.name() + ": the generated path owed a conversion, saw " + describe(processed));
        continue;
      }
      final var bridge = emitted(processed, plain, prefix, subBridges);
      final Attempt srcSample = () -> src.getMethod("sample").invoke(null);
      final Attempt tgtSample = () -> tgt.getMethod("sample").invoke(null);
      final var owed = new ArrayList<Map.Entry<String, String>>();
      owed.add(Map.entry("forward", cell.forward()));
      if (cell.backward() != null) owed.add(Map.entry("backward", cell.backward()));
      if (cell.patch() != null) owed.add(Map.entry("patch", cell.patch()));
      for (final var operation : owed) {
        final Attempt generated = switch (operation.getKey()) {
          case "forward" -> () -> bridge.getMethod("forward", src).invoke(null, srcSample.get());
          case "backward" -> () -> bridge.getMethod("backward", tgt).invoke(null, tgtSample.get());
          default -> () -> bridge.getMethod("patch", src, tgt).invoke(null, srcSample.get(), tgtSample.get());
        };
        final Attempt reflective = switch (operation.getKey()) {
          case "forward" -> forwardOnly
            ? () -> forwardMapper.forward(srcSample.get())
            : () -> mapper.forward(srcSample.get());
          case "backward" -> () -> mapper.backward(tgtSample.get());
          default -> () -> mapper.patch(srcSample.get(), tgtSample.get());
        };
        // A cell whose runtime owes a refusal has no mapper to hold to the shapes.
        final var sides =
          runtimeRefusal == null
            ? List.of(Map.entry("generated", generated), Map.entry("reflective", reflective))
            : List.of(Map.entry("generated", generated));
        for (final var side : sides) {
          final var shape = shapeOf(side.getValue());
          if (!shape.equals(operation.getValue())) {
            failures.add(
              cell.name() +
                ", " +
                operation.getKey() +
                ": " +
                side.getKey() +
                " gave " +
                shape +
                ", owed " +
                operation.getValue()
            );
          }
        }
      }
    }
    assertTrue(failures.isEmpty(), () -> failures.size() + " enum cell(s) failed:\n  " + String.join("\n  ", failures));
    assertTrue(
      exercised.equals(ENUM_RUNTIME_REFUSALS.keySet()),
      () -> "registered runtime refusals no cell exercised: " + ENUM_RUNTIME_REFUSALS.keySet()
    );
  }

  /**
   * {@code @Bridge} to {@code %1$sTgt} with a transform on {@code value} through {@code %1$sFn}.
   */
  private static String transformedValue(final boolean forwardOnly) {
    return (
      "@io.github.eschizoid.telescope.annotations.Bridge(value = %1$sTgt.class, transforms =" +
      " @io.github.eschizoid.telescope.annotations.Transform(field = \"value\", using = %1$sFn.class" +
      (forwardOnly ? ", forwardOnly = true" : "") +
      "))\n"
    );
  }

  /**
   * {@code %1$sFn}, a {@code BridgeFn<from, to>} whose forward returns the expression {@code
   * forward} of its argument {@code a}, and whose backward returns {@code backward} of {@code b}.
   */
  private static String transformFn(final String from, final String to, final String forward, final String backward) {
    return (
      "public class %1$sFn implements io.github.eschizoid.telescope.conversion.BridgeFn<" +
      from +
      ", " +
      to +
      "> {\n" +
      "  public " +
      to +
      " forward(final " +
      from +
      " a) { return " +
      forward +
      "; }\n" +
      "  public " +
      from +
      " backward(final " +
      to +
      " b) { return " +
      backward +
      "; }\n}\n"
    );
  }

  /**
   * {@code %1$sRuntime}, whose {@code mapper()} builds the runtime mapper for the pair with the
   * typed-transform row that agrees with the {@code @Transform}, reading {@code value} through
   * {@code accessor} on both sides. A method reference to a member of a generic class names it raw,
   * so the row is typed by what the raw members return, {@code srcType} and {@code tgtType}.
   */
  private static String runtimeRow(
    final boolean forwardOnly,
    final String accessor,
    final String srcType,
    final String tgtType
  ) {
    return witnessedRow(forwardOnly, accessor, "%1$sSrc, %1$sTgt, " + srcType + ", " + tgtType);
  }

  /**
   * As {@link #runtimeRow}, with the row's four type arguments, {@code witness}, given whole: the
   * source and target classes, possibly with arguments of their own, then the types the row reads
   * {@code value} as on each side.
   */
  private static String witnessedRow(final boolean forwardOnly, final String accessor, final String witness) {
    final var src = "%1$sSrc::" + accessor;
    final var tgt = "%1$sTgt::" + accessor;
    final var row = forwardOnly
      ? "<" + witness + ">toOneWay(" + src + ", " + tgt + ", fn::forward)"
      : "<" + witness + ">to(" + src + ", " + tgt + ", fn::forward, fn::backward)";
    return (
      "public class %1$sRuntime {\n" +
      "  @SuppressWarnings({\"rawtypes\", \"unchecked\"})\n" +
      "  public static Object mapper() {\n" +
      "    final var fn = new %1$sFn();\n" +
      "    return io.github.eschizoid.telescope.Telescope." +
      (forwardOnly ? "mapperForward" : "mapper") +
      "(%1$sSrc.class, %1$sTgt.class, io.github.eschizoid.telescope.mapping.Mapping." +
      row +
      ");\n  }\n}\n"
    );
  }

  /**
   * As {@link #sampled}, for a record declared {@code header} with type parameters, whose static
   * {@code sample()} cannot name them and returns the record with a wildcard.
   */
  private static String sampledGeneric(final String header, final String components, final String sample) {
    final var name = header.replaceAll("<.*", "");
    return (
      "public record " +
      header +
      "(" +
      components +
      ") {\n  public static " +
      name +
      "<?> sample() { return " +
      sample +
      "; }\n}\n"
    );
  }

  /**
   * A bean declared {@code header} with one property, {@code value}, of {@code type}, and a static
   * {@code sample()} holding {@code sample}. A generic bean's sample has the argument {@code
   * Object}.
   */
  private static String valueBean(final String header, final String type, final String sample) {
    final var name = header.replaceAll("<.*", "");
    final var held = header.contains("<") ? name + "<Object>" : name;
    return (
      "public class " +
      header +
      " {\n" +
      "  private " +
      type +
      " value;\n" +
      "  public " +
      type +
      " getValue() { return value; }\n" +
      "  public void setValue(final " +
      type +
      " value) { this.value = value; }\n" +
      "  public static " +
      held +
      " sample() { final " +
      held +
      " s = new " +
      held +
      "(); s.setValue(" +
      sample +
      "); return s; }\n}\n"
    );
  }

  /**
   * {@code header}, then a bean {@code name} declaring no type parameters, whose one property,
   * {@code property}, is read and written through generic methods, {@code <T> T getP()} and {@code
   * <T> void setP(T)}, and whose static {@code sample()} holds {@code sample}.
   */
  private static String genericAccessorBean(
    final String header,
    final String name,
    final String property,
    final String sample
  ) {
    final var cap = Character.toUpperCase(property.charAt(0)) + property.substring(1);
    return (
      header +
      "public class " +
      name +
      " {\n" +
      "  private Object " +
      property +
      ";\n" +
      "  @SuppressWarnings(\"unchecked\") public <T> T get" +
      cap +
      "() { return (T) " +
      property +
      "; }\n" +
      "  public <T> void set" +
      cap +
      "(final T " +
      property +
      ") { this." +
      property +
      " = " +
      property +
      "; }\n" +
      "  public static " +
      name +
      " sample() { final var s = new " +
      name +
      "(); s.set" +
      cap +
      "(" +
      sample +
      "); return s; }\n}\n"
    );
  }

  /**
   * A {@code @Transform} on a property typed by its class's own type variable: the declarations,
   * each with {@code %1$s} as the row's prefix and the last of them the runtime row, and what both
   * paths owe for {@code Src.sample()} forward and {@code Tgt.sample()} backward and laid over
   * {@code Src.sample()}. A forward-only row owes no backward or patch.
   */
  private record VariableTransform(
    String name,
    boolean forwardOnly,
    List<String> declarations,
    String forward,
    String backward,
    String patch
  ) {}

  private static final List<VariableTransform> VARIABLE_TRANSFORMS = List.of(
    new VariableTransform(
      "a source property typed by the source's variable, forward only",
      true,
      List.of(
        transformedValue(true) + sampledGeneric("%1$sSrc<X>", "X value", "new %1$sSrc<>(7)"),
        sampled("%1$sTgt", "String value", "new %1$sTgt(\"q\")"),
        transformFn("Object", "String", "\"<\" + a + \">\"", "b"),
        runtimeRow(true, "value", "Object", "String")
      ),
      "#0(value=\"<7>\")",
      null,
      null
    ),
    new VariableTransform(
      "a source property typed by the source's variable, both ways",
      false,
      List.of(
        transformedValue(false) + sampledGeneric("%1$sSrc<X>", "X value", "new %1$sSrc<>(7)"),
        sampled("%1$sTgt", "String value", "new %1$sTgt(\"q\")"),
        transformFn("Object", "String", "\"<\" + a + \">\"", "b + \"!\""),
        runtimeRow(false, "value", "Object", "String")
      ),
      "#0(value=\"<7>\")",
      "#0(value=\"q!\")",
      "#0(value=\"q!\")"
    ),
    new VariableTransform(
      "a target property typed by the target's variable, both ways",
      false,
      List.of(
        transformedValue(false) + sampled("%1$sSrc", "String value", "new %1$sSrc(\"abc\")"),
        sampledGeneric("%1$sTgt<Y>", "Y value", "new %1$sTgt<>(5)"),
        transformFn("String", "Object", "a.length()", "\"#\" + b"),
        runtimeRow(false, "value", "String", "Object")
      ),
      "#0(value=3)",
      "#0(value=\"#5\")",
      "#0(value=\"#5\")"
    ),
    new VariableTransform(
      "a source property typed by a variable bounded by Number, both ways",
      false,
      List.of(
        transformedValue(false) + sampledGeneric("%1$sSrc<T extends Number>", "T value", "new %1$sSrc<>(7)"),
        sampled("%1$sTgt", "String value", "new %1$sTgt(\"41\")"),
        transformFn("Number", "String", "String.valueOf(a.intValue() + 1)", "Integer.valueOf(b)"),
        runtimeRow(false, "value", "Number", "String")
      ),
      "#0(value=\"8\")",
      "#0(value=41)",
      "#0(value=41)"
    ),
    new VariableTransform(
      "a source list of the source's variable, both ways",
      false,
      List.of(
        transformedValue(false) +
          sampledGeneric("%1$sSrc<X>", "java.util.List<X> value", "new %1$sSrc<>(java.util.List.of(1, 2))"),
        sampled("%1$sTgt", "String value", "new %1$sTgt(\"q\")"),
        transformFn("java.util.List<Object>", "String", "a.size() + \":\" + a", "java.util.List.of(b, b)"),
        runtimeRow(false, "value", "java.util.List", "String")
      ),
      "#0(value=\"2:[1, 2]\")",
      "#0(value=[\"q\", \"q\"])",
      "#0(value=[\"q\", \"q\"])"
    ),
    // The list is held raw, which an overload for List<Object> takes by unchecked
    // conversion and is then the most specific: the call binds that overload, and
    // the setter is handed what it returns.
    new VariableTransform(
      "a source list of the source's variable, through the transform's overload for a list",
      true,
      List.of(
        transformedValue(true) + valueBean("%1$sSrc<X>", "java.util.List<X>", "java.util.List.of(1, 2)"),
        valueBean("%1$sTgt", "Object", "\"q\""),
        "public class %1$sFn implements io.github.eschizoid.telescope.conversion.BridgeFn<Object, String> {\n" +
          "  public String forward(final Object a) { return \"any\"; }\n" +
          "  public Integer forward(final java.util.List<Object> a) { return a.size(); }\n" +
          "  public Object backward(final String b) { return b; }\n}\n",
        runtimeRow(true, "getValue", "java.util.List", "Object")
      ),
      "#0(value=2)",
      null,
      null
    ),
    new VariableTransform(
      "a bean property typed by the bean's variable, both ways",
      false,
      List.of(
        transformedValue(false) + valueBean("%1$sSrc<X>", "X", "7"),
        valueBean("%1$sTgt", "String", "\"q\""),
        transformFn("Object", "String", "\"<\" + a + \">\"", "b + \"!\""),
        runtimeRow(false, "getValue", "Object", "String")
      ),
      "#0(value=\"<7>\")",
      "#0(value=\"q!\")",
      "#0(value=\"q!\")"
    ),
    new VariableTransform(
      "a bean property read and written through generic methods, both ways",
      false,
      List.of(
        genericAccessorBean(transformedValue(false), "%1$sSrc", "value", "7"),
        valueBean("%1$sTgt", "String", "\"q\""),
        transformFn("Object", "String", "\"<\" + a + \">\"", "b + \"!\""),
        runtimeRow(false, "getValue", "Object", "String")
      ),
      "#0(value=\"<7>\")",
      "#0(value=\"q!\")",
      "#0(value=\"q!\")"
    )
  );

  @Test
  @DisplayName("a transform on a property typed by its class's own type variable converts the same way on both paths")
  void aTransformOnATypeVariablePropertyConvertsTheSameWayOnBothPaths() throws ReflectiveOperationException {
    final var failures = new ArrayList<String>();
    var index = 0;
    for (final var row : VARIABLE_TRANSFORMS) {
      final var prefix = "Vt" + index++;
      final var head = "package " + PACKAGE + ";\n";
      final var all = row
        .declarations()
        .stream()
        .map(declaration -> declaration.formatted(prefix))
        .map(code -> source(declaredName(code), head + code))
        .toList();
      // The runtime row is compiled with the pair but not handed to the processor, so the classes
      // the processor's compile adds are its bridges alone.
      final var plain = ProcessorHarness.compileFully(List.of(), List.of(), all.toArray(JavaFileObject[]::new));
      assertTrue(
        plain.success(),
        () -> row.name() + ": the pair and its runtime row should compile: " + plain.errorMessages()
      );
      final var processed = bridged(all.subList(0, all.size() - 1).toArray(JavaFileObject[]::new));
      if (!processed.success()) {
        failures.add(row.name() + ": the generated bridge should compile: " + processed.errorMessages());
        continue;
      }
      final var classes = plain.define(MethodHandles.lookup());
      final Class<Object> src = cast(classes.get(PACKAGE + "." + prefix + "Src"));
      final Class<Object> tgt = cast(classes.get(PACKAGE + "." + prefix + "Tgt"));
      // Built before the cell's own bridge is defined, so the runtime converts through its own row.
      final var nested = nestedBridgesFirst(processed, plain, prefix);
      final var runtime = classes.get(PACKAGE + "." + prefix + "Runtime").getMethod("mapper").invoke(null);
      @SuppressWarnings("unchecked")
      final Mapper<Object, Object> mapper = row.forwardOnly() ? null : (Mapper<Object, Object>) runtime;
      @SuppressWarnings("unchecked")
      final ForwardMapper<Object, Object> forwardMapper = row.forwardOnly()
        ? (ForwardMapper<Object, Object>) runtime
        : null;
      final var bridge = emitted(processed, plain, prefix, nested);
      final Attempt srcSample = () -> src.getMethod("sample").invoke(null);
      final Attempt tgtSample = () -> tgt.getMethod("sample").invoke(null);
      final var owed = new ArrayList<Map.Entry<String, String>>();
      owed.add(Map.entry("forward", row.forward()));
      if (row.backward() != null) owed.add(Map.entry("backward", row.backward()));
      if (row.patch() != null) owed.add(Map.entry("patch", row.patch()));
      for (final var operation : owed) {
        final Attempt generated = switch (operation.getKey()) {
          case "forward" -> () -> bridge.getMethod("forward", src).invoke(null, srcSample.get());
          case "backward" -> () -> bridge.getMethod("backward", tgt).invoke(null, tgtSample.get());
          default -> () -> bridge.getMethod("patch", src, tgt).invoke(null, srcSample.get(), tgtSample.get());
        };
        final Attempt reflective = switch (operation.getKey()) {
          case "forward" -> forwardMapper != null
            ? () -> forwardMapper.forward(srcSample.get())
            : () -> mapper.forward(srcSample.get());
          case "backward" -> () -> mapper.backward(tgtSample.get());
          default -> () -> mapper.patch(srcSample.get(), tgtSample.get());
        };
        for (final var side : List.of(Map.entry("generated", generated), Map.entry("reflective", reflective))) {
          final var shape = shapeOf(side.getValue());
          if (!shape.equals(operation.getValue())) {
            failures.add(
              row.name() +
                ", " +
                operation.getKey() +
                ": " +
                side.getKey() +
                " gave " +
                shape +
                ", owed " +
                operation.getValue()
            );
          }
        }
      }
    }
    assertTrue(failures.isEmpty(), () -> String.join("\n  ", failures));
  }

  @Test
  @DisplayName(
    "a transform that does not fit the erasure of a type-variable property is refused by name, and by javac on a row typed as the raw members"
  )
  void aTransformThatDoesNotFitATypeVariablePropertyIsRefusedByName() {
    // Each row: the prefix, the pair and its transform, the runtime row typed by what the raw
    // members return, and two fragments the processor's refusal has to contain.
    final var rows = List.of(
      List.of(
        "Vr0",
        transformedValue(false) + sampledGeneric("%1$sSrc<X>", "X value", "new %1$sSrc<>(7)"),
        sampled("%1$sTgt", "String value", "new %1$sTgt(\"q\")"),
        transformFn("String", "String", "a", "b"),
        runtimeRow(false, "value", "Object", "String"),
        "@Transform field=\"value\" does not fit forward",
        "X (held as its erasure java.lang.Object) -> java.lang.String"
      ),
      List.of(
        "Vr1",
        transformedValue(false) + sampledGeneric("%1$sSrc<T extends Number>", "T value", "new %1$sSrc<>(7)"),
        sampled("%1$sTgt", "String value", "new %1$sTgt(\"q\")"),
        transformFn("Object", "String", "String.valueOf(a)", "b"),
        runtimeRow(false, "value", "Number", "String"),
        "@Transform field=\"value\" does not fit backward",
        "java.lang.String -> T (held as its erasure java.lang.Number)"
      )
    );
    final var failures = new ArrayList<String>();
    for (final var row : rows) {
      final var prefix = row.get(0);
      final var head = "package " + PACKAGE + ";\n";
      final var all = row
        .subList(1, 5)
        .stream()
        .map(declaration -> declaration.formatted(prefix))
        .map(code -> source(declaredName(code), head + code))
        .toList();
      final var processed = bridged(all.subList(0, 3).toArray(JavaFileObject[]::new));
      if (processed.success() || !processed.hasError(row.get(5)) || !processed.hasError(row.get(6))) {
        failures.add(prefix + ": the processor owed a refusal naming the property, saw " + describe(processed));
      }
      final var plain = ProcessorHarness.compileFully(List.of(), List.of(), all.toArray(JavaFileObject[]::new));
      final var rowRefused = plain
        .errors()
        .stream()
        .allMatch(e -> e.getSource() != null && e.getSource().getName().endsWith(prefix + "Runtime.java"));
      if (plain.success() || !rowRefused) {
        failures.add(prefix + ": javac owed a refusal of the runtime row alone, saw " + describe(plain));
      }
    }
    assertTrue(failures.isEmpty(), () -> String.join("\n  ", failures));
  }

  /** {@code @Bridge} to {@code %1$sTgt} converting {@code child} through {@code %1$sChMap}. */
  private static final String VIA_CHILD =
    "@io.github.eschizoid.telescope.annotations.Bridge(value = %1$sTgt.class, viaMappers =" +
    " @io.github.eschizoid.telescope.annotations.ViaMapper(field = \"child\", using = %1$sChMap.class))\n";

  /**
   * The child pair, {@code %1$sCh} and {@code %1$sChDto}, and {@code %1$sChMap}, whose static
   * methods convert one to the other with the name upper-cased forward and lower-cased backward.
   */
  private static final List<String> CHILD_PAIR = List.of(
    "public record %1$sCh(String name) {}\n",
    "public record %1$sChDto(String name) {}\n",
    "public class %1$sChMap {\n" +
      "  public static %1$sChDto forward(final %1$sCh c) { return new %1$sChDto(c.name().toUpperCase()); }\n" +
      "  public static %1$sCh backward(final %1$sChDto d) { return new %1$sCh(d.name().toLowerCase()); }\n}\n"
  );

  /**
   * {@code %1$sRuntime}, whose {@code mapper()} builds the runtime mapper for the pair with a
   * {@code via} row reading {@code child} through {@code accessor} on both sides, over a child
   * mapper that converts as {@code %1$sChMap} does.
   */
  private static String viaRow(final String accessor) {
    return (
      "public class %1$sRuntime {\n" +
      "  @SuppressWarnings({\"rawtypes\", \"unchecked\"})\n" +
      "  public static Object mapper() {\n" +
      "    final var child = io.github.eschizoid.telescope.Telescope.mapper(%1$sCh.class, %1$sChDto.class,\n" +
      "      io.github.eschizoid.telescope.mapping.Mapping.to(%1$sCh::name, %1$sChDto::name," +
      " String::toUpperCase, String::toLowerCase));\n" +
      "    return io.github.eschizoid.telescope.Telescope.mapper(%1$sSrc.class, %1$sTgt.class,\n" +
      "      io.github.eschizoid.telescope.mapping.Mapping.<%1$sSrc, %1$sTgt>via(%1$sSrc::" +
      accessor +
      ", %1$sTgt::" +
      accessor +
      ", child));\n  }\n}\n"
    );
  }

  /** A generic bean {@code %1$sBase<T>} with one property, {@code child}, of type {@code T}. */
  private static final String CHILD_BASE =
    "public class %1$sBase<T> {\n" +
    "  private T child;\n" +
    "  public T getChild() { return child; }\n" +
    "  public void setChild(final T child) { this.child = child; }\n}\n";

  /**
   * A subclass of {@code %1$sBase} declared {@code header extends %1$sBase<argument>}, whose static
   * {@code sample()}, typed {@code sampleType}, holds {@code child}.
   */
  private static String childBean(
    final String header,
    final String argument,
    final String sampleType,
    final String child
  ) {
    return (
      "public class " +
      header +
      " extends %1$sBase<" +
      argument +
      "> {\n" +
      "  public static " +
      sampleType +
      " sample() { final " +
      sampleType +
      " s = new " +
      sampleType +
      "(); s.setChild(" +
      child +
      "); return s; }\n}\n"
    );
  }

  /**
   * A generic bean {@code %1$sBase<T bound>} whose one property, {@code value}, is declared {@code
   * declared}, a type naming {@code T}; a source {@code %1$sSrc<X bound>} with a transform on it
   * that passes its own variable through; and a target {@code %1$sTgt} that fixes the variable to
   * {@code fixed}. Each side's {@code sample()} holds the given value.
   */
  private static List<String> fixedByTarget(
    final String bound,
    final String declared,
    final String fixed,
    final String srcSample,
    final String tgtSample
  ) {
    return List.of(
      "public class %1$sBase<T" +
        bound +
        "> {\n" +
        "  private " +
        declared +
        " value;\n" +
        "  public " +
        declared +
        " getValue() { return value; }\n" +
        "  public void setValue(final " +
        declared +
        " value) { this.value = value; }\n}\n",
      transformedValue(false) +
        "public class %1$sSrc<X" +
        bound +
        "> extends %1$sBase<X> {\n" +
        "  public static %1$sSrc<" +
        fixed +
        "> sample() { final %1$sSrc<" +
        fixed +
        "> s = new %1$sSrc<>(); s.setValue(" +
        srcSample +
        "); return s; }\n}\n",
      "public class %1$sTgt extends %1$sBase<" +
        fixed +
        "> {\n" +
        "  public static %1$sTgt sample() { final var t = new %1$sTgt(); t.setValue(" +
        tgtSample +
        "); return t; }\n}\n"
    );
  }

  /**
   * A transform or a {@code @ViaMapper} on {@code property} that the processor refuses by the
   * property's name and a runtime row still runs: the declarations, each with {@code %1$s} as the
   * row's prefix and the last of them the runtime row.
   */
  private record UncheckedConversion(String name, String property, List<String> declarations) {}

  private static final List<UncheckedConversion> UNCHECKED_CONVERSIONS = List.of(
    new UncheckedConversion(
      "a target that fixes the variable to String, with a transform returning Object",
      "value",
      concat(
        fixedByTarget("", "T", "String", "\"a\"", "\"q\""),
        transformFn("Object", "Object", "\"<\" + a + \">\"", "b"),
        witnessedRow(false, "getValue", "%1$sSrc, %1$sTgt, Object, Object")
      )
    ),
    new UncheckedConversion(
      "a target that fixes a variable bounded by Number to Integer, with a transform on Number",
      "value",
      concat(
        fixedByTarget(" extends Number", "T", "Integer", "7", "41"),
        transformFn("Number", "Number", "a.intValue() + 1", "b.intValue() - 1"),
        witnessedRow(false, "getValue", "%1$sSrc, %1$sTgt, Number, Number")
      )
    ),
    new UncheckedConversion(
      "a target that fixes the variable in a Map to String, with a transform on any Map",
      "value",
      concat(
        fixedByTarget(
          "",
          "java.util.Map<String, T>",
          "String",
          "java.util.Map.of(\"k\", \"a\")",
          "java.util.Map.of(\"k\", \"q\")"
        ),
        transformFn(
          "java.util.Map<?, ?>",
          "java.util.Map<?, ?>",
          "java.util.Map.of(\"n\", String.valueOf(a.size()))",
          "b"
        ),
        witnessedRow(false, "getValue", "%1$sSrc, %1$sTgt, java.util.Map, java.util.Map")
      )
    ),
    new UncheckedConversion(
      "a target that fixes the variable in an Optional to String, with a transform on any Optional",
      "value",
      concat(
        fixedByTarget(
          "",
          "java.util.Optional<T>",
          "String",
          "java.util.Optional.of(\"a\")",
          "java.util.Optional.of(\"q\")"
        ),
        transformFn("java.util.Optional<?>", "java.util.Optional<?>", "a.map(v -> \"<\" + v + \">\")", "b"),
        witnessedRow(false, "getValue", "%1$sSrc, %1$sTgt, java.util.Optional, java.util.Optional")
      )
    ),
    new UncheckedConversion(
      "a target built through a generic builder its builder() fixes to String, with a transform returning Object",
      "value",
      List.of(
        fixedByTarget("", "T", "String", "\"a\"", "\"q\"").get(0),
        fixedByTarget("", "T", "String", "\"a\"", "\"q\"").get(1),
        "public class %1$sTgt {\n" +
          "  private final String value;\n" +
          "  %1$sTgt(final String value) { this.value = value; }\n" +
          "  public String getValue() { return value; }\n" +
          "  public static %1$sMaker<String> builder() { return new %1$sMaker<>(); }\n" +
          "  public static %1$sTgt sample() { return new %1$sTgt(\"q\"); }\n}\n",
        "public class %1$sMaker<T> {\n" +
          "  private T value;\n" +
          "  public %1$sMaker<T> value(final T value) { this.value = value; return this; }\n" +
          "  public %1$sTgt build() { return new %1$sTgt((String) value); }\n}\n",
        transformFn("Object", "Object", "\"<\" + a + \">\"", "b"),
        witnessedRow(false, "getValue", "%1$sSrc, %1$sTgt, Object, Object")
      )
    ),
    new UncheckedConversion(
      "a row typed by both classes' arguments, with a transform on String",
      "value",
      List.of(
        transformedValue(false) + sampledGeneric("%1$sSrc<X>", "X value", "new %1$sSrc<>(\"a\")"),
        sampledGeneric("%1$sTgt<Y>", "Y value", "new %1$sTgt<>(\"q\")"),
        transformFn("String", "String", "a + \"!\"", "b + \"?\""),
        witnessedRow(false, "value", "%1$sSrc<String>, %1$sTgt<String>, String, String")
      )
    ),
    new UncheckedConversion(
      "a row typed by the source's argument under Number, with a transform on Integer",
      "value",
      List.of(
        transformedValue(false) + sampledGeneric("%1$sSrc<T extends Number>", "T value", "new %1$sSrc<>(7)"),
        sampled("%1$sTgt", "Integer value", "new %1$sTgt(41)"),
        transformFn("Integer", "Integer", "a + 1", "b - 1"),
        witnessedRow(false, "value", "%1$sSrc<Integer>, %1$sTgt, Integer, Integer")
      )
    ),
    new UncheckedConversion(
      "a row reading a variable bounded by Number as Object, with a backward returning Object",
      "value",
      List.of(
        transformedValue(false) + sampledGeneric("%1$sSrc<T extends Number>", "T value", "new %1$sSrc<>(7)"),
        sampled("%1$sTgt", "String value", "new %1$sTgt(\"q\")"),
        transformFn("Object", "String", "String.valueOf(a)", "b"),
        witnessedRow(false, "value", "%1$sSrc, %1$sTgt, Object, String")
      )
    ),
    new UncheckedConversion(
      "a @ViaMapper whose forward returns a class the target's fixed property does not take",
      "child",
      List.of(
        "public record %1$sCh(String name) {}\n",
        "public record %1$sOtherDto(String name) {}\n",
        "public class %1$sChMap {\n" +
          "  public static %1$sOtherDto forward(final %1$sCh c) { return new %1$sOtherDto(c.name()); }\n" +
          "  public static %1$sCh backward(final %1$sChDto d) { return new %1$sCh(d.name()); }\n}\n",
        "public record %1$sChDto(String name) {}\n",
        CHILD_BASE,
        VIA_CHILD + childBean("%1$sSrc<X>", "X", "%1$sSrc<%1$sCh>", "new %1$sCh(\"a\")"),
        childBean("%1$sTgt", "%1$sChDto", "%1$sTgt", "new %1$sChDto(\"q\")"),
        "public class %1$sRuntime {\n" +
          "  @SuppressWarnings({\"rawtypes\", \"unchecked\"})\n" +
          "  public static Object mapper() {\n" +
          "    final var child = io.github.eschizoid.telescope.Telescope.mapper(%1$sCh.class, %1$sOtherDto.class);\n" +
          "    return io.github.eschizoid.telescope.Telescope.mapper(%1$sSrc.class, %1$sTgt.class,\n" +
          "      io.github.eschizoid.telescope.mapping.Mapping.<%1$sSrc, %1$sTgt>via(%1$sSrc::getChild, %1$sTgt::getChild," +
          " child));\n  }\n}\n"
      )
    ),
    new UncheckedConversion(
      "a @ViaMapper whose forward has an overload for Object besides the child, on a property both sides pass through",
      "child",
      List.of(
        "public record %1$sCh(String name) {}\n",
        "public record %1$sChDto(String name) {}\n",
        "public class %1$sChMap {\n" +
          "  public static %1$sChDto forward(final %1$sCh c) { return new %1$sChDto(\"f:\" + c.name()); }\n" +
          "  public static %1$sChDto forward(final Object o) { return new %1$sChDto(\"o:\" + o); }\n" +
          "  public static %1$sCh backward(final %1$sChDto d) { return new %1$sCh(d.name()); }\n}\n",
        CHILD_BASE,
        VIA_CHILD + childBean("%1$sSrc<X>", "X", "%1$sSrc<%1$sCh>", "new %1$sCh(\"a\")"),
        childBean("%1$sTgt<Y>", "Y", "%1$sTgt<%1$sChDto>", "new %1$sChDto(\"q\")"),
        "public class %1$sRuntime {\n" +
          "  @SuppressWarnings({\"rawtypes\", \"unchecked\"})\n" +
          "  public static Object mapper() {\n" +
          "    final var child = io.github.eschizoid.telescope.Telescope.mapper(%1$sCh.class, %1$sChDto.class,\n" +
          "      io.github.eschizoid.telescope.mapping.Mapping.to(%1$sCh::name, %1$sChDto::name, n -> \"f:\" + n, n -> n));\n" +
          "    return io.github.eschizoid.telescope.Telescope.mapper(%1$sSrc.class, %1$sTgt.class,\n" +
          "      io.github.eschizoid.telescope.mapping.Mapping.<%1$sSrc, %1$sTgt>via(%1$sSrc::getChild, %1$sTgt::getChild," +
          " child));\n  }\n}\n"
      )
    ),
    new UncheckedConversion(
      "a @ViaMapper whose forward has an overload for String besides the child, on a property both sides pass through",
      "child",
      List.of(
        "public record %1$sCh(String name) {}\n",
        "public record %1$sChDto(String name) {}\n",
        "public class %1$sChMap {\n" +
          "  public static %1$sChDto forward(final %1$sCh c) { return new %1$sChDto(\"f:\" + c.name()); }\n" +
          "  public static %1$sChDto forward(final String o) { return new %1$sChDto(\"s:\" + o); }\n" +
          "  public static %1$sCh backward(final %1$sChDto d) { return new %1$sCh(d.name()); }\n}\n",
        CHILD_BASE,
        VIA_CHILD + childBean("%1$sSrc<X>", "X", "%1$sSrc<%1$sCh>", "new %1$sCh(\"a\")"),
        childBean("%1$sTgt<Y>", "Y", "%1$sTgt<%1$sChDto>", "new %1$sChDto(\"q\")"),
        "public class %1$sRuntime {\n" +
          "  @SuppressWarnings({\"rawtypes\", \"unchecked\"})\n" +
          "  public static Object mapper() {\n" +
          "    final var child = io.github.eschizoid.telescope.Telescope.mapper(%1$sCh.class, %1$sChDto.class,\n" +
          "      io.github.eschizoid.telescope.mapping.Mapping.to(%1$sCh::name, %1$sChDto::name, n -> \"f:\" + n, n -> n));\n" +
          "    return io.github.eschizoid.telescope.Telescope.mapper(%1$sSrc.class, %1$sTgt.class,\n" +
          "      io.github.eschizoid.telescope.mapping.Mapping.<%1$sSrc, %1$sTgt>via(%1$sSrc::getChild, %1$sTgt::getChild," +
          " child));\n  }\n}\n"
      )
    )
  );

  /**
   * What {@code attempt}'s result holds as {@code property}, read through its record accessor or
   * its getter, inherited or not, with the value's class, each without the row's {@code prefix}; or
   * the refusal.
   */
  private static String propertyHeld(final String prefix, final String property, final Attempt attempt) {
    try {
      final var out = attempt.get();
      final var type = out.getClass();
      final var getter = type.isRecord()
        ? type.getMethod(property)
        : type.getMethod("get" + Character.toUpperCase(property.charAt(0)) + property.substring(1));
      final var value = getter.invoke(out);
      final var held = property + "=" + value + ", a " + (value == null ? "null" : value.getClass().getSimpleName());
      return held.replace(prefix, "");
    } catch (final InvocationTargetException e) {
      return REFUSED + (e.getCause() == null ? e : e.getCause()).getClass().getSimpleName();
    } catch (final ReflectiveOperationException | RuntimeException e) {
      return REFUSED + e.getClass().getSimpleName();
    }
  }

  /**
   * The direction a transform on {@code value} was refused in, and the member type the refusal
   * names where it names one; the call and the reason a {@code @ViaMapper} on {@code child} was
   * refused for; or the compilation, where nothing refused the row.
   */
  private static String refusalOfConversion(final ProcessorHarness.Compilation processed) {
    for (final var direction : List.of("forward", "backward")) {
      for (final var reason : List.of("takes no value", "returns", "has several overloads")) {
        if (processed.hasError("@ViaMapper field=\"child\"") && processed.hasError("'s " + direction + " " + reason)) {
          return REFUSED + "@ViaMapper " + direction + " " + reason;
        }
      }
    }
    for (final var direction : List.of("forward", "backward")) {
      if (!processed.hasError("@Transform field=\"value\" does not fit " + direction)) continue;
      final var member = Pattern.compile("the member it is written through takes (\\S+)").matcher(
        processed.errorMessages()
      );
      return (
        REFUSED + "does not fit " + direction + (member.find() ? ", through a member taking " + member.group(1) : "")
      );
    }
    return describe(processed);
  }

  /** {@code head} followed by {@code tail}. */
  private static List<String> concat(final List<String> head, final String... tail) {
    final var all = new ArrayList<>(head);
    all.addAll(List.of(tail));
    return List.copyOf(all);
  }

  /**
   * Shapes the processor refuses by the property's name while a runtime row still runs, each with
   * the generated path's refusal and then what the runtime gives forward and backward. Neither path
   * checks a value against the type of the property it is read from or written to: the runtime row
   * hands its functions, or a {@code via} row its mapper, whatever it reads, and writes whatever
   * they return. A value that happens to fit is converted; one that does not is written as it is,
   * or throws a bare {@code ClassCastException} where the code it reaches checks its class.
   *
   * <p>An entry no row exercises, or whose row either path stops giving, fails the test.
   */
  private static final Map<String, List<String>> UNCHECKED_CONVERSION_DIVERGENCES = Map.ofEntries(
    // A defect still to close, in this entry and the four after it: the runtime writes a
    // transform's result into a property a target fixes to a narrower type without checking
    // it, where the processor refuses the transform.
    Map.entry(
      "a target that fixes the variable to String, with a transform returning Object",
      List.of(REFUSED + "does not fit forward", "value=<a>, a String", "value=q, a String")
    ),
    Map.entry(
      "a target that fixes a variable bounded by Number to Integer, with a transform on Number",
      List.of(REFUSED + "does not fit forward", "value=8, a Integer", "value=40, a Integer")
    ),
    Map.entry(
      "a target that fixes the variable in a Map to String, with a transform on any Map",
      List.of(REFUSED + "does not fit forward", "value={n=1}, a Map1", "value={k=q}, a Map1")
    ),
    Map.entry(
      "a target that fixes the variable in an Optional to String, with a transform on any Optional",
      List.of(REFUSED + "does not fit forward", "value=Optional[<a>], a Optional", "value=Optional[q], a Optional")
    ),
    Map.entry(
      "a target built through a generic builder its builder() fixes to String, with a transform returning Object",
      List.of(REFUSED + "does not fit forward", "value=<a>, a String", "value=q, a String")
    ),
    // A defect still to close, in this entry and the next: a runtime row typed by a class's
    // arguments reads the property as that argument without checking it, where the
    // processor holds it as the erasure and refuses a transform narrower than that.
    Map.entry(
      "a row typed by both classes' arguments, with a transform on String",
      List.of(REFUSED + "does not fit forward", "value=a!, a String", "value=q?, a String")
    ),
    Map.entry(
      "a row typed by the source's argument under Number, with a transform on Integer",
      List.of(REFUSED + "does not fit forward", "value=8, a Integer", "value=40, a Integer")
    ),
    // A defect still to close: the runtime writes a backward result into a property bounded
    // by Number without checking it, and throws a bare ClassCastException where the
    // processor refuses the transform by name.
    Map.entry(
      "a row reading a variable bounded by Number as Object, with a backward returning Object",
      List.of(REFUSED + "does not fit backward", "value=7, a String", REFUSED + "ClassCastException")
    ),
    // A defect still to close: the runtime writes the via mapper's result into a property
    // the target fixes to another class without checking it, where the processor refuses
    // the row.
    Map.entry(
      "a @ViaMapper whose forward returns a class the target's fixed property does not take",
      List.of(
        REFUSED + "@ViaMapper forward returns",
        "child=OtherDto[name=a], a OtherDto",
        REFUSED + "ClassCastException"
      )
    ),
    // A defect still to close, in this entry and the next: the processor refuses an
    // overloaded mapper method on a value it holds only as Object, since the overload javac
    // picks for Object need not be the one the value's class calls for, while the runtime
    // row converts through the one mapper it has.
    Map.entry(
      "a @ViaMapper whose forward has an overload for Object besides the child, on a property both sides pass through",
      List.of(
        REFUSED + "@ViaMapper forward has several overloads",
        "child=ChDto[name=f:a], a ChDto",
        "child=Ch[name=q], a Ch"
      )
    ),
    Map.entry(
      "a @ViaMapper whose forward has an overload for String besides the child, on a property both sides pass through",
      List.of(
        REFUSED + "@ViaMapper forward has several overloads",
        "child=ChDto[name=f:a], a ChDto",
        "child=Ch[name=q], a Ch"
      )
    )
  );

  @Test
  @DisplayName("a conversion the processor refuses by name and a runtime row still runs is a registered divergence")
  void aConversionOnlyTheRuntimeRunsIsARegisteredDivergence() throws ReflectiveOperationException {
    final var failures = new ArrayList<String>();
    final var exercised = new LinkedHashSet<String>();
    var index = 0;
    for (final var row : UNCHECKED_CONVERSIONS) {
      final var prefix = "Ud" + index++;
      final var head = "package " + PACKAGE + ";\n";
      final var all = row
        .declarations()
        .stream()
        .map(declaration -> declaration.formatted(prefix))
        .map(code -> source(declaredName(code), head + code))
        .toList();
      final var plain = ProcessorHarness.compileFully(List.of(), List.of(), all.toArray(JavaFileObject[]::new));
      assertTrue(
        plain.success(),
        () -> row.name() + ": the pair and its runtime row should compile: " + plain.errorMessages()
      );
      final var processed = bridged(all.subList(0, all.size() - 1).toArray(JavaFileObject[]::new));
      final var generated = refusalOfConversion(processed);
      final var classes = plain.define(MethodHandles.lookup());
      final Class<Object> src = cast(classes.get(PACKAGE + "." + prefix + "Src"));
      final Class<Object> tgt = cast(classes.get(PACKAGE + "." + prefix + "Tgt"));
      @SuppressWarnings("unchecked")
      final var mapper = (Mapper<Object, Object>) classes
        .get(PACKAGE + "." + prefix + "Runtime")
        .getMethod("mapper")
        .invoke(null);
      final var seen = List.of(
        generated,
        propertyHeld(prefix, row.property(), () -> mapper.forward(src.getMethod("sample").invoke(null))),
        propertyHeld(prefix, row.property(), () -> mapper.backward(tgt.getMethod("sample").invoke(null)))
      );
      final var registered = UNCHECKED_CONVERSION_DIVERGENCES.get(row.name());
      if (registered != null) exercised.add(row.name());
      if (!seen.equals(registered)) {
        failures.add(row.name() + ": generated and runtime gave " + seen + ", registered " + registered);
      }
    }
    assertTrue(failures.isEmpty(), () -> String.join("\n  ", failures));
    assertTrue(
      exercised.equals(UNCHECKED_CONVERSION_DIVERGENCES.keySet()),
      () -> "registered divergences with no row: " + UNCHECKED_CONVERSION_DIVERGENCES.keySet()
    );
  }

  /**
   * A {@code @ViaMapper} on a property typed by a type variable: the declarations other than the
   * child pair, each with {@code %1$s} as the row's prefix and the last of them the runtime row,
   * and what both paths owe for {@code Src.sample()} forward, {@code Tgt.sample()} backward, and
   * {@code Tgt.sample()} laid over {@code Src.sample()}.
   */
  private record ViaVariable(String name, List<String> declarations, String forward, String backward, String patch) {}

  private static final List<ViaVariable> VIA_VARIABLES = List.of(
    new ViaVariable(
      "a source record typed by its own variable bounded by the child",
      List.of(
        VIA_CHILD + sampledGeneric("%1$sSrc<X extends %1$sCh>", "X child", "new %1$sSrc<>(new %1$sCh(\"a\"))"),
        sampled("%1$sTgt", "%1$sChDto child", "new %1$sTgt(new %1$sChDto(\"Q\"))"),
        viaRow("child")
      ),
      "child=ChDto:A",
      "child=Ch:q",
      "child=Ch:q"
    ),
    new ViaVariable(
      "a target record typed by its own variable",
      List.of(
        VIA_CHILD + sampled("%1$sSrc", "%1$sCh child", "new %1$sSrc(new %1$sCh(\"a\"))"),
        sampledGeneric("%1$sTgt<Y>", "Y child", "new %1$sTgt<>(new %1$sChDto(\"Q\"))"),
        viaRow("child")
      ),
      "child=ChDto:A",
      "child=Ch:q",
      "child=Ch:q"
    ),
    new ViaVariable(
      "a property both sides pass through to a generic superclass",
      List.of(
        CHILD_BASE,
        VIA_CHILD + childBean("%1$sSrc<X>", "X", "%1$sSrc<%1$sCh>", "new %1$sCh(\"a\")"),
        childBean("%1$sTgt<Y>", "Y", "%1$sTgt<%1$sChDto>", "new %1$sChDto(\"Q\")"),
        viaRow("getChild")
      ),
      "child=ChDto:A",
      "child=Ch:q",
      "child=Ch:q"
    ),
    // The bridge casts the value to the mapper's parameter class, and the runtime mapper
    // reads it
    // as that class, so a value of another class is refused at the call on both paths.
    new ViaVariable(
      "a property both sides pass through, holding a value that is not the child",
      List.of(
        CHILD_BASE,
        VIA_CHILD + childBean("%1$sSrc<X>", "X", "%1$sSrc<Object>", "\"a\""),
        childBean("%1$sTgt<Y>", "Y", "%1$sTgt<Object>", "\"Q\""),
        viaRow("getChild")
      ),
      REFUSED + "ClassCastException",
      REFUSED + "ClassCastException",
      REFUSED + "ClassCastException"
    ),
    new ViaVariable(
      "a property the source passes through and the target fixes to the child's counterpart",
      List.of(
        CHILD_BASE,
        VIA_CHILD + childBean("%1$sSrc<X>", "X", "%1$sSrc<%1$sCh>", "new %1$sCh(\"a\")"),
        childBean("%1$sTgt", "%1$sChDto", "%1$sTgt", "new %1$sChDto(\"Q\")"),
        viaRow("getChild")
      ),
      "child=ChDto:A",
      "child=Ch:q",
      "child=Ch:q"
    ),
    new ViaVariable(
      "a property the source fixes to the child and the target passes through",
      List.of(
        CHILD_BASE,
        VIA_CHILD + childBean("%1$sSrc", "%1$sCh", "%1$sSrc", "new %1$sCh(\"a\")"),
        childBean("%1$sTgt<Y>", "Y", "%1$sTgt<%1$sChDto>", "new %1$sChDto(\"Q\")"),
        viaRow("getChild")
      ),
      "child=ChDto:A",
      "child=Ch:q",
      "child=Ch:q"
    ),
    new ViaVariable(
      "a property read and written through generic methods",
      List.of(
        genericAccessorBean(VIA_CHILD, "%1$sSrc", "child", "new %1$sCh(\"a\")"),
        "public class %1$sTgt {\n" +
          "  private %1$sChDto child;\n" +
          "  public %1$sChDto getChild() { return child; }\n" +
          "  public void setChild(final %1$sChDto child) { this.child = child; }\n" +
          "  public static %1$sTgt sample() { final var t = new %1$sTgt(); t.setChild(new %1$sChDto(\"Q\")); return t; }\n}\n",
        viaRow("getChild")
      ),
      "child=ChDto:A",
      "child=Ch:q",
      "child=Ch:q"
    ),
    // A variable with a bound is held as the bound, and javac picks among the
    // mapper's overloads by it, as a method reference typed by the bound does.
    new ViaVariable(
      "a source record typed by a variable bounded by the child, to a mapper overloaded for Object",
      List.of(
        "public class %1$sChMap {\n" +
          "  public static %1$sChDto forward(final %1$sCh c) { return new %1$sChDto(c.name().toUpperCase()); }\n" +
          "  public static %1$sChDto forward(final Object o) { return new %1$sChDto(\"o:\" + o); }\n" +
          "  public static %1$sCh backward(final %1$sChDto d) { return new %1$sCh(d.name().toLowerCase()); }\n}\n",
        VIA_CHILD + sampledGeneric("%1$sSrc<X extends %1$sCh>", "X child", "new %1$sSrc<>(new %1$sCh(\"a\"))"),
        sampled("%1$sTgt", "%1$sChDto child", "new %1$sTgt(new %1$sChDto(\"Q\"))"),
        viaRow("child")
      ),
      "child=ChDto:A",
      "child=Ch:q",
      "child=Ch:q"
    ),
    new ViaVariable(
      "a source record typed by a variable bounded by the child, to a mapper overloaded for String",
      List.of(
        "public class %1$sChMap {\n" +
          "  public static %1$sChDto forward(final %1$sCh c) { return new %1$sChDto(c.name().toUpperCase()); }\n" +
          "  public static %1$sChDto forward(final String o) { return new %1$sChDto(\"s:\" + o); }\n" +
          "  public static %1$sCh backward(final %1$sChDto d) { return new %1$sCh(d.name().toLowerCase()); }\n}\n",
        VIA_CHILD + sampledGeneric("%1$sSrc<X extends %1$sCh>", "X child", "new %1$sSrc<>(new %1$sCh(\"a\"))"),
        sampled("%1$sTgt", "%1$sChDto child", "new %1$sTgt(new %1$sChDto(\"Q\"))"),
        viaRow("child")
      ),
      "child=ChDto:A",
      "child=Ch:q",
      "child=Ch:q"
    ),
    new ViaVariable(
      "a property read through a generic method bounded by the child, to a mapper overloaded for Object",
      List.of(
        "public class %1$sChMap {\n" +
          "  public static %1$sChDto forward(final %1$sCh c) { return new %1$sChDto(c.name().toUpperCase()); }\n" +
          "  public static %1$sChDto forward(final Object o) { return new %1$sChDto(\"o:\" + o); }\n" +
          "  public static %1$sCh backward(final %1$sChDto d) { return new %1$sCh(d.name().toLowerCase()); }\n}\n",
        VIA_CHILD +
          "public class %1$sSrc {\n" +
          "  private %1$sCh child;\n" +
          "  @SuppressWarnings(\"unchecked\") public <T extends %1$sCh> T getChild() { return (T) child; }\n" +
          "  public <T extends %1$sCh> void setChild(final T child) { this.child = child; }\n" +
          "  public static %1$sSrc sample() { final var s = new %1$sSrc(); s.setChild(new %1$sCh(\"a\")); return s; }\n}\n",
        "public class %1$sTgt {\n" +
          "  private %1$sChDto child;\n" +
          "  public %1$sChDto getChild() { return child; }\n" +
          "  public void setChild(final %1$sChDto child) { this.child = child; }\n" +
          "  public static %1$sTgt sample() { final var t = new %1$sTgt(); t.setChild(new %1$sChDto(\"Q\")); return t; }\n}\n",
        viaRow("getChild")
      ),
      "child=ChDto:A",
      "child=Ch:q",
      "child=Ch:q"
    ),
    // A mapper parameter typed by the method's own variable erases to the type the
    // bridge holds the value as, and takes it as it is.
    new ViaVariable(
      "a property both sides pass through, to a mapper whose forward takes its own variable",
      List.of(
        "public class %1$sChMap {\n" +
          "  public static <T> %1$sChDto forward(final T c) {" +
          " return new %1$sChDto(((%1$sCh) c).name().toUpperCase()); }\n" +
          "  public static %1$sCh backward(final %1$sChDto d) { return new %1$sCh(d.name().toLowerCase()); }\n}\n",
        CHILD_BASE,
        VIA_CHILD + childBean("%1$sSrc<X>", "X", "%1$sSrc<%1$sCh>", "new %1$sCh(\"a\")"),
        childBean("%1$sTgt<Y>", "Y", "%1$sTgt<%1$sChDto>", "new %1$sChDto(\"Q\")"),
        viaRow("getChild")
      ),
      "child=ChDto:A",
      "child=Ch:q",
      "child=Ch:q"
    )
  );

  @Test
  @DisplayName("a @ViaMapper on a property typed by a type variable converts the same way on both paths")
  void aViaMapperOnATypeVariablePropertyConvertsTheSameWayOnBothPaths() throws ReflectiveOperationException {
    final var failures = new ArrayList<String>();
    var index = 0;
    for (final var row : VIA_VARIABLES) {
      final var prefix = "Vm" + index++;
      final var head = "package " + PACKAGE + ";\n";
      final var ownMapper = row
        .declarations()
        .stream()
        .anyMatch(d -> d.contains("class %1$sChMap "));
      final var all = concat(
        ownMapper ? CHILD_PAIR.subList(0, 2) : CHILD_PAIR,
        row.declarations().toArray(String[]::new)
      )
        .stream()
        .map(declaration -> declaration.formatted(prefix))
        .map(code -> source(declaredName(code), head + code))
        .toList();
      final var plain = ProcessorHarness.compileFully(List.of(), List.of(), all.toArray(JavaFileObject[]::new));
      assertTrue(
        plain.success(),
        () -> row.name() + ": the pair and its runtime row should compile: " + plain.errorMessages()
      );
      // A warning fails this row and the others still run, so each row reports on its own.
      final ProcessorHarness.Compilation processed;
      try {
        processed = bridged(all.subList(0, all.size() - 1).toArray(JavaFileObject[]::new));
      } catch (final AssertionError warned) {
        failures.add(row.name() + ": " + warned.getMessage());
        continue;
      }
      if (!processed.success()) {
        failures.add(row.name() + ": the generated bridge should compile: " + processed.errorMessages());
        continue;
      }
      final var classes = plain.define(MethodHandles.lookup());
      final Class<Object> src = cast(classes.get(PACKAGE + "." + prefix + "Src"));
      final Class<Object> tgt = cast(classes.get(PACKAGE + "." + prefix + "Tgt"));
      // Built before the cell's own bridge is defined, so the runtime converts through its own row.
      final var nested = nestedBridgesFirst(processed, plain, prefix);
      @SuppressWarnings("unchecked")
      final var mapper = (Mapper<Object, Object>) classes
        .get(PACKAGE + "." + prefix + "Runtime")
        .getMethod("mapper")
        .invoke(null);
      final var bridge = emitted(processed, plain, prefix, nested);
      final Attempt srcSample = () -> src.getMethod("sample").invoke(null);
      final Attempt tgtSample = () -> tgt.getMethod("sample").invoke(null);
      final var owed = List.of(
        Map.entry("forward", row.forward()),
        Map.entry("backward", row.backward()),
        Map.entry("patch", row.patch())
      );
      for (final var operation : owed) {
        final Attempt generated = switch (operation.getKey()) {
          case "forward" -> () -> bridge.getMethod("forward", src).invoke(null, srcSample.get());
          case "backward" -> () -> bridge.getMethod("backward", tgt).invoke(null, tgtSample.get());
          default -> () -> bridge.getMethod("patch", src, tgt).invoke(null, srcSample.get(), tgtSample.get());
        };
        final Attempt reflective = switch (operation.getKey()) {
          case "forward" -> () -> mapper.forward(srcSample.get());
          case "backward" -> () -> mapper.backward(tgtSample.get());
          default -> () -> mapper.patch(srcSample.get(), tgtSample.get());
        };
        for (final var side : List.of(Map.entry("generated", generated), Map.entry("reflective", reflective))) {
          final var held = childHeld(prefix, side.getValue());
          if (!held.equals(operation.getValue())) {
            failures.add(
              row.name() +
                ", " +
                operation.getKey() +
                ": " +
                side.getKey() +
                " gave " +
                held +
                ", owed " +
                operation.getValue()
            );
          }
        }
      }
    }
    assertTrue(failures.isEmpty(), () -> String.join("\n  ", failures));
  }

  /**
   * What {@code attempt}'s result holds as {@code child}, read through its record accessor or its
   * getter, inherited or not: the child's class without the row's prefix and its name; or the
   * refusal.
   */
  private static String childHeld(final String prefix, final Attempt attempt) {
    try {
      final var out = attempt.get();
      final var type = out.getClass();
      final var child = (type.isRecord() ? type.getMethod("child") : type.getMethod("getChild")).invoke(out);
      if (child == null) return "child=null";
      final var name = child.getClass().getMethod("name").invoke(child);
      return "child=" + child.getClass().getSimpleName().replace(prefix, "") + ":" + name;
    } catch (final InvocationTargetException e) {
      return REFUSED + (e.getCause() == null ? e : e.getCause()).getClass().getSimpleName();
    } catch (final ReflectiveOperationException | RuntimeException e) {
      return REFUSED + e.getClass().getSimpleName();
    }
  }

  /** {@code @Bridge} to {@code %1$sTgt} converting {@code children} through {@code %1$sChMap}. */
  private static final String VIA_CHILDREN =
    "@io.github.eschizoid.telescope.annotations.Bridge(value = %1$sTgt.class, viaMappers =" +
    " @io.github.eschizoid.telescope.annotations.ViaMapper(field = \"children\", using = %1$sChMap.class))\n";

  /**
   * A {@code @ViaMapper} on a container property: the mapper class, null for the child pair's own,
   * the source and target declarations, each with {@code %1$s} as the row's prefix, and what each
   * path owes for {@code Src.sample()} forward, and for {@code Tgt.sample()} backward and laid over
   * {@code Src.sample()}: the runtime first, then the generated bridge.
   */
  private record ViaContainer(
    String name,
    String mapper,
    String source,
    String target,
    String forward,
    String backward,
    String generatedForward,
    String generatedBackward
  ) {
    /** A row both paths owe the same results for, through the child pair's own mapper. */
    ViaContainer(
      final String name,
      final String source,
      final String target,
      final String forward,
      final String backward
    ) {
      this(name, null, source, target, forward, backward, forward, backward);
    }
  }

  /**
   * A mapper class with the child pair's element methods and an overload of each that converts a
   * whole list, marking what it builds with {@code W} forward and {@code WB} backward.
   */
  private static final String WHOLE_LIST_OVERLOADS =
    "public class %1$sChMap {\n" +
    "  public static %1$sChDto forward(final %1$sCh c) { return new %1$sChDto(c.name().toUpperCase()); }\n" +
    "  public static %1$sCh backward(final %1$sChDto d) { return new %1$sCh(d.name().toLowerCase()); }\n" +
    "  public static java.util.List<%1$sChDto> forward(final java.util.List<%1$sCh> l) {" +
    " return l.stream().map(c -> new %1$sChDto(\"W\" + c.name())).toList(); }\n" +
    "  public static java.util.List<%1$sCh> backward(final java.util.List<%1$sChDto> l) {" +
    " return l.stream().map(d -> new %1$sCh(\"WB\" + d.name())).toList(); }\n}\n";

  /**
   * As {@link #WHOLE_LIST_OVERLOADS}, with each whole-list overload typed by its own result
   * variable, which the call site infers, and marking what it builds with {@code GW} and {@code
   * GWB}.
   */
  private static final String WHOLE_LIST_INFERRED =
    "@SuppressWarnings(\"unchecked\")\npublic class %1$sChMap {\n" +
    "  public static %1$sChDto forward(final %1$sCh c) { return new %1$sChDto(c.name().toUpperCase()); }\n" +
    "  public static %1$sCh backward(final %1$sChDto d) { return new %1$sCh(d.name().toLowerCase()); }\n" +
    "  public static <R> R forward(final java.util.List<%1$sCh> l) {" +
    " return (R) l.stream().map(c -> new %1$sChDto(\"GW\" + c.name())).toList(); }\n" +
    "  public static <R> R backward(final java.util.List<%1$sChDto> l) {" +
    " return (R) l.stream().map(d -> new %1$sCh(\"GWB\" + d.name())).toList(); }\n}\n";

  /**
   * A whole-list forward with a declared result, and a whole-list backward with an inferred one.
   */
  private static final String WHOLE_LIST_HALF_INFERRED =
    "@SuppressWarnings(\"unchecked\")\npublic class %1$sChMap {\n" +
    "  public static %1$sChDto forward(final %1$sCh c) { return new %1$sChDto(c.name().toUpperCase()); }\n" +
    "  public static %1$sCh backward(final %1$sChDto d) { return new %1$sCh(d.name().toLowerCase()); }\n" +
    "  public static java.util.List<%1$sChDto> forward(final java.util.List<%1$sCh> l) {" +
    " return l.stream().map(c -> new %1$sChDto(\"W\" + c.name())).toList(); }\n" +
    "  public static <R> R backward(final java.util.List<%1$sChDto> l) {" +
    " return (R) l.stream().map(d -> new %1$sCh(\"GWB\" + d.name())).toList(); }\n}\n";

  /** A container row's source, generic over its element or not, holding {@code sample}. */
  private static String containerSource(final boolean generic, final String declared, final String sample) {
    return generic
      ? VIA_CHILDREN +
        sampledGeneric("%1$sSrc<X>", declared.replace("%1$sCh", "X") + " children", "new %1$sSrc<>(" + sample + ")")
      : VIA_CHILDREN + sampled("%1$sSrc", declared + " children", "new %1$sSrc(" + sample + ")");
  }

  private static List<ViaContainer> viaContainers() {
    final var rows = new ArrayList<ViaContainer>();
    for (final var generic : List.of(false, true)) {
      final var kind = generic ? "a source generic over its element" : "a source fixed to the child";
      rows.add(
        new ViaContainer(
          "a List property, " + kind,
          containerSource(generic, "java.util.List<%1$sCh>", "java.util.List.of(new %1$sCh(\"a\"))"),
          sampled(
            "%1$sTgt",
            "java.util.List<%1$sChDto> children",
            "new %1$sTgt(java.util.List.of(new %1$sChDto(\"Q\")))"
          ),
          "children=[ChDto:A]",
          "children=[Ch:q]"
        )
      );
      rows.add(
        new ViaContainer(
          "a Set property, " + kind,
          containerSource(generic, "java.util.Set<%1$sCh>", "java.util.Set.of(new %1$sCh(\"a\"))"),
          sampled(
            "%1$sTgt",
            "java.util.Set<%1$sChDto> children",
            "new %1$sTgt(java.util.Set.of(new %1$sChDto(\"Q\")))"
          ),
          "children=[ChDto:A]",
          "children=[Ch:q]"
        )
      );
      rows.add(
        new ViaContainer(
          "an Optional property, " + kind,
          containerSource(generic, "java.util.Optional<%1$sCh>", "java.util.Optional.of(new %1$sCh(\"a\"))"),
          sampled(
            "%1$sTgt",
            "java.util.Optional<%1$sChDto> children",
            "new %1$sTgt(java.util.Optional.of(new %1$sChDto(\"Q\")))"
          ),
          "children=Optional[ChDto:A]",
          "children=Optional[Ch:q]"
        )
      );
      rows.add(
        new ViaContainer(
          "a Map property's values, " + kind,
          containerSource(generic, "java.util.Map<String, %1$sCh>", "java.util.Map.of(\"k\", new %1$sCh(\"a\"))"),
          sampled(
            "%1$sTgt",
            "java.util.Map<String, %1$sChDto> children",
            "new %1$sTgt(java.util.Map.of(\"k\", new %1$sChDto(\"Q\")))"
          ),
          "children={k=ChDto:A}",
          "children={k=Ch:q}"
        )
      );
    }
    rows.add(
      new ViaContainer(
        "a List property both sides type by a variable",
        containerSource(true, "java.util.List<%1$sCh>", "java.util.List.of(new %1$sCh(\"a\"))"),
        sampledGeneric(
          "%1$sTgt<Y>",
          "java.util.List<Y> children",
          "new %1$sTgt<>(java.util.List.of(new %1$sChDto(\"Q\")))"
        ),
        "children=[ChDto:A]",
        "children=[Ch:q]"
      )
    );
    // A mapper class that also converts the whole list keeps converting the whole property on the
    // generated path, as it did before element mappers were lifted. A runtime via row's Mapper has
    // one pair of classes and no overloads, so it can only express the element methods, which it
    // lifts: the two paths are owed different results, and each holds its own.
    for (final var generic : List.of(false, true)) {
      rows.add(
        new ViaContainer(
          "a List property whose mapper class also converts the whole list, " +
            (generic ? "a source generic over its element" : "a source fixed to the child"),
          WHOLE_LIST_OVERLOADS,
          containerSource(generic, "java.util.List<%1$sCh>", "java.util.List.of(new %1$sCh(\"a\"))"),
          sampled(
            "%1$sTgt",
            "java.util.List<%1$sChDto> children",
            "new %1$sTgt(java.util.List.of(new %1$sChDto(\"Q\")))"
          ),
          "children=[ChDto:A]",
          "children=[Ch:q]",
          "children=[ChDto:Wa]",
          "children=[Ch:WBQ]"
        )
      );
    }
    // A whole-list overload whose result is the method's own variable gives what the call site
    // infers, so it counts as converting the whole property where the bridge reads the property
    // as declared. On a source generic over its element the bridge holds the property raw, and a
    // call handed a raw argument erases its result, so nothing is inferred: the element methods
    // are lifted, as the runtime lifts its mapper.
    rows.add(
      new ViaContainer(
        "a List property whose mapper class also converts the whole list into an inferred result, a source generic over its element",
        WHOLE_LIST_INFERRED,
        containerSource(true, "java.util.List<%1$sCh>", "java.util.List.of(new %1$sCh(\"a\"))"),
        sampled(
          "%1$sTgt",
          "java.util.List<%1$sChDto> children",
          "new %1$sTgt(java.util.List.of(new %1$sChDto(\"Q\")))"
        ),
        "children=[ChDto:A]",
        "children=[Ch:q]",
        "children=[ChDto:A]",
        "children=[Ch:q]"
      )
    );
    rows.add(
      new ViaContainer(
        "a List property whose mapper class also converts the whole list into an inferred result",
        WHOLE_LIST_INFERRED,
        containerSource(false, "java.util.List<%1$sCh>", "java.util.List.of(new %1$sCh(\"a\"))"),
        sampled(
          "%1$sTgt",
          "java.util.List<%1$sChDto> children",
          "new %1$sTgt(java.util.List.of(new %1$sChDto(\"Q\")))"
        ),
        "children=[ChDto:A]",
        "children=[Ch:q]",
        "children=[ChDto:GWa]",
        "children=[Ch:GWBQ]"
      )
    );
    rows.add(
      new ViaContainer(
        "a List property whose mapper class converts the whole list one way declared and back inferred",
        WHOLE_LIST_HALF_INFERRED,
        containerSource(false, "java.util.List<%1$sCh>", "java.util.List.of(new %1$sCh(\"a\"))"),
        sampled(
          "%1$sTgt",
          "java.util.List<%1$sChDto> children",
          "new %1$sTgt(java.util.List.of(new %1$sChDto(\"Q\")))"
        ),
        "children=[ChDto:A]",
        "children=[Ch:q]",
        "children=[ChDto:Wa]",
        "children=[Ch:GWBQ]"
      )
    );
    // A null element is not handed to the mapper on either path: it stays null.
    rows.add(
      new ViaContainer(
        "a List property holding a null element",
        containerSource(false, "java.util.List<%1$sCh>", "java.util.Arrays.asList(new %1$sCh(\"a\"), null)"),
        sampled(
          "%1$sTgt",
          "java.util.List<%1$sChDto> children",
          "new %1$sTgt(java.util.Arrays.asList(null, new %1$sChDto(\"Q\")))"
        ),
        "children=[ChDto:A, null]",
        "children=[null, Ch:q]"
      )
    );
    return rows;
  }

  @Test
  @DisplayName("a @ViaMapper on a List, Set, Optional or Map property converts each element the same way on both paths")
  void aViaMapperOnAContainerPropertyConvertsEachElementTheSameWayOnBothPaths() throws ReflectiveOperationException {
    final var failures = new ArrayList<String>();
    var index = 0;
    for (final var row : viaContainers()) {
      final var prefix = "Vc" + index++;
      final var head = "package " + PACKAGE + ";\n";
      final var pair = row.mapper() == null ? CHILD_PAIR : List.of(CHILD_PAIR.get(0), CHILD_PAIR.get(1), row.mapper());
      final var all = concat(pair, row.source(), row.target(), viaRow("children"))
        .stream()
        .map(declaration -> declaration.formatted(prefix))
        .map(code -> source(declaredName(code), head + code))
        .toList();
      final var plain = ProcessorHarness.compileFully(List.of(), List.of(), all.toArray(JavaFileObject[]::new));
      assertTrue(
        plain.success(),
        () -> row.name() + ": the pair and its runtime row should compile: " + plain.errorMessages()
      );
      final ProcessorHarness.Compilation processed;
      try {
        processed = bridged(all.subList(0, all.size() - 1).toArray(JavaFileObject[]::new));
      } catch (final AssertionError warned) {
        failures.add(row.name() + ": " + warned.getMessage());
        continue;
      }
      if (!processed.success()) {
        failures.add(row.name() + ": the generated bridge should compile: " + processed.errorMessages());
        continue;
      }
      final var classes = plain.define(MethodHandles.lookup());
      final Class<Object> src = cast(classes.get(PACKAGE + "." + prefix + "Src"));
      final Class<Object> tgt = cast(classes.get(PACKAGE + "." + prefix + "Tgt"));
      final var nested = nestedBridgesFirst(processed, plain, prefix);
      final Attempt runtime = () -> classes.get(PACKAGE + "." + prefix + "Runtime").getMethod("mapper").invoke(null);
      final var bridge = emitted(processed, plain, prefix, nested);
      final Attempt srcSample = () -> src.getMethod("sample").invoke(null);
      final Attempt tgtSample = () -> tgt.getMethod("sample").invoke(null);
      for (final var operation : List.of("forward", "backward", "patch")) {
        final Attempt generated = switch (operation) {
          case "forward" -> () -> bridge.getMethod("forward", src).invoke(null, srcSample.get());
          case "backward" -> () -> bridge.getMethod("backward", tgt).invoke(null, tgtSample.get());
          default -> () -> bridge.getMethod("patch", src, tgt).invoke(null, srcSample.get(), tgtSample.get());
        };
        @SuppressWarnings("unchecked")
        final Attempt reflective = switch (operation) {
          case "forward" -> () -> ((Mapper<Object, Object>) runtime.get()).forward(srcSample.get());
          case "backward" -> () -> ((Mapper<Object, Object>) runtime.get()).backward(tgtSample.get());
          default -> () -> ((Mapper<Object, Object>) runtime.get()).patch(srcSample.get(), tgtSample.get());
        };
        final var forward = "forward".equals(operation);
        final var sides = List.of(
          List.of("generated", forward ? row.generatedForward() : row.generatedBackward()),
          List.of("reflective", forward ? row.forward() : row.backward())
        );
        for (final var side : sides) {
          final var held = childrenHeld(prefix, "generated".equals(side.get(0)) ? generated : reflective);
          if (!held.equals(side.get(1))) {
            failures.add(
              row.name() + ", " + operation + ": " + side.get(0) + " gave " + held + ", owed " + side.get(1)
            );
          }
        }
      }
    }
    assertTrue(failures.isEmpty(), () -> String.join("\n  ", failures));
  }

  /**
   * What {@code attempt}'s result holds as {@code children}, each element written as its class
   * without the row's prefix and its name: a list or set as {@code [..]}, an optional as {@code
   * Optional[..]}, and a map as {@code {key=..}}; or the refusal.
   */
  private static String childrenHeld(final String prefix, final Attempt attempt) {
    try {
      final var out = attempt.get();
      final var children = out.getClass().getMethod("children").invoke(out);
      return "children=" + described(prefix, children);
    } catch (final InvocationTargetException e) {
      return REFUSED + (e.getCause() == null ? e : e.getCause()).getClass().getSimpleName();
    } catch (final ReflectiveOperationException | RuntimeException e) {
      return REFUSED + e.getClass().getSimpleName();
    }
  }

  private static String described(final String prefix, final Object value) throws ReflectiveOperationException {
    if (value == null) return "null";
    if (value instanceof Optional<?> optional) {
      return optional.isEmpty() ? "Optional.empty" : "Optional[" + described(prefix, optional.get()) + "]";
    }
    if (value instanceof Collection<?> collection) {
      final var parts = new ArrayList<String>();
      for (final var element : collection) parts.add(described(prefix, element));
      return "[" + String.join(", ", parts) + "]";
    }
    if (value instanceof Map<?, ?> map) {
      final var parts = new ArrayList<String>();
      for (final var entry : map.entrySet()) parts.add(entry.getKey() + "=" + described(prefix, entry.getValue()));
      return "{" + String.join(", ", parts) + "}";
    }
    return (
      value.getClass().getSimpleName().replace(prefix, "") + ":" + value.getClass().getMethod("name").invoke(value)
    );
  }

  /**
   * A {@code @ViaMapper} and the runtime {@code via} row over the same pair, whose mapper is handed
   * a property no value of which it can take: the declarations other than the child pair, each with
   * {@code %1$s} as the row's prefix and the last of them the runtime row, and the refusal both
   * paths owe when the bridge and the mapper are built.
   */
  private record ViaNeverFits(String name, List<String> declarations, String refusal) {}

  /** The refusal of a via row over {@code children} whose source holds no child. */
  private static String childrenNeverChild() {
    return PairingMessages.viaMapperNeverFits("%1$sSrc", "%1$sTgt", "source", "children", "%1$sCh");
  }

  private static final List<ViaNeverFits> VIA_NEVER_FITS = List.of(
    new ViaNeverFits(
      "a List of a variable bounded by a class the mapper does not take",
      List.of(
        "public record %1$sOther(String name) {}\n",
        VIA_CHILDREN + "public record %1$sSrc<X extends %1$sOther>(java.util.List<X> children) {}\n",
        "public record %1$sTgt(java.util.List<%1$sChDto> children) {}\n",
        viaRow("children")
      ),
      childrenNeverChild()
    ),
    new ViaNeverFits(
      "a Map whose keys are a variable",
      List.of(
        VIA_CHILDREN + "public record %1$sSrc<X>(java.util.Map<X, String> children) {}\n",
        "public record %1$sTgt(java.util.Map<%1$sChDto, String> children) {}\n",
        viaRow("children")
      ),
      childrenNeverChild()
    ),
    new ViaNeverFits(
      "a Map whose keys are the child",
      List.of(
        VIA_CHILDREN + "public record %1$sSrc(java.util.Map<%1$sCh, String> children) {}\n",
        "public record %1$sTgt(java.util.Map<%1$sChDto, String> children) {}\n",
        viaRow("children")
      ),
      childrenNeverChild()
    ),
    new ViaNeverFits(
      "an array of a variable",
      List.of(
        VIA_CHILDREN + "public record %1$sSrc<X>(X[] children) {}\n",
        "public record %1$sTgt(%1$sChDto[] children) {}\n",
        viaRow("children")
      ),
      childrenNeverChild()
    ),
    new ViaNeverFits(
      "an array of the child",
      List.of(
        VIA_CHILDREN + "public record %1$sSrc(%1$sCh[] children) {}\n",
        "public record %1$sTgt(%1$sChDto[] children) {}\n",
        viaRow("children")
      ),
      childrenNeverChild()
    ),
    new ViaNeverFits(
      "a List of Lists of a variable",
      List.of(
        VIA_CHILDREN + "public record %1$sSrc<X>(java.util.List<java.util.List<X>> children) {}\n",
        "public record %1$sTgt(java.util.List<java.util.List<%1$sChDto>> children) {}\n",
        viaRow("children")
      ),
      childrenNeverChild()
    ),
    new ViaNeverFits(
      "a List of Lists of the child",
      List.of(
        VIA_CHILDREN + "public record %1$sSrc(java.util.List<java.util.List<%1$sCh>> children) {}\n",
        "public record %1$sTgt(java.util.List<java.util.List<%1$sChDto>> children) {}\n",
        viaRow("children")
      ),
      childrenNeverChild()
    ),
    new ViaNeverFits(
      "a property typed by a variable bounded by the child, to a mapper of another class",
      List.of(
        "public record %1$sOther(String name) {}\n",
        "public class %1$sChMap {\n" +
          "  public static %1$sChDto forward(final %1$sOther c) { return new %1$sChDto(c.name()); }\n" +
          "  public static %1$sOther backward(final %1$sChDto d) { return new %1$sOther(d.name()); }\n}\n",
        VIA_CHILD + "public record %1$sSrc<X extends %1$sCh>(X child) {}\n",
        "public record %1$sTgt(%1$sChDto child) {}\n",
        "public class %1$sRuntime {\n" +
          "  @SuppressWarnings({\"rawtypes\", \"unchecked\"})\n" +
          "  public static Object mapper() {\n" +
          "    final var child = io.github.eschizoid.telescope.Telescope.mapper(%1$sOther.class, %1$sChDto.class);\n" +
          "    return io.github.eschizoid.telescope.Telescope.mapper(%1$sSrc.class, %1$sTgt.class,\n" +
          "      io.github.eschizoid.telescope.mapping.Mapping.<%1$sSrc, %1$sTgt>via(%1$sSrc::child, %1$sTgt::child," +
          " child));\n  }\n}\n"
      ),
      PairingMessages.viaMapperNeverFits("%1$sSrc", "%1$sTgt", "source", "child", "%1$sOther")
    ),
    new ViaNeverFits(
      "a child source into a List target",
      List.of(
        VIA_CHILDREN + "public record %1$sSrc(%1$sCh children) {}\n",
        "public record %1$sTgt(java.util.List<%1$sChDto> children) {}\n",
        viaRow("children")
      ),
      PairingMessages.viaMapperNeverFits("%1$sSrc", "%1$sTgt", "target", "children", "%1$sChDto")
    ),
    new ViaNeverFits(
      "an interface the child, a record, does not implement",
      List.of(
        "public interface %1$sNamed { String name(); }\n",
        VIA_CHILDREN + "public record %1$sSrc(%1$sNamed children) {}\n",
        "public record %1$sTgt(%1$sChDto children) {}\n",
        viaRow("children")
      ),
      childrenNeverChild()
    ),
    new ViaNeverFits(
      "a sealed interface none of whose cases is the mapper's class, which is not final",
      List.of(
        "public sealed interface %1$sShape permits %1$sCircle {}\n",
        "public record %1$sCircle(String name) implements %1$sShape {}\n",
        "public class %1$sPlain {\n" +
          "  private String name;\n" +
          "  public %1$sPlain() {}\n" +
          "  public String getName() { return name; }\n" +
          "  public void setName(final String name) { this.name = name; }\n}\n",
        "public class %1$sChMap {\n" +
          "  public static %1$sChDto forward(final %1$sPlain p) { return new %1$sChDto(p.getName()); }\n" +
          "  public static %1$sPlain backward(final %1$sChDto d) { final var p = new %1$sPlain(); p.setName(d.name()); return p; }\n}\n",
        VIA_CHILDREN + "public record %1$sSrc(%1$sShape children) {}\n",
        "public record %1$sTgt(%1$sChDto children) {}\n",
        "public class %1$sRuntime {\n" +
          "  @SuppressWarnings({\"rawtypes\", \"unchecked\"})\n" +
          "  public static Object mapper() {\n" +
          "    final var child = io.github.eschizoid.telescope.Telescope.mapper(%1$sPlain.class, %1$sChDto.class);\n" +
          "    return io.github.eschizoid.telescope.Telescope.mapper(%1$sSrc.class, %1$sTgt.class,\n" +
          "      io.github.eschizoid.telescope.mapping.Mapping.<%1$sSrc, %1$sTgt>via(%1$sSrc::children, %1$sTgt::children," +
          " child));\n  }\n}\n"
      ),
      PairingMessages.viaMapperNeverFits("%1$sSrc", "%1$sTgt", "source", "children", "%1$sPlain")
    )
  );

  @Test
  @DisplayName("a via row whose mapper no value of the property fits is refused by name on both paths")
  void aViaRowWhoseMapperNoValueFitsIsRefusedOnBothPaths() throws ReflectiveOperationException {
    final var failures = new ArrayList<String>();
    var index = 0;
    for (final var row : VIA_NEVER_FITS) {
      final var prefix = "Vn" + index++;
      final var head = "package " + PACKAGE + ";\n";
      final var ownMapper = row
        .declarations()
        .stream()
        .anyMatch(d -> d.contains("class %1$sChMap "));
      final var all = concat(
        ownMapper ? CHILD_PAIR.subList(0, 2) : CHILD_PAIR,
        row.declarations().toArray(String[]::new)
      )
        .stream()
        .map(declaration -> declaration.formatted(prefix))
        .map(code -> source(declaredName(code), head + code))
        .toList();
      final var refusal = row.refusal().formatted(prefix);
      final var plain = ProcessorHarness.compileFully(List.of(), List.of(), all.toArray(JavaFileObject[]::new));
      assertTrue(
        plain.success(),
        () -> row.name() + ": the pair and its runtime row should compile: " + plain.errorMessages()
      );
      final var processed = bridged(all.subList(0, all.size() - 1).toArray(JavaFileObject[]::new));
      if (processed.success() || !processed.hasError(refusal)) {
        failures.add(row.name() + ": the processor owed the refusal, saw " + describe(processed));
      }
      final var classes = plain.define(MethodHandles.lookup());
      String built;
      try {
        classes.get(PACKAGE + "." + prefix + "Runtime").getMethod("mapper").invoke(null);
        built = "a mapper";
      } catch (final InvocationTargetException e) {
        built = String.valueOf(e.getCause());
      }
      if (!("java.lang.IllegalArgumentException: " + refusal).equals(built)) {
        failures.add(row.name() + ": the runtime owed the refusal, saw " + built);
      }
    }
    assertTrue(failures.isEmpty(), () -> String.join("\n  ", failures));
  }

  /**
   * {@code %1$sRuntime}, whose {@code mapper()} is the two-way runtime mapper the pair has no rows
   * for.
   */
  private static final String PLAIN_RUNTIME =
    "public class %1$sRuntime {\n" +
    "  public static Object mapper() {\n" +
    "    return io.github.eschizoid.telescope.Telescope.mapper(%1$sSrc.class, %1$sTgt.class);\n" +
    "  }\n}\n";

  /**
   * A generic bean {@code %1$s<name><Y>} extending {@code %1$sBase<fixed>}, which declares {@code
   * set<Cap>(T)}, recording {@code T:} and the value, and an empty {@code Marker} interface. The
   * bean reads the property as {@code fixed} and adds its own {@code set<Cap>(other)}, which writes
   * {@code otherWrites} instead. Its {@code sample()} holds {@code held}.
   */
  private static List<String> inheritedSetter(
    final String name,
    final String property,
    final String fixed,
    final String other,
    final String otherWrites,
    final String held,
    final String header
  ) {
    final var cap = Character.toUpperCase(property.charAt(0)) + property.substring(1);
    return List.of(
      "public class %1$sBase<T> {\n" +
        "  public interface Marker {}\n" +
        "  protected Object " +
        property +
        ";\n" +
        "  public void set" +
        cap +
        "(final T v) { this." +
        property +
        " = v instanceof String text ? \"T:\" + text : v; }\n}\n",
      header +
        "public class %1$s" +
        name +
        "<Y> extends %1$sBase<" +
        fixed +
        "> {\n" +
        "  public " +
        fixed +
        " get" +
        cap +
        "() { return (" +
        fixed +
        ") " +
        property +
        "; }\n" +
        "  public void set" +
        cap +
        "(final " +
        other +
        " v) { this." +
        property +
        " = " +
        otherWrites +
        "; }\n" +
        "  public static %1$s" +
        name +
        "<Object> sample() { final var b = new %1$s" +
        name +
        "<Object>(); b." +
        property +
        " = " +
        held +
        "; return b; }\n}\n"
    );
  }

  /**
   * A write into a generic class the bridge names raw: the declarations, each with {@code %1$s} as
   * the row's prefix and the last of them the runtime mapper, and what both paths owe forward,
   * backward and through patch. A row owing no backward is forward only.
   */
  private record RawWrite(String name, List<String> declarations, String forward, String backward, String patch) {}

  private static final List<RawWrite> RAW_WRITES = List.of(
    // The inherited setValue(T) takes Object on the raw class, where a String argument would
    // bind
    // setValue(CharSequence); the runtime writes through setValue(T), chosen from the String
    // the
    // property is read as.
    new RawWrite(
      "a plain write into a generic target whose setter is inherited",
      concat(
        List.of(BRIDGE_TO_TGT + valueBean("%1$sSrc", "String", "\"x\"")),
        concat(
          inheritedSetter("Tgt", "value", "String", "CharSequence", "\"CharSequence:\" + v", "\"q\"", ""),
          PLAIN_RUNTIME
        ).toArray(String[]::new)
      ),
      "#0(value=\"T:x\")",
      "#0(value=\"q\")",
      "#0(value=\"q\")"
    ),
    new RawWrite(
      "a plain write backward and through patch into a generic source whose setter is inherited",
      concat(
        inheritedSetter("Src", "value", "String", "CharSequence", "\"CharSequence:\" + v", "\"x\"", BRIDGE_TO_TGT),
        valueBean("%1$sTgt", "String", "\"q\""),
        PLAIN_RUNTIME
      ),
      "#0(value=\"x\")",
      "#0(value=\"T:q\")",
      "#0(value=\"T:q\")"
    ),
    // A nested pair's own bridge converts the child; its result is written through the setter
    // the
    // child's type chooses, setChild(T), rather than setChild(Marker), which writes nothing.
    new RawWrite(
      "a nested bridge's result into a generic target whose setter is inherited",
      concat(
        List.of(
          "@io.github.eschizoid.telescope.annotations.Bridge(%1$sChDto.class)\npublic record %1$sCh(String name) {}\n",
          "public record %1$sChDto(String name) implements %1$sBase.Marker {}\n",
          BRIDGE_TO_TGT +
            "public class %1$sSrc {\n" +
            "  private %1$sCh child;\n" +
            "  public %1$sCh getChild() { return child; }\n" +
            "  public void setChild(final %1$sCh child) { this.child = child; }\n" +
            "  public static %1$sSrc sample() { final var s = new %1$sSrc(); s.child = new %1$sCh(\"a\"); return s; }\n}\n"
        ),
        concat(
          inheritedSetter("Tgt", "child", "%1$sChDto", "%1$sBase.Marker", "null", "new %1$sChDto(\"q\")", ""),
          PLAIN_RUNTIME
        ).toArray(String[]::new)
      ),
      "#0(child=#1(name=\"a\"))",
      "#0(child=#1(name=\"q\"))",
      "#0(child=#1(name=\"q\"))"
    ),
    new RawWrite(
      "a @ViaMapper result into a generic target whose setter is inherited",
      concat(
        List.of(
          "public record %1$sCh(String name) {}\n",
          "public record %1$sChDto(String name) implements %1$sBase.Marker {}\n",
          "public class %1$sChMap {\n" +
            "  public static %1$sChDto forward(final %1$sCh c) { return new %1$sChDto(c.name()); }\n" +
            "  public static %1$sCh backward(final %1$sChDto d) { return new %1$sCh(d.name()); }\n}\n",
          VIA_CHILD +
            "public class %1$sSrc {\n" +
            "  private %1$sCh child;\n" +
            "  public %1$sCh getChild() { return child; }\n" +
            "  public void setChild(final %1$sCh child) { this.child = child; }\n" +
            "  public static %1$sSrc sample() { final var s = new %1$sSrc(); s.child = new %1$sCh(\"a\"); return s; }\n}\n"
        ),
        concat(
          inheritedSetter("Tgt", "child", "%1$sChDto", "%1$sBase.Marker", "null", "new %1$sChDto(\"q\")", ""),
          "public class %1$sRuntime {\n" +
            "  @SuppressWarnings({\"rawtypes\", \"unchecked\"})\n" +
            "  public static Object mapper() {\n" +
            "    final var child = io.github.eschizoid.telescope.Telescope.mapper(%1$sCh.class, %1$sChDto.class);\n" +
            "    return io.github.eschizoid.telescope.Telescope.mapper(%1$sSrc.class, %1$sTgt.class,\n" +
            "      io.github.eschizoid.telescope.mapping.Mapping.<%1$sSrc, %1$sTgt>via(%1$sSrc::getChild, %1$sTgt::getChild," +
            " child));\n  }\n}\n"
        ).toArray(String[]::new)
      ),
      "#0(child=#1(name=\"a\"))",
      "#0(child=#1(name=\"q\"))",
      "#0(child=#1(name=\"q\"))"
    ),
    // The raw record's canonical constructor takes Object, where a CharSequence argument
    // would bind
    // the other constructor of one argument; the runtime calls the canonical one.
    new RawWrite(
      "a transform result into a generic record with a second constructor of the same arity",
      List.of(
        transformedValue(true) + sampled("%1$sSrc", "String value", "new %1$sSrc(\"x\")"),
        "@SuppressWarnings(\"unchecked\")\npublic record %1$sTgt<Y>(Y value) {\n" +
          "  public %1$sTgt(final CharSequence c) { this((Y) (\"CharSequence:\" + c)); }\n" +
          "  public static %1$sTgt<Object> sample() { return new %1$sTgt<Object>((Object) \"q\"); }\n}\n",
        transformFn("String", "CharSequence", "a", "String.valueOf(b)"),
        "public class %1$sRuntime {\n" +
          "  public static Object mapper() {\n" +
          "    final var fn = new %1$sFn();\n" +
          "    return io.github.eschizoid.telescope.Telescope.mapperForward(%1$sSrc.class, %1$sTgt.class,\n" +
          "      io.github.eschizoid.telescope.mapping.Mapping.<%1$sSrc, %1$sTgt, String, Object>toOneWay(" +
          "%1$sSrc::value, %1$sTgt::value, fn::forward));\n  }\n}\n"
      ),
      "#0(value=\"x\")",
      null,
      null
    )
  );

  @Test
  @DisplayName("a write into a generic class the bridge names raw goes through the setter the property's type chooses")
  void aWriteIntoARawGenericClassBindsTheSetterThePropertysTypeChooses() throws ReflectiveOperationException {
    final var failures = new ArrayList<String>();
    var index = 0;
    for (final var row : RAW_WRITES) {
      final var prefix = "Pw" + index++;
      final var head = "package " + PACKAGE + ";\n";
      final var all = row
        .declarations()
        .stream()
        .map(declaration -> declaration.formatted(prefix))
        .map(code -> source(declaredName(code), head + code))
        .toList();
      final var plain = ProcessorHarness.compileFully(List.of(), List.of(), all.toArray(JavaFileObject[]::new));
      assertTrue(plain.success(), () -> row.name() + ": the pair should compile: " + plain.errorMessages());
      final ProcessorHarness.Compilation processed;
      try {
        processed = bridged(all.subList(0, all.size() - 1).toArray(JavaFileObject[]::new));
      } catch (final AssertionError warned) {
        failures.add(row.name() + ": " + warned.getMessage());
        continue;
      }
      if (!processed.success()) {
        failures.add(row.name() + ": the generated bridge should compile: " + processed.errorMessages());
        continue;
      }
      final var classes = plain.define(MethodHandles.lookup());
      final Class<Object> src = cast(classes.get(PACKAGE + "." + prefix + "Src"));
      final Class<Object> tgt = cast(classes.get(PACKAGE + "." + prefix + "Tgt"));
      final var nested = nestedBridgesFirst(processed, plain, prefix);
      final var runtime = classes.get(PACKAGE + "." + prefix + "Runtime").getMethod("mapper").invoke(null);
      final var forwardOnly = row.backward() == null;
      @SuppressWarnings("unchecked")
      final Mapper<Object, Object> mapper = forwardOnly ? null : (Mapper<Object, Object>) runtime;
      @SuppressWarnings("unchecked")
      final ForwardMapper<Object, Object> forwardMapper = forwardOnly ? (ForwardMapper<Object, Object>) runtime : null;
      final var bridge = emitted(processed, plain, prefix, nested);
      final Attempt srcSample = () -> src.getMethod("sample").invoke(null);
      final Attempt tgtSample = () -> tgt.getMethod("sample").invoke(null);
      final var owed = new ArrayList<Map.Entry<String, String>>();
      owed.add(Map.entry("forward", row.forward()));
      if (row.backward() != null) owed.add(Map.entry("backward", row.backward()));
      if (row.patch() != null) owed.add(Map.entry("patch", row.patch()));
      for (final var operation : owed) {
        final Attempt generated = switch (operation.getKey()) {
          case "forward" -> () -> bridge.getMethod("forward", src).invoke(null, srcSample.get());
          case "backward" -> () -> bridge.getMethod("backward", tgt).invoke(null, tgtSample.get());
          default -> () -> bridge.getMethod("patch", src, tgt).invoke(null, srcSample.get(), tgtSample.get());
        };
        final Attempt reflective = switch (operation.getKey()) {
          case "forward" -> forwardOnly
            ? () -> forwardMapper.forward(srcSample.get())
            : () -> mapper.forward(srcSample.get());
          case "backward" -> () -> mapper.backward(tgtSample.get());
          default -> () -> mapper.patch(srcSample.get(), tgtSample.get());
        };
        for (final var side : List.of(Map.entry("generated", generated), Map.entry("reflective", reflective))) {
          final var shape = shapeOf(side.getValue());
          if (!shape.equals(operation.getValue())) {
            failures.add(
              row.name() +
                ", " +
                operation.getKey() +
                ": " +
                side.getKey() +
                " gave " +
                shape +
                ", owed " +
                operation.getValue()
            );
          }
        }
      }
    }
    assertTrue(failures.isEmpty(), () -> String.join("\n  ", failures));
  }

  /** {@code %1$sLog}, a log every conversion of an order row appends to as it runs. */
  private static final String ORDER_LOG =
    "public class %1$sLog { public static final StringBuilder LOG = new StringBuilder(); }\n";

  /**
   * A child pair whose own bridge converts its one component through a transform that logs {@code
   * c;} each way.
   */
  private static final List<String> LOGGED_CHILD = List.of(
    "@io.github.eschizoid.telescope.annotations.Bridge(value = %1$sCDto.class, transforms =" +
      " @io.github.eschizoid.telescope.annotations.Transform(field = \"n\", using = %1$sCFn.class))\n" +
      "public record %1$sC(String n) {}\n",
    "public record %1$sCDto(String n) {}\n",
    "public class %1$sCFn implements io.github.eschizoid.telescope.conversion.BridgeFn<String, String> {\n" +
      "  public String forward(final String a) { %1$sLog.LOG.append(\"c;\"); return a; }\n" +
      "  public String backward(final String b) { %1$sLog.LOG.append(\"c;\"); return b; }\n}\n"
  );

  /**
   * {@code %1$sVFn}, a transform from {@code String} to {@code to} that logs {@code v;} each way.
   */
  private static String loggedValueFn(final String to) {
    return (
      "public class %1$sVFn implements io.github.eschizoid.telescope.conversion.BridgeFn<String, " +
      to +
      "> {\n" +
      "  public " +
      to +
      " forward(final String a) { %1$sLog.LOG.append(\"v;\"); return a; }\n" +
      "  public String backward(final " +
      to +
      " b) { %1$sLog.LOG.append(\"v;\"); return String.valueOf(b); }\n}\n"
    );
  }

  /**
   * A pair whose conversions log the order they run in: the declarations, each with {@code %1$s} as
   * the row's prefix and the last of them the runtime row, and what both paths owe forward and
   * backward, each as the log followed by the result. A row owing no backward is forward only. No
   * row owes a patch: the runtime's patch converts a row's property after the properties it walks,
   * where its forward and backward, and the generated patch, convert in component order. Every row
   * builds a record: the runtime walks a bean's properties in the order reflection lists its
   * methods, which is not fixed, so no order can be owed for a bean.
   */
  private record OrderRow(String name, List<String> declarations, String forward, String backward, String patch) {}

  /** {@code @Bridge} to {@code %1$sTgt} with a transform on {@code v} through {@code %1$sVFn}. */
  private static String loggedTransform(final boolean forwardOnly) {
    return (
      "@io.github.eschizoid.telescope.annotations.Bridge(value = %1$sTgt.class, transforms =" +
      " @io.github.eschizoid.telescope.annotations.Transform(field = \"v\", using = %1$sVFn.class" +
      (forwardOnly ? ", forwardOnly = true" : "") +
      "))\n"
    );
  }

  /**
   * {@code %1$sRuntime} over a typed-transform row on {@code v}, reading it through {@code
   * accessor}.
   */
  private static String loggedRuntime(final boolean forwardOnly, final String accessor, final String to) {
    final var row = forwardOnly
      ? "toOneWay(%1$sSrc::" + accessor + ", %1$sTgt::" + accessor + ", fn::forward)"
      : "to(%1$sSrc::" + accessor + ", %1$sTgt::" + accessor + ", fn::forward, b -> fn.backward((" + to + ") b))";
    return (
      "public class %1$sRuntime {\n" +
      "  public static Object mapper() {\n" +
      "    final var fn = new %1$sVFn();\n" +
      "    return io.github.eschizoid.telescope.Telescope." +
      (forwardOnly ? "mapperForward" : "mapper") +
      "(%1$sSrc.class, %1$sTgt.class,\n" +
      "      io.github.eschizoid.telescope.mapping.Mapping.<%1$sSrc, %1$sTgt, String, Object>" +
      row +
      ");\n  }\n}\n"
    );
  }

  private static final List<OrderRow> ORDER_ROWS = List.of(
    // The transform's result is typed by Tgt's variable, which a local in the static method
    // can
    // name only as its erasure; the child's conversion is a nested bridge's.
    new OrderRow(
      "a transform on a type-variable component ahead of a nested pair, in a generic record",
      concat(
        LOGGED_CHILD,
        ORDER_LOG,
        loggedValueFn("Object"),
        loggedTransform(false) + sampled("%1$sSrc", "String v, %1$sC c", "new %1$sSrc(\"x\", new %1$sC(\"a\"))"),
        "public record %1$sTgt<Y>(Y v, %1$sCDto c) {\n" +
          "  public static %1$sTgt<Object> sample() { return new %1$sTgt<>(\"q\", new %1$sCDto(\"b\")); }\n}\n",
        loggedRuntime(false, "v", "Object")
      ),
      "v;c; #0(v=\"x\", c=#1(n=\"a\"))",
      "v;c; #0(v=\"q\", c=#1(n=\"b\"))",
      null
    ),
    // A second constructor of the same arity takes a CharSequence; the transform's result is
    // one,
    // and the canonical constructor still has to be the one called.
    new OrderRow(
      "a transform on a type-variable component ahead of a nested pair, in a generic record with a second constructor",
      concat(
        LOGGED_CHILD,
        ORDER_LOG,
        loggedValueFn("CharSequence"),
        loggedTransform(true) + sampled("%1$sSrc", "String v, %1$sC c", "new %1$sSrc(\"x\", new %1$sC(\"a\"))"),
        "@SuppressWarnings(\"unchecked\")\npublic record %1$sTgt<Y>(Y v, %1$sCDto c) {\n" +
          "  public %1$sTgt(final CharSequence v, final %1$sCDto c) { this((Y) (\"CharSequence:\" + v), c); }\n" +
          "  public static %1$sTgt<Object> sample() { return new %1$sTgt<Object>((Object) \"q\", new %1$sCDto(\"b\")); }\n}\n",
        loggedRuntime(true, "v", "CharSequence")
      ),
      "v;c; #0(v=\"x\", c=#1(n=\"a\"))",
      null,
      null
    )
  );

  @Test
  @DisplayName("a rebuild runs its conversions in the same order on both paths")
  void aRebuildRunsItsConversionsInTheSameOrderOnBothPaths() throws ReflectiveOperationException {
    final var failures = new ArrayList<String>();
    var index = 0;
    for (final var row : ORDER_ROWS) {
      final var prefix = "Ao" + index++;
      final var head = "package " + PACKAGE + ";\n";
      final var all = row
        .declarations()
        .stream()
        .map(declaration -> declaration.formatted(prefix))
        .map(code -> source(declaredName(code), head + code))
        .toList();
      final var plain = ProcessorHarness.compileFully(List.of(), List.of(), all.toArray(JavaFileObject[]::new));
      assertTrue(plain.success(), () -> row.name() + ": the pair should compile: " + plain.errorMessages());
      final ProcessorHarness.Compilation processed;
      try {
        processed = bridged(all.subList(0, all.size() - 1).toArray(JavaFileObject[]::new));
      } catch (final AssertionError warned) {
        failures.add(row.name() + ": " + warned.getMessage());
        continue;
      }
      if (!processed.success()) {
        failures.add(row.name() + ": the generated bridge should compile: " + processed.errorMessages());
        continue;
      }
      // A transform on a type-variable component is evaluated into a local of the variable's
      // erasure, ahead of the constructor call, as an argument of a nameable type is.
      final var text = processed.generated().get(PACKAGE + "." + prefix + "SrcBridge");
      final var local = text.indexOf("final java.lang.Object __cv_v = ");
      final var call = text.indexOf("new " + PACKAGE + "." + prefix + "Tgt(");
      if (row.name().contains("type-variable") && (local < 0 || local > call)) {
        failures.add(row.name() + ": forward owed the transform's result in a local of its erasure before the call");
      }
      final var classes = plain.define(MethodHandles.lookup());
      final Class<Object> src = cast(classes.get(PACKAGE + "." + prefix + "Src"));
      final Class<Object> tgt = cast(classes.get(PACKAGE + "." + prefix + "Tgt"));
      final var log = (StringBuilder) classes.get(PACKAGE + "." + prefix + "Log").getField("LOG").get(null);
      final var nested = nestedBridgesFirst(processed, plain, prefix);
      final var runtime = classes.get(PACKAGE + "." + prefix + "Runtime").getMethod("mapper").invoke(null);
      final var forwardOnly = row.backward() == null;
      @SuppressWarnings("unchecked")
      final Mapper<Object, Object> mapper = forwardOnly ? null : (Mapper<Object, Object>) runtime;
      @SuppressWarnings("unchecked")
      final ForwardMapper<Object, Object> forwardMapper = forwardOnly ? (ForwardMapper<Object, Object>) runtime : null;
      final var bridge = emitted(processed, plain, prefix, nested);
      final Attempt srcSample = () -> src.getMethod("sample").invoke(null);
      final Attempt tgtSample = () -> tgt.getMethod("sample").invoke(null);
      final var owed = new ArrayList<Map.Entry<String, String>>();
      owed.add(Map.entry("forward", row.forward()));
      if (row.backward() != null) owed.add(Map.entry("backward", row.backward()));
      if (row.patch() != null) owed.add(Map.entry("patch", row.patch()));
      for (final var operation : owed) {
        final Attempt generated = switch (operation.getKey()) {
          case "forward" -> () -> bridge.getMethod("forward", src).invoke(null, srcSample.get());
          case "backward" -> () -> bridge.getMethod("backward", tgt).invoke(null, tgtSample.get());
          default -> () -> bridge.getMethod("patch", src, tgt).invoke(null, srcSample.get(), tgtSample.get());
        };
        final Attempt reflective = switch (operation.getKey()) {
          case "forward" -> forwardOnly
            ? () -> forwardMapper.forward(srcSample.get())
            : () -> mapper.forward(srcSample.get());
          case "backward" -> () -> mapper.backward(tgtSample.get());
          default -> () -> mapper.patch(srcSample.get(), tgtSample.get());
        };
        for (final var side : List.of(Map.entry("generated", generated), Map.entry("reflective", reflective))) {
          log.setLength(0);
          final var shape = shapeOf(side.getValue());
          final var seen = log + " " + shape;
          if (!seen.equals(operation.getValue())) {
            failures.add(
              row.name() +
                ", " +
                operation.getKey() +
                ": " +
                side.getKey() +
                " gave " +
                seen +
                ", owed " +
                operation.getValue()
            );
          }
        }
      }
    }
    assertTrue(failures.isEmpty(), () -> String.join("\n  ", failures));
  }

  /** Whether a compilation succeeded, or the errors it reported. */
  private static String describe(final ProcessorHarness.Compilation compilation) {
    return compilation.success() ? "a successful compilation" : "errors " + compilation.errorMessages();
  }

  /** The message of what {@code attempt} threw, or null when it threw nothing. */
  private static String refusalOf(final Attempt attempt) {
    try {
      attempt.get();
      return null;
    } catch (final ReflectiveOperationException | RuntimeException e) {
      return String.valueOf(e.getMessage());
    }
  }
}
