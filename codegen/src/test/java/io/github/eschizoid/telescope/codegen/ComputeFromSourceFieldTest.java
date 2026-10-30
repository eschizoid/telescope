package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.conversion.Mapper;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import javax.annotation.processing.Processor;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@code compute(target, function)} row in a {@code @Bridge} field: the processor writes a direct
 * call of the function on the source into the generated bridge, the field's mapper runs it, and the
 * same rows built without the processor give the same result. The references the generated bridge
 * cannot call, or that the runtime path refuses, are refused by name on the row.
 */
class ComputeFromSourceFieldTest {

  private static final String HEADER =
    "package cf;\n" +
    "import static io.github.eschizoid.telescope.mapping.Mapping.*;\n" +
    "import io.github.eschizoid.telescope.Telescope;\n" +
    "import io.github.eschizoid.telescope.annotations.Bridge;\n" +
    "import io.github.eschizoid.telescope.conversion.*;\n" +
    "import io.github.eschizoid.telescope.mapping.*;\n";

  private static final String ROWS =
    "      compute(Card::full, Names::full),\n" +
    "      compute(Card::shout, Named::shout),\n" +
    "      compute(Card::display, Person::display),\n" +
    "      compute(Card::letters, Person::letters),\n" +
    "      compute(Card::doubled, Names::doubled),\n" +
    "      compute(Card::missing, Names::none),\n" +
    "      compute(Card::tag, Names.INSTANCE::tag),\n" +
    "      compute(Card::held, Names.HELD)";

  private static final List<JavaFileObject> MODEL = List.of(
    ProcessorHarness.source(
      "cf.Named",
      "package cf; public interface Named { String first(); default String shout() { return first().toUpperCase(); } }"
    ),
    ProcessorHarness.source(
      "cf.Person",
      "package cf; public record Person(String first, String last, int age) implements Named {\n" +
        "  public String display() { return first + \" \" + last; }\n" +
        "  public int letters() { return first.length() + last.length(); }\n" +
        "}\n"
    ),
    ProcessorHarness.source(
      "cf.Card",
      "package cf; public record Card(String first, String last, int age, String full, String shout," +
        " String display, int letters, Integer doubled, String missing, String tag, String held) {}"
    ),
    ProcessorHarness.source("cf.Plain", "package cf; public record Plain(String first) {}"),
    ProcessorHarness.source(
      "cf.Names",
      "package cf;\n" +
        "public final class Names {\n" +
        "  public static final java.util.concurrent.atomic.AtomicInteger CALLS =" +
        " new java.util.concurrent.atomic.AtomicInteger();\n" +
        "  public static final Names INSTANCE = new Names();\n" +
        "  public static final java.util.function.Function<Person, String> HELD = p -> \"held \" + p.first();\n" +
        "  public static java.util.function.Function<Person, String> loose = p -> p.first();\n" +
        "  public static String full(final Person p) { CALLS.incrementAndGet(); return p.first() + \" \" + p.last(); }\n" +
        "  public static int doubled(final Person p) { return p.age() * 2; }\n" +
        "  public static String none(final Person p) { return null; }\n" +
        "  public static String foreign(final Plain p) { return p.first(); }\n" +
        "  public String tag(final Person p) { return \"#\" + p.age(); }\n" +
        "  public static String label() { return \"plain\"; }\n" +
        "  public static String label(final Person p) { return \"from \" + p.first(); }\n" +
        "}\n"
    ),
    ProcessorHarness.source(
      "cf.Cards",
      HEADER +
        "public final class Cards {\n" +
        "  @Bridge public static final Mapper<Person, Card> CARD = Telescope.mapper(Person.class, Card.class,\n" +
        ROWS +
        ");\n" +
        "}\n"
    ),
    ProcessorHarness.source(
      "cf.CardsRuntime",
      HEADER +
        "public final class CardsRuntime {\n" +
        "  public static Mapper<Person, Card> runtime() {\n" +
        "    return Telescope.mapper(Person.class, Card.class,\n" +
        ROWS +
        ");\n" +
        "  }\n" +
        "}\n"
    )
  );

  private static JavaFileObject[] sources(final String... extra) {
    final var all = new ArrayList<>(MODEL);
    for (var i = 0; i < extra.length; i += 2) all.add(ProcessorHarness.source(extra[i], extra[i + 1]));
    return all.toArray(JavaFileObject[]::new);
  }

