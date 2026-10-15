package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A sorted map rebuilt over keys it cannot order, refused the same way on both paths: by name when
 * the first key that cannot be ordered is inserted, or while the mapper is built where no
 * comparator could ever reach the map.
 */
class SortedMapKeyParityTest {

  private static final String PACKAGE = "io.github.eschizoid.telescope.codegen";

  private static final String HEAD = "package " + PACKAGE + ";\n";

  private static final List<String> KEYS = List.of("b", "a");

  /**
   * One shape. {@code declarations} are whole top-level declarations, among them the key class
   * {@code K}, each with {@code %s} for the cell's prefix; {@code srcField} and {@code tgtField}
   * are the two field types. Values are strings unless {@code converted}, when they are records
   * {@code A} converted to records {@code B}. {@code owed} is what both paths give for a source
   * holding two keys, rendered by {@link #outcome}, with {@code %s} for the cell's qualified
   * prefix.
   */
  private record Row(
    String name,
    List<String> declarations,
    String srcField,
    String tgtField,
    boolean converted,
    String owed
  ) {}

  private static final String NOT_COMPARABLE = "public record %sK(String v) {}";

  /** The refusal a sorted map gives a key it cannot order, with the cast as its cause. */
  private static String unorderable(final String container, final String implementing) {
    return (
      "IllegalStateException: Deep map: " +
      container +
      " keeps its keys in order, and %sK could not be ordered there, " +
      implementing +
      ". Supply an ordering these keys accept through a Mapping.via(...) row, or declare the" +
      " target as a map that keeps no order. The cause is the cast itself. <- ClassCastException"
    );
  }

  private static final String REFUSED_AT_BUILD = "refused while the mapper is built";

  private static final List<Row> ROWS = List.of(
    new Row(
      "keys copied unchanged into a TreeMap",
      List.of(NOT_COMPARABLE),
      "java.util.Map<%sK, String>",
      "java.util.TreeMap<%sK, String>",
      false,
      unorderable("java.util.TreeMap", "and its type does not implement Comparable")
    ),
    new Row(
      "values converted into a SortedMap",
      List.of(NOT_COMPARABLE, "public record %sA(String v) {}", "public record %sB(String v) {}"),
      "java.util.Map<%sK, %sA>",
      "java.util.SortedMap<%sK, %sB>",
      true,
      unorderable("java.util.SortedMap", "and its type does not implement Comparable")
    ),
    new Row(
      "a key ordered against another type",
      List.of(
        "public record %sK(String v) implements Comparable<String> {\n" +
          "  public int compareTo(final String o) { return v.compareTo(o); }\n}"
      ),
      "java.util.Map<%sK, String>",
      "java.util.TreeMap<%sK, String>",
      false,
      unorderable("java.util.TreeMap", "though its type implements Comparable")
    ),
    // A key ordered against its own kind has said it can be ordered,
    // so a cast escaping its compareTo is its own and propagates as it is.
    new Row(
      "a key whose own compareTo casts",
      List.of(
        "public record %sK(String v) implements Comparable<%sK> {\n" +
          "  public int compareTo(final %sK o) { throw new ClassCastException(\"its own\"); }\n}"
      ),
      "java.util.Map<%sK, String>",
      "java.util.TreeMap<%sK, String>",
      false,
      "ClassCastException: its own"
    ),
    new Row(
      "a sorted subtype that cannot be handed a comparator",
      List.of(
        NOT_COMPARABLE,
        "public class %sPlain<K, V> extends java.util.TreeMap<K, V> {\n" +
          "  private static final long serialVersionUID = 1L;\n  public %sPlain() {}\n}"
      ),
      "java.util.Map<%sK, String>",
      "%sPlain<%sK, String>",
      false,
      REFUSED_AT_BUILD
    ),
    new Row(
      "a sorted subtype fixing its own key type, which cannot be handed a comparator",
      List.of(
        NOT_COMPARABLE,
        "public class %sFixed extends java.util.TreeMap<%sK, String> {\n" +
          "  private static final long serialVersionUID = 1L;\n  public %sFixed() {}\n}"
      ),
      "java.util.Map<%sK, String>",
      "%sFixed",
      false,
      REFUSED_AT_BUILD
    ),
    new Row(
      "a sorted subtype that can be handed a comparator",
      List.of(
        NOT_COMPARABLE,
        "public class %sTold<K, V> extends java.util.TreeMap<K, V> {\n" +
          "  private static final long serialVersionUID = 1L;\n  public %sTold() {}\n" +
          "  public %sTold(final java.util.Comparator<? super K> c) { super(c); }\n}"
      ),
      "java.util.Map<%sK, String>",
      "%sTold<%sK, String>",
      false,
      unorderable("%sTold", "and its type does not implement Comparable")
    )
  );

