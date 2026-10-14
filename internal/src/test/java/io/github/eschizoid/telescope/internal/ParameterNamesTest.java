package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.ParameterNames.Local;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Constructor parameter names read from a class compiled without {@code -parameters}. Each fixture
 * is compiled here with the options under test and loaded from the directory it was written to, so
 * its own bytes are there to read the way an application's are.
 *
 * <p>Inside a native image there are no class bytes, and the suite also runs with the image flag
 * set, so every assertion that names come from the local variable table says what the image answers
 * instead: nothing.
 */
class ParameterNamesTest {

  @TempDir
  Path dir;

  private static final String OUTER =
    "package dbg;\npublic class Outer {\n" +
    "  public class Inner {\n" +
    "    public Inner(final long a, final String b, final double c, final int d) {}\n  }\n" +
    "  public static class Nested {\n" +
    "    public Nested(final double x, final long y, final String z) {}\n  }\n}\n";

  private static final String POINT =
    "package dbg;\npublic class Point {\n  private final String label;\n  private final long x;\n" +
    "  public Point(final long x, final String label) { this.x = x; this.label = label; }\n" +
    "  public String getLabel() { return label; }\n  public long getX() { return x; }\n}\n";

  private static final String OTHER =
    "package dbg;\npublic class Other {\n  public Other(final long y, final String text) {}\n}\n";

  /**
   * A constructor declaring a local after its one parameter, so its table has an entry that starts
   * later.
   */
  private static final String PAIR =
    "package dbg;\npublic class Pair {\n  private final String a;\n" +
    "  public Pair(final String a) { final String extra = a.trim(); this.a = extra; }\n" +
    "  public String getA() { return a; }\n}\n";

  // The properties are first then last, the constructor takes last then first, so binding by name
  // and binding by position put the two values in opposite fields.
  private static final String PERSON =
    "package dbg;\npublic class Person {\n  private final String first;\n  private final String last;\n" +
    "  public Person(final String last, final String first) { this.first = first; this.last = last; }\n" +
    "  public String getFirst() { return first; }\n  public String getLast() { return last; }\n}\n";

  // Parameter names that are not properties, in the properties' order.
  private static final String INITIALS =
    "package dbg;\npublic class Initials {\n  private final String first;\n  private final String last;\n" +
    "  public Initials(final String f, final String l) { this.first = f; this.last = l; }\n" +
    "  public String getFirst() { return first; }\n  public String getLast() { return last; }\n}\n";

  /**
   * A constructor only some of whose parameter names are properties, the parameters that name none,
   * and its arity, which is its property count.
   */
  private record PartlyNamed(String name, String source, List<String> unmatched, int arity) {}

  private static final List<PartlyNamed> PARTLY_NAMED = List.of(
    // In an order that binding by position would hand first's value to the parameter named
    // last.
    new PartlyNamed(
      "Partial",
      "package dbg;\npublic class Partial {\n  private final String first;\n  private final String last;\n" +
        "  public Partial(final String last, final String nick) { this.last = last; this.first = nick; }\n" +
        "  public String getFirst() { return first; }\n  public String getLast() { return last; }\n}\n",
      List.of("nick"),
      2
    ),
    // Two of three parameters named after properties.
    new PartlyNamed(
      "Subset",
      "package dbg;\npublic class Subset {\n  private final String a;\n  private final String b;\n" +
        "  private final String c;\n" +
        "  public Subset(final String a, final String b, final String extra) {\n" +
        "    this.a = a; this.b = b; this.c = extra;\n  }\n" +
        "  public String getA() { return a; }\n  public String getB() { return b; }\n" +
        "  public String getC() { return c; }\n}\n",
      List.of("extra"),
      3
    ),
    // The parameter that names no property is a primitive, which by name would take its
    // default.
    new PartlyNamed(
      "PrimPartial",
      "package dbg;\npublic class PrimPartial {\n  private final String name;\n  private final int age;\n" +
        "  public PrimPartial(final String name, final int count) { this.name = name; this.age = count; }\n" +
        "  public String getName() { return name; }\n  public int getAge() { return age; }\n}\n",
      List.of("count"),
      2
    ),
    // A property typed as the class itself, beside a parameter that names no property.
    new PartlyNamed(
      "Link",
      "package dbg;\npublic class Link {\n  private final Link next;\n  private final String label;\n" +
        "  public Link(final Link next, final String text) { this.next = next; this.label = text; }\n" +
        "  public Link getNext() { return next; }\n  public String getLabel() { return label; }\n}\n",
      List.of("text"),
      2
    )
  );