  private static ProcessorHarness.Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compileFully(
      List.<Processor>of(new BridgeProcessor(), new MapperVerifierProcessor()),
      List.of(),
      sources
    );
  }

  /** A loader over the compiled classes and generated resources, as a class path holds them. */
  private static URLClassLoader load(final ProcessorHarness.Compilation c) throws IOException {
    final var dir = Files.createTempDirectory("compute-from-source");
    for (final var e : c.classes().entrySet()) {
      write(dir.resolve(e.getKey().replace('.', '/') + ".class"), e.getValue());
    }
    for (final var e : c.resources().entrySet()) write(dir.resolve(e.getKey()), e.getValue().getBytes());
    return new URLClassLoader(new URL[] { dir.toUri().toURL() }, ComputeFromSourceFieldTest.class.getClassLoader());
  }

  private static void write(final Path file, final byte[] bytes) throws IOException {
    Files.createDirectories(file.getParent());
    Files.write(file, bytes);
  }

  private static Object person(final ClassLoader loader, final String first, final String last, final int age)
    throws ReflectiveOperationException {
    return loader.loadClass("cf.Person").getConstructors()[0].newInstance(first, last, age);
  }

  @SuppressWarnings("unchecked")
  private static Mapper<Object, Object> declared(final ClassLoader loader) throws ReflectiveOperationException {
    return (Mapper<Object, Object>) loader.loadClass("cf.Cards").getField("CARD").get(null);
  }

  @SuppressWarnings("unchecked")
  private static Mapper<Object, Object> runtime(final ClassLoader loader) throws ReflectiveOperationException {
    return (Mapper<Object, Object>) loader.loadClass("cf.CardsRuntime").getMethod("runtime").invoke(null);
  }

  @Test
  @DisplayName("the generated bridge calls each function directly on the source, with no class written for it")
  void theBridgeCallsTheFunctionDirectly() {
    final var compilation = compile(sources());
    assertTrue(compilation.success(), compilation::errorMessages);
    final var bridge = compilation.generated().get("cf.PersonToCardBridge");
    for (final var call : List.of(
      "cf.Names.full(s)",
      "s.shout()",
      "s.display()",
      "s.letters()",
      "cf.Names.doubled(s)",
      "cf.Names.none(s)",
      "cf.Names.INSTANCE.tag(s)",
      "cf.Names.HELD.apply(s)"
    )) {
      assertEquals(1, bridge.split(Pattern.quote(call), -1).length - 1, () -> call + " in\n" + bridge);
    }
    assertFalse(bridge.contains("__cp_"), bridge);
    assertFalse(
      compilation
        .generated()
        .keySet()
        .stream()
        .anyMatch(name -> name.endsWith("Compute"))
    );
  }

  @Test
  @DisplayName("forward, backward and patch give the same result on the generated and the runtime path")
  void bothPathsAgree() throws Exception {
    final var compilation = compile(sources());
    assertTrue(compilation.success(), compilation::errorMessages);
    try (final var loader = load(compilation)) {
      final var declared = declared(loader);
      final var runtime = runtime(loader);
      assertTrue(declared.explain().toString().contains("Runs: generated"), () -> declared.explain().toString());
      assertFalse(runtime.explain().toString().contains("Runs: generated"), () -> runtime.explain().toString());
      final var ada = person(loader, "ada", "lovelace", 36);
      final var owed =
        "Card[first=ada, last=lovelace, age=36, full=ada lovelace, shout=ADA, display=ada lovelace, letters=11," +
        " doubled=72, missing=null, tag=#36, held=held ada]";
      assertEquals(owed, declared.forward(ada).toString());
      assertEquals(owed, runtime.forward(ada).toString());
      final var card = loader
        .loadClass("cf.Card")
        .getConstructors()[0].newInstance("tim", "lee", 9, "x", "y", "z", 1, 2, "w", "t", "h");
      for (final var mapper : List.of(declared, runtime)) {
        assertEquals("Person[first=tim, last=lee, age=9]", mapper.backward(card).toString());
        assertEquals("Person[first=tim, last=lee, age=9]", mapper.patch(ada, card).toString());
      }
    }
  }

  @Test
  @DisplayName("a null source is handed back as null on both paths, and the function is never called")
  void aNullSourceNeverReachesTheFunction() throws Exception {
    final var compilation = compile(sources());
    assertTrue(compilation.success(), compilation::errorMessages);
    try (final var loader = load(compilation)) {
      final var calls = (AtomicInteger) loader.loadClass("cf.Names").getField("CALLS").get(null);
      final var declared = declared(loader);
      final var runtime = runtime(loader);
      calls.set(0);
      assertNull(declared.forward(null));
      assertNull(runtime.forward(null));
      assertNull(
        loader
          .loadClass("cf.PersonToCardBridge")
          .getMethod("forward", loader.loadClass("cf.Person"))
          .invoke(null, (Object) null)
      );
      assertEquals(0, calls.get());
    }
  }

  /**
   * {@code cf.Odd}, declaring a {@code @Bridge} field of Person to Card with {@code row}, and
   * {@code cf.OddRuntime}, whose {@code runtime()} builds the same rows on the runtime path.
   */
  private static String[] withRow(final String row) {
    return new String[] {
      "cf.Odd",
      HEADER +
      "public final class Odd {\n" +
      "  @Bridge public static final ForwardMapper<Person, Card> ODD =" +
      " Telescope.mapperForward(Person.class, Card.class, " +
      row +
      ");\n" +
      "}\n",
      "cf.OddRuntime",
      HEADER +
      "public final class OddRuntime {\n" +
      "  public static Object runtime() { return Telescope.mapperForward(Person.class, Card.class, " +
      row +
      "); }\n" +
      "}\n",
    };
  }

  /** The message the runtime path refuses {@code row} with when the mapper is built, or null. */
  private static String runtimeRefusal(final String row) throws Exception {
    final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources(withRow(row)));
    assertTrue(plain.success(), plain::errorMessages);
    try (final var loader = load(plain)) {
      loader.loadClass("cf.OddRuntime").getMethod("runtime").invoke(null);
      return null;
    } catch (final InvocationTargetException e) {
      return e.getCause().getMessage();
    }
  }

  @Test
  @DisplayName("a reference whose name has a form taking the source too is refused in the words the runtime uses")
  void aReferenceWithASourceFormIsRefusedOnBothPaths() throws Exception {
    final var row = "compute(Card::full, Names::label)";
    final var words =
      "Deep map Person → Card: compute(...) fills 'full' from Names::label, which Java binds as a supplier taking" +
      " no argument, though Names.label(Person) takes the source Person. Hold the one meant in a static final" +
      " Supplier or Function field, or give the form taking the source a name of its own.";
    final var generated = compile(sources(withRow(row)));
    assertTrue(generated.hasError(words), generated::errorMessages);
    assertEquals(words, runtimeRefusal(row));
  }

  @Test
  @DisplayName("a function taking another class than the source is refused by name on the row")
  void aFunctionOffTheSourceIsRefused() {
    final var words =
      "Deep map Person → Card: compute(...) fills 'full' from Names::foreign, which takes Plain, and the source" +
      " Person is not one. Name a method taking Person, or a method of Person.";
    final var generated = compile(sources(withRow("compute(Card::full, Names::foreign)")));
    assertTrue(generated.hasError(words), generated::errorMessages);
  }

  @Test
  @DisplayName("an inline lambda is refused by name, asking for a method reference")
  void anInlineLambdaIsRefused() {
    final var generated = compile(sources(withRow("compute(Card::full, (Person p) -> p.first())")));
    assertTrue(
      generated.hasError("write the compute row's function as a method reference the bridge calls directly"),
      generated::errorMessages
    );
  }

  @Test
  @DisplayName("a function held in a field that is not static and final is refused by name")
  void aLooseHeldFunctionIsRefused() {
    final var generated = compile(sources(withRow("compute(Card::full, Names.loose)")));
    assertTrue(generated.hasError("loose is not static and final"), generated::errorMessages);
  }

  @Test
  @DisplayName("a function whose result the property does not hold is refused by name")
  void aResultThePropertyDoesNotHoldIsRefused() {
    final var generated = compile(sources(withRow("compute(Card::full, Names::doubled)")));
    assertTrue(
      generated.hasError("Names::doubled gives int, which the java.lang.String property 'full'"),
      generated::errorMessages
    );
  }
}