  @Test
  @DisplayName("a sorted map over keys it cannot order is refused the same way on both paths")
  void anUnorderableKeyIsRefusedTheSameWayOnBothPaths() throws ReflectiveOperationException {
    final var failures = new ArrayList<String>();
    var index = 0;
    for (final var row : ROWS) {
      final var prefix = "Smk" + index++;
      final var owed = row.owed().replace("%s", PACKAGE + "." + prefix);
      final var cell = compile(prefix, row.declarations(), row.srcField(), row.tgtField());
      if (row.owed().equals(REFUSED_AT_BUILD)) {
        final var generated = cell.processed().success()
          ? "bridged"
          : cell.processed().errorMessages().contains("a sorted map whose key " + PACKAGE + "." + prefix + "K")
            ? REFUSED_AT_BUILD
            : cell.processed().errorMessages();
        final var reflective = outcome(() -> Telescope.mapper(cast(cell.src()), cast(cell.tgt())));
        final var reflectiveRefused = reflective.contains("a sorted map whose key " + PACKAGE + "." + prefix + "K")
          ? REFUSED_AT_BUILD
          : reflective;
        if (!owed.equals(generated)) failures.add(row.name() + ": generated gave " + generated);
        if (!owed.equals(reflectiveRefused)) failures.add(row.name() + ": reflective gave " + reflectiveRefused);
        continue;
      }
      if (!cell.processed().success()) {
        failures.add(row.name() + ": should bridge: " + cell.processed().errorMessages());
        continue;
      }
      final var key = cell.classes().get(PACKAGE + "." + prefix + "K").getConstructor(String.class);
      final var entries = new LinkedHashMap<Object, Object>();
      for (final var name : KEYS) {
        final Object value = row.converted()
          ? cell.classes().get(PACKAGE + "." + prefix + "A").getConstructor(String.class).newInstance(name)
          : name;
        entries.put(key.newInstance(name), value);
      }
      final var source = cell.src().getConstructors()[0].newInstance(entries);
      final var generated = outcome(() -> cell.forward().invoke(null, source));
      final var reflective = outcome(() -> Telescope.mapper(cast(cell.src()), cast(cell.tgt())).forward(source));
      if (!owed.equals(generated)) failures.add(row.name() + ": generated gave " + generated);
      if (!owed.equals(reflective)) failures.add(row.name() + ": reflective gave " + reflective);
    }
    assertTrue(failures.isEmpty(), () -> String.join("\n  ", failures));
  }

  /**
   * A map whose entries differ on each pass over them, the last pass repeating. Its size is that of
   * the first.
   */
  private static final class ShiftingMap extends AbstractMap<Object, Object> {

    private final List<Map<Object, Object>> passes;
    private int pass;

    ShiftingMap(final List<Map<Object, Object>> passes) {
      this.passes = passes;
    }

    @Override
    public Set<Map.Entry<Object, Object>> entrySet() {
      final var shifting = this;
      return new AbstractSet<>() {
        @Override
        public Iterator<Map.Entry<Object, Object>> iterator() {
          return shifting.passes.get(Math.min(shifting.pass++, shifting.passes.size() - 1)).entrySet().iterator();
        }

        @Override
        public int size() {
          return shifting.passes.getFirst().size();
        }
      };
    }
  }