  /**
   * Compiles {@code sources} with {@code options} and returns the directory the classes landed in.
   */
  private Path compile(final List<String> options, final Map<String, String> sources) throws IOException {
    final var root = Files.createTempDirectory(dir, "fixture");
    final var src = root.resolve("src");
    final var out = root.resolve("out");
    final var files = new ArrayList<Path>();
    for (final var entry : sources.entrySet()) {
      final var file = src.resolve(entry.getKey() + ".java");
      Files.createDirectories(file.getParent());
      Files.writeString(file, entry.getValue());
      files.add(file);
    }
    Files.createDirectories(out);
    final var arguments = new ArrayList<>(options);
    arguments.addAll(List.of("-d", out.toString()));
    final var compiler = ToolProvider.getSystemJavaCompiler();
    try (final var fileManager = compiler.getStandardFileManager(null, Locale.ROOT, null)) {
      final var ok = compiler
        .getTask(null, fileManager, null, arguments, null, fileManager.getJavaFileObjectsFromPaths(files))
        .call();
      assertTrue(ok, "the fixture should compile");
    }
    return out;
  }

  private Class<?> load(final String name, final List<String> options, final Map<String, String> sources)
    throws IOException, ClassNotFoundException {
    return Class.forName(name, false, new Serving(compile(options, sources), Map.of()));
  }

  /**
   * Loads classes from a directory and answers some {@code .class} resources with other bytes, as a
   * loader that serves another version of a class does.
   */
  private static final class Serving extends URLClassLoader {

    private final Map<String, byte[]> served;

    Serving(final Path classes, final Map<String, byte[]> served) throws IOException {
      super(new URL[] { classes.toUri().toURL() }, ParameterNamesTest.class.getClassLoader());
      this.served = Map.copyOf(served);
    }

    @Override
    public InputStream getResourceAsStream(final String name) {
      final var bytes = served.get(name);
      return bytes != null ? new ByteArrayInputStream(bytes) : super.getResourceAsStream(name);
    }
  }

  /** What a class compiled with {@code -g} answers: its names, or nothing inside an image. */
  private static String[] expected(final String... names) {
    return NativeImage.IN_IMAGE ? null : names;
  }

  // --- slot mapping ----------------------------------------------------------------------------

  @Test
  @DisplayName("a long or a double takes two slots, and the names still line up with the parameters")
  void wideParametersTakeTwoSlots() throws ReflectiveOperationException, IOException {
    final var nested = load("dbg.Outer$Nested", List.of("-g"), Map.of("dbg/Outer", OUTER));
    final var ctor = nested.getConstructor(double.class, long.class, String.class);

    assertArrayEquals(expected("x", "y", "z"), ParameterNames.of(ctor));
  }

  @Test
  @DisplayName("an inner class's enclosing instance is its constructor's first parameter")
  void theEnclosingInstanceComesFirst() throws ReflectiveOperationException, IOException {
    final var loader = new Serving(compile(List.of("-g"), Map.of("dbg/Outer", OUTER)), Map.of());
    final var outer = Class.forName("dbg.Outer", false, loader);
    final var inner = Class.forName("dbg.Outer$Inner", false, loader);
    final var ctor = inner.getConstructor(outer, long.class, String.class, double.class, int.class);

    assertArrayEquals(expected("this$0", "a", "b", "c", "d"), ParameterNames.of(ctor));
  }

  @Test
  @DisplayName("slots are read past the second half of every wide parameter")
  void slotsSkipTheSecondHalfOfAWideParameter() {
    final var types = new Class<?>[] { long.class, String.class, double.class, int.class };
    final var slots = Map.of(1, local("a", "J"), 3, local("b", "Ljava/lang/String;"), 4, local("c", "D"));

    assertArrayEquals(
      new String[] { "a", "b", "c", "d" },
      ParameterNames.fromSlots(types, plus(slots, 6, local("d", "I")))
    );
    assertNull(ParameterNames.fromSlots(types, slots), "an unnamed slot names nothing");
  }