  @Test
  @DisplayName("a sorted map rebuilt from a source that does not yield the same keys twice refuses on both paths")
  void aSortedMapFromAShiftingSourceRefusesOnBothPaths() throws ReflectiveOperationException {
    final var cell = compile(
      "Smks",
      List.of(NOT_COMPARABLE),
      "java.util.Map<%sK, String>",
      "java.util.TreeMap<%sK, String>"
    );
    assertTrue(cell.processed().success(), () -> "should bridge: " + cell.processed().errorMessages());
    final var key = cell.classes().get(PACKAGE + ".SmksK").getConstructor(String.class);
    final var refusal = "IllegalStateException: Deep map: java.util.TreeMap keeps its keys in order, and ";
    final var cause = " <- ClassCastException";
    final var failures = new ArrayList<String>();
    for (final var path : List.of("generated", "reflective")) {
      final Map<Object, Object> first = new LinkedHashMap<>();
      first.put(key.newInstance("a"), "1");
      final var source = cell.src().getConstructors()[0].newInstance(new ShiftingMap(List.of(first, Map.of())));
      final var thrown = path.equals("generated")
        ? outcome(() -> cell.forward().invoke(null, source))
        : outcome(() -> Telescope.mapper(cast(cell.src()), cast(cell.tgt())).forward(source));
      if (!thrown.startsWith(refusal) || !thrown.contains(cause)) failures.add(path + " gave " + thrown);
    }
    assertTrue(failures.isEmpty(), () -> String.join("\n  ", failures));
  }

  /**
   * How many refusals the reflective path is driven through before the test gives up on the JIT
   * replacing the cast with a preallocated one. HotSpot does so once the code holding the cast has
   * been compiled and has thrown there often enough, which takes some thousands of throws.
   */
  private static final int WARMING_LIMIT = 200_000;

  /**
   * The key's cast sits in the bridge method its own {@code compareTo} gets, so it lives in a class
   * this test defines and is cold when the test starts, whatever ran before it. A refusal taken
   * before the cast is hot carries the cast's message; one taken after carries a cast with none.
   */
  @Test
  @DisplayName("a refusal reads the same on both paths before and after the JIT drops the cast's message")
  void aRefusalReadsTheSameOnBothPathsBeforeAndAfterTheCastIsHot() throws ReflectiveOperationException {
    final var cell = compile(
      "Smkh",
      List.of(
        "public record %sK(String v) implements Comparable<String> {\n" +
          "  public int compareTo(final String o) { return v.compareTo(o); }\n}"
      ),
      "java.util.Map<%sK, String>",
      "java.util.TreeMap<%sK, String>"
    );
    assertTrue(cell.processed().success(), () -> "should bridge: " + cell.processed().errorMessages());
    final var key = cell.classes().get(PACKAGE + ".SmkhK").getConstructor(String.class);
    final Map<Object, Object> entries = new LinkedHashMap<>();
    entries.put(key.newInstance("a"), "1");
    final var source = cell.src().getConstructors()[0].newInstance(entries);
    final var mapper = Telescope.mapper(cast(cell.src()), cast(cell.tgt()));
    final Attempt generatedPath = () -> cell.forward().invoke(null, source);
    final Attempt reflectivePath = () -> mapper.forward(source);

    final var cold = List.of(outcome(generatedPath), outcome(reflectivePath));
    final var first = castOf(mapper, source);
    var last = first;
    for (var i = 0; i < WARMING_LIMIT && last != null && last.getMessage() != null; i++) {
      last = castOf(mapper, source);
    }
    final var hottest = last;
    assertTrue(
      first instanceof ClassCastException && first.getMessage() != null,
      () -> "the first cast should carry its message: " + first
    );
    // A JVM that keeps every implicit exception's message never reaches the state this test is
    // about, and is skipped rather than failed.
    assumeTrue(
      hottest instanceof ClassCastException && hottest.getMessage() == null,
      () -> "the cast kept its message through " + WARMING_LIMIT + " refusals: " + hottest
    );
    final var hot = List.of(outcome(generatedPath), outcome(reflectivePath));

    final var owed = unorderable("java.util.TreeMap", "though its type implements Comparable").replace(
      "%s",
      PACKAGE + ".Smkh"
    );
    assertEquals(List.of(owed, owed, owed, owed), List.of(cold.get(0), cold.get(1), hot.get(0), hot.get(1)));
  }

  /**
   * The cause of the refusal {@code mapper} gives {@code source}, or null when it does not refuse.
   */
  private static Throwable castOf(final Mapper<Object, Object> mapper, final Object source) {
    try {
      mapper.forward(source);
      return null;
    } catch (final IllegalStateException e) {
      return e.getCause();
    }
  }

  /** The two compilations of a cell and what the tests need from them. */
  private record Cell(
    ProcessorHarness.Compilation processed,
    Map<String, Class<?>> classes,
    Class<?> src,
    Class<?> tgt,
    Method forward
  ) {}

  private static Cell compile(
    final String prefix,
    final List<String> declarations,
    final String srcField,
    final String tgtField
  ) throws ReflectiveOperationException {
    final var declared = Pattern.compile("(?:class|record) (\\w+)");
    final var sources = new ArrayList<JavaFileObject>();
    for (final var declaration : declarations) {
      final var code = declaration.replace("%s", prefix);
      final var named = declared.matcher(code);
      if (!named.find()) throw new IllegalStateException("no type declared in " + code);
      sources.add(ProcessorHarness.source(PACKAGE + "." + named.group(1), HEAD + code + "\n"));
    }
    sources.add(
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Src",
        HEAD +
          "@io.github.eschizoid.telescope.annotations.Bridge(" +
          prefix +
          "Tgt.class)\npublic record " +
          prefix +
          "Src(" +
          srcField.replace("%s", prefix) +
          " items) {}\n"
      )
    );
    sources.add(
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Tgt",
        HEAD + "public record " + prefix + "Tgt(" + tgtField.replace("%s", prefix) + " items) {}\n"
      )
    );
    final var compiled = sources.toArray(JavaFileObject[]::new);
    final var plain = ProcessorHarness.compileFully(List.of(), List.of(), compiled);
    assertTrue(plain.success(), plain::errorMessages);
    final var processed = ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), compiled);
    final var classes = plain.define(MethodHandles.lookup());
    final var src = classes.get(PACKAGE + "." + prefix + "Src");
    final var tgt = classes.get(PACKAGE + "." + prefix + "Tgt");
    if (!processed.success()) return new Cell(processed, classes, src, tgt, null);
    final var added = new LinkedHashMap<>(processed.classes());
    plain.classes().keySet().forEach(added::remove);
    final var bridge = new ProcessorHarness.Compilation(
      processed.success(),
      processed.diagnostics(),
      processed.generated(),
      processed.resources(),
      added
    )
      .define(MethodHandles.lookup())
      .get(PACKAGE + "." + prefix + "SrcBridge");
    return new Cell(processed, classes, src, tgt, bridge.getMethod("forward", src));
  }

  private interface Attempt {
    Object get() throws ReflectiveOperationException;
  }

  /**
   * What an attempt gives: "nothing thrown", or the thrown exception as its simple class name and
   * message followed by the class of each cause after {@code <-}. A cause's message is left out:
   * HotSpot replaces an implicit exception that compiled code throws often with a preallocated one
   * whose message is null, so the same cast reads differently from one call to the next, and the
   * class is what both paths are held to.
   */
  private static String outcome(final Attempt attempt) {
    try {
      attempt.get();
      return "nothing thrown";
    } catch (final InvocationTargetException e) {
      return rendered(e.getCause());
    } catch (final ReflectiveOperationException | RuntimeException e) {
      return rendered(e);
    }
  }

  private static String rendered(final Throwable thrown) {
    final var out = new StringBuilder(thrown.getClass().getSimpleName()).append(": ").append(thrown.getMessage());
    for (var cause = thrown.getCause(); cause != null; cause = cause.getCause()) {
      out.append(" <- ").append(cause.getClass().getSimpleName());
    }
    return out.toString();
  }

  @SuppressWarnings("unchecked")
  private static <T> Class<T> cast(final Class<?> type) {
    return (Class<T>) type;
  }
}