  @Test
  @DisplayName("two parameters with one name name nothing")
  void aRepeatedNameNamesNothing() {
    final var types = new Class<?>[] { String.class, String.class };

    assertNull(
      ParameterNames.fromSlots(types, Map.of(1, local("a", "Ljava/lang/String;"), 2, local("a", "Ljava/lang/String;")))
    );
  }

  @Test
  @DisplayName("a name that is not a Java identifier names nothing")
  void aNameThatIsNoIdentifierNamesNothing() {
    final var types = new Class<?>[] { String.class };

    assertNull(ParameterNames.fromSlots(types, Map.of(1, local("Ljava/lang/String;", "Ljava/lang/String;"))));
    assertArrayEquals(
      new String[] { "$a_1" },
      ParameterNames.fromSlots(types, Map.of(1, local("$a_1", "Ljava/lang/String;")))
    );
  }

  @Test
  @DisplayName("a class compiled without a local variable table has no parameter names")
  void noLocalVariableTableNamesNothing() throws ReflectiveOperationException, IOException {
    final var nested = load("dbg.Outer$Nested", List.of("-g:none"), Map.of("dbg/Outer", OUTER));

    assertNull(ParameterNames.of(nested.getConstructor(double.class, long.class, String.class)));
  }

  // --- whose bytes, and which entries ----------------------------------------------------------

  @Test
  @DisplayName("bytes that name another class give no names, even when a constructor's descriptor matches")
  void anotherClassesBytesNameNothing() throws ReflectiveOperationException, IOException {
    final var out = compile(List.of("-g"), Map.of("dbg/Point", POINT, "dbg/Other", OTHER));
    final var other = Files.readAllBytes(out.resolve("dbg/Other.class"));
    final var point = Class.forName("dbg.Point", false, new Serving(out, Map.of("dbg/Point.class", other)));

    assertNull(ParameterNames.of(point.getConstructor(long.class, String.class)));
  }

  @Test
  @DisplayName("an entry whose type is not its parameter's gives no names")
  void anEntryOfAnotherTypeNamesNothing() throws ReflectiveOperationException, IOException {
    final var out = compile(List.of("-g"), Map.of("dbg/Point", POINT));
    final var bytes = Files.readAllBytes(out.resolve("dbg/Point.class"));
    // Entries: this, x (a long), label (a String). Give label the long's type.
    final var table = constructorTable(bytes, 3);
    copyShort(bytes, table + 10 + 6, table + 20 + 6);
    final var point = Class.forName("dbg.Point", false, new Serving(out, Map.of("dbg/Point.class", bytes)));

    assertNull(ParameterNames.of(point.getConstructor(long.class, String.class)));
  }

  @Test
  @DisplayName("two entries live from the first instruction in one slot give no names")
  void twoEntriesInOneSlotNameNothing() throws ReflectiveOperationException, IOException {
    final var out = compile(List.of("-g"), Map.of("dbg/Pair", PAIR));
    final var bytes = Files.readAllBytes(out.resolve("dbg/Pair.class"));
    // Entries: this, a, extra. Make extra start at the first instruction in a's slot.
    final var table = constructorTable(bytes, 3);
    setShort(bytes, table + 20, 0);
    setShort(bytes, table + 20 + 8, 1);
    final var pair = Class.forName("dbg.Pair", false, new Serving(out, Map.of("dbg/Pair.class", bytes)));

    assertNull(ParameterNames.of(pair.getConstructor(String.class)));
  }

  @Test
  @DisplayName("a local that reuses a parameter's slot later in the body does not displace the parameter")
  void aLaterEntryInAParameterSlotIsALocal() throws ReflectiveOperationException, IOException {
    final var out = compile(List.of("-g"), Map.of("dbg/Pair", PAIR));
    final var bytes = Files.readAllBytes(out.resolve("dbg/Pair.class"));
    // Entries: this, a, extra. Move extra into a's slot without changing where it starts.
    final var table = constructorTable(bytes, 3);
    setShort(bytes, table + 20 + 8, 1);
    final var pair = Class.forName("dbg.Pair", false, new Serving(out, Map.of("dbg/Pair.class", bytes)));

    assertArrayEquals(expected("a"), ParameterNames.of(pair.getConstructor(String.class)));
  }

  // --- the automatic writer --------------------------------------------------------------------

  @Test
  @DisplayName("the automatic writer builds through a constructor whose names only the debug info keeps")
  void theAutomaticWriterMatchesByDebugNames() throws ReflectiveOperationException, IOException {
    final var point = load("dbg.Point", List.of("-g"), Map.of("dbg/Point", POINT));

    if (NativeImage.IN_IMAGE) {
      final var refusal = assertThrows(IllegalStateException.class, () -> Beans.autoWriter(point));
      assertTrue(refusal.getMessage().contains("javac -parameters or -g"), refusal.getMessage());
      return;
    }
    // The properties are discovered as label then x, the constructor takes x then label, so only a
    // match by name puts each value where it belongs.
    final var built = Beans.autoWriter(point).construct(Beans.propertyNames(point), n -> n.equals("x") ? 7L : "seven");
    assertEquals("seven", point.getMethod("getLabel").invoke(built));
    assertEquals(7L, point.getMethod("getX").invoke(built));
  }

  @Test
  @DisplayName("the automatic writer refuses a constructor whose names nothing keeps, and says how to keep them")
  void theAutomaticWriterRefusesWithoutDebugNames() throws ReflectiveOperationException, IOException {
    final var point = load("dbg.Point", List.of("-g:none"), Map.of("dbg/Point", POINT));

    final var refusal = assertThrows(IllegalStateException.class, () -> Beans.autoWriter(point));
    assertTrue(refusal.getMessage().contains("javac -parameters or -g"), refusal.getMessage());
  }

  // --- the CONSTRUCTOR hint --------------------------------------------------------------------

  /**
   * Builds through the hint's writer from a source holding first = Ada and last = Lovelace, and
   * reads both back. The source has no value under any other name.
   */
  private static String hinted(final Class<?> type) throws ReflectiveOperationException {
    final var source = Map.of("first", "Ada", "last", "Lovelace");
    final var built = Beans.constructorWriter(type, 2).construct(Beans.propertyNames(type), source::get);
    return type.getMethod("getFirst").invoke(built) + " " + type.getMethod("getLast").invoke(built);
  }

  @Test
  @DisplayName("the CONSTRUCTOR hint binds by the names the debug info keeps")
  void theHintBindsByDebugNames() throws ReflectiveOperationException, IOException {
    final var person = load("dbg.Person", List.of("-g"), Map.of("dbg/Person", PERSON));

    // Inside an image the names are unknown, so the hint binds by position, as it does under
    // -g:none.
    assertEquals(NativeImage.IN_IMAGE ? "Lovelace Ada" : "Ada Lovelace", hinted(person));
  }

  @Test
  @DisplayName("the CONSTRUCTOR hint binds by the names -parameters keeps")
  void theHintBindsByReflectiveNames() throws ReflectiveOperationException, IOException {
    final var person = load("dbg.Person", List.of("-parameters", "-g:none"), Map.of("dbg/Person", PERSON));

    assertEquals("Ada Lovelace", hinted(person));
  }

  @Test
  @DisplayName("the CONSTRUCTOR hint binds by position when nothing keeps the names")
  void theHintBindsByPositionWithoutNames() throws ReflectiveOperationException, IOException {
    final var person = load("dbg.Person", List.of("-g:none"), Map.of("dbg/Person", PERSON));

    // Position follows the properties, first then last, into a constructor taking last then first.
    assertEquals("Lovelace Ada", hinted(person));
  }

  @Test
  @DisplayName("the CONSTRUCTOR hint binds by position when the parameter names are not properties")
  void theHintBindsByPositionWhenNamesAreNotProperties() throws ReflectiveOperationException, IOException {
    final var initials = load("dbg.Initials", List.of("-parameters"), Map.of("dbg/Initials", INITIALS));

    assertEquals("Ada Lovelace", hinted(initials));
  }

  /**
   * Builds a {@code CONSTRUCTOR} writer for every partly named fixture compiled with {@code
   * options}, and returns, per fixture, the refusal's message or {@code "built"}.
   */
  private Map<String, String> partlyNamedOutcomes(final List<String> options)
    throws IOException, ClassNotFoundException {
    final var sources = new HashMap<String, String>();
    for (final var fixture : PARTLY_NAMED) sources.put("dbg/" + fixture.name(), fixture.source());
    final var loader = new Serving(compile(options, sources), Map.of());
    final var out = new HashMap<String, String>();
    for (final var fixture : PARTLY_NAMED) {
      final var type = Class.forName("dbg." + fixture.name(), false, loader);
      try {
        Beans.constructorWriter(type, fixture.arity());
        out.put(fixture.name(), "built");
      } catch (final IllegalStateException e) {
        out.put(fixture.name(), e.getMessage());
      }
    }
    return out;
  }

  /**
   * Fails unless every fixture was refused with a message naming exactly its unmatched parameters.
   */
  private static void assertRefusedByName(final Map<String, String> outcomes) {
    for (final var fixture : PARTLY_NAMED) {
      final var message = outcomes.get(fixture.name());
      assertTrue(
        message.contains("the parameters " + fixture.unmatched() + " are named after no property"),
        () -> fixture.name() + ": " + message
      );
    }
  }

  @Test
  @DisplayName("the CONSTRUCTOR hint refuses a constructor only some of whose -parameters names are properties")
  void theHintRefusesAPartialMatchUnderParameters() throws IOException, ClassNotFoundException {
    assertRefusedByName(partlyNamedOutcomes(List.of("-parameters", "-g:none")));
  }

  @Test
  @DisplayName("the CONSTRUCTOR hint refuses a constructor only some of whose debug names are properties")
  void theHintRefusesAPartialMatchUnderDebugInfo() throws IOException, ClassNotFoundException {
    final var outcomes = partlyNamedOutcomes(List.of("-g"));
    if (NativeImage.IN_IMAGE) {
      // Inside an image the names are unknown, so the hint binds by position and builds.
      assertEquals(Map.of("Partial", "built", "Subset", "built", "PrimPartial", "built", "Link", "built"), outcomes);
      return;
    }
    assertRefusedByName(outcomes);
  }

  @Test
  @DisplayName("the CONSTRUCTOR hint builds a partly named constructor by position when nothing keeps the names")
  void theHintBuildsAPartialMatchWithoutNames() throws IOException, ClassNotFoundException {
    final var outcomes = partlyNamedOutcomes(List.of("-g:none"));

    assertEquals(Map.of("Partial", "built", "Subset", "built", "PrimPartial", "built", "Link", "built"), outcomes);
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static Local local(final String name, final String descriptor) {
    return new Local(name, descriptor);
  }

  private static Map<Integer, Local> plus(final Map<Integer, Local> slots, final int slot, final Local local) {
    final var out = new HashMap<>(slots);
    out.put(slot, local);
    return out;
  }

  /**
   * The offset of the first entry of the only local variable table with {@code entries} entries,
   * the first two live from the first instruction in slots 0 and 1. Each entry is ten bytes: start,
   * length, name, type and slot.
   */
  private static int constructorTable(final byte[] bytes, final int entries) {
    var found = -1;
    for (var o = 0; o + 6 + 10 * entries <= bytes.length; o++) {
      if (readInt(bytes, o) != 2 + 10 * entries || readShort(bytes, o + 4) != entries) continue;
      final var first = o + 6;
      final var second = first + 10;
      if (readShort(bytes, first) != 0 || readShort(bytes, first + 8) != 0) continue;
      if (readShort(bytes, second) != 0 || readShort(bytes, second + 8) != 1) continue;
      if (found >= 0) throw new IllegalStateException("more than one table matches");
      found = first;
    }
    if (found < 0) throw new IllegalStateException("no table with " + entries + " entries");
    return found;
  }

  private static int readShort(final byte[] bytes, final int at) {
    return ((bytes[at] & 0xFF) << 8) | (bytes[at + 1] & 0xFF);
  }

  private static int readInt(final byte[] bytes, final int at) {
    return (readShort(bytes, at) << 16) | readShort(bytes, at + 2);
  }

  private static void setShort(final byte[] bytes, final int at, final int value) {
    bytes[at] = (byte) (value >>> 8);
    bytes[at + 1] = (byte) value;
  }

  private static void copyShort(final byte[] bytes, final int from, final int to) {
    setShort(bytes, to, readShort(bytes, from));
  }
}
