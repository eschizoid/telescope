package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.conversion.ForwardMapper;
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
    "      compute(Card::held, Names.HELD),\n" +
    "      compute(Card::plain, Names.PLAIN::apply)";

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
        " String display, int letters, Integer doubled, String missing, String tag, String held, String plain) {}"
    ),
    ProcessorHarness.source("cf.Plain", "package cf; public record Plain(String first) {}"),
    ProcessorHarness.source(
      "cf.Bag",
      "package cf; public record Bag(String first, String last, int age, java.util.List<String> tags," +
        " java.util.concurrent.atomic.AtomicReference<String> slot, String label, boolean flag, Wrapped made, long count) {}"
    ),
    ProcessorHarness.source(
      "cf.Hidden",
      "package cf; public final class Hidden {\n" +
        "  public static String label() { return \"plain\"; }\n" +
        "  @SuppressWarnings(\"unused\") private static String label(final Person p) { return p.first(); }\n" +
        "}\n"
    ),
    ProcessorHarness.source(
      "cf.Several",
      "package cf; public final class Several {\n" +
        "  public static String label() { return \"plain\"; }\n" +
        "  public static String label(final Person p) { return \"person\"; }\n" +
        "  public static String label(final Named n) { return \"named\"; }\n" +
        "  public static String label(final Record r) { return \"record\"; }\n" +
        "}\n"
    ),
    ProcessorHarness.source(
      "cf.Wrapped",
      "package cf; public record Wrapped(String from) {\n" +
        "  public Wrapped() { this(\"nothing\"); }\n" +
        "  public Wrapped(final Person p) { this(p.first()); }\n" +
        "}\n"
    ),
    ProcessorHarness.source(
      "cf.Base",
      "package cf; public class Base { public static String label(final Person p) { return p.first(); } }"
    ),
    ProcessorHarness.source(
      "cf.Derived",
      "package cf; public final class Derived extends Base { public static String label() { return \"plain\"; } }"
    ),
    ProcessorHarness.source(
      "cf.Upper",
      "package cf; public class Upper {\n" +
        "  public static String zz() { return \"upper\"; }\n" +
        "  public String ii() { return \"upper\"; }\n" +
        "}\n"
    ),
    ProcessorHarness.source(
      "cf.Lower",
      "package cf; public final class Lower extends Upper {\n" +
        "  public static final Lower LOWER = new Lower();\n" +
        "  public static final Upper AS_UPPER = new Lower();\n" +
        "  public static String zz(final Person p) { return \"lower \" + p.first(); }\n" +
        "  public String ii(final Person p) { return \"lower \" + p.first(); }\n" +
        "}\n"
    ),
    ProcessorHarness.source(
      "cf.Names",
      "package cf;\n" +
        "public final class Names {\n" +
        "  public static final java.util.concurrent.atomic.AtomicInteger CALLS =" +
        " new java.util.concurrent.atomic.AtomicInteger();\n" +
        "  public static final Names INSTANCE = new Names();\n" +
        "  public static final io.github.eschizoid.telescope.mapping.Mapping.FromSource<Person, String> HELD =" +
        " p -> \"held \" + p.first();\n" +
        "  public static final java.util.function.Function<Person, String> PLAIN = p -> \"plain \" + p.first();\n" +
        "  public static io.github.eschizoid.telescope.mapping.Mapping.FromSource<Person, String> loose =" +
        " p -> p.first();\n" +
        "  public static String full(final Person p) { CALLS.incrementAndGet(); return p.first() + \" \" + p.last(); }\n" +
        "  public static int doubled(final Person p) { return p.age() * 2; }\n" +
        "  public static String none(final Person p) { return null; }\n" +
        "  public static String foreign(final Plain p) { return p.first(); }\n" +
        "  public static Boolean nobool(final Person p) { return null; }\n" +
        "  public static Long nolong(final Person p) { return null; }\n" +
        "  public static final io.github.eschizoid.telescope.mapping.Mapping.FromSource<Plain, String> OFF_LAMBDA =" +
        " p -> p.first();\n" +
        "  public static final io.github.eschizoid.telescope.mapping.Mapping.FromSource<Plain, String> OFF_REF =" +
        " Names::foreign;\n" +
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
      "cf.Names.HELD.apply(s)",
      "cf.Names.PLAIN.apply(s)"
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
        " doubled=72, missing=null, tag=#36, held=held ada, plain=plain ada]";
      assertEquals(owed, declared.forward(ada).toString());
      assertEquals(owed, runtime.forward(ada).toString());
      final var card = loader
        .loadClass("cf.Card")
        .getConstructors()[0].newInstance("tim", "lee", 9, "x", "y", "z", 1, 2, "w", "t", "h", "p");
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
    return withRow("Card", row);
  }

  /** As {@link #withRow(String)}, for a mapper of Person to {@code target}. */
  private static String[] withRow(final String target, final String row) {
    return new String[] {
      "cf.Odd",
      HEADER +
      "public final class Odd {\n" +
      "  @Bridge public static final ForwardMapper<Person, " +
      target +
      "> ODD = Telescope.mapperForward(Person.class, " +
      target +
      ".class, " +
      row +
      ");\n" +
      "}\n",
      "cf.OddRuntime",
      HEADER +
      "public final class OddRuntime {\n" +
      "  public static Object runtime() { return Telescope.mapperForward(Person.class, " +
      target +
      ".class, " +
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

  /**
   * What Ada converts to through {@code row} into {@code target}: by the generated bridge of a
   * {@code @Bridge} field and by the runtime path, in that order.
   */
  @SuppressWarnings("unchecked")
  private static List<String> forwardOnBothPaths(final String target, final String row) throws Exception {
    final var generated = compile(sources(withRow(target, row)));
    assertTrue(generated.success(), generated::errorMessages);
    try (final var loader = load(generated)) {
      final var declared = (ForwardMapper<Object, Object>) loader.loadClass("cf.Odd").getField("ODD").get(null);
      assertTrue(declared.explain().toString().contains("Runs: generated"), () -> declared.explain().toString());
      final var runtime = (ForwardMapper<Object, Object>) loader
        .loadClass("cf.OddRuntime")
        .getMethod("runtime")
        .invoke(null);
      final var ada = person(loader, "ada", "lovelace", 36);
      return List.of(declared.forward(ada).toString(), runtime.forward(ada).toString());
    }
  }

  /** Holds {@code row} to one refusal on both paths, in {@code words}. */
  private static void refusedOnBothPaths(final String row, final String words) throws Exception {
    final var generated = compile(sources(withRow(row)));
    assertTrue(generated.hasError(words), generated::errorMessages);
    assertEquals(words, runtimeRefusal(row));
  }

  @Test
  @DisplayName("a factory whose one-value form takes any Object runs as the supplier on both paths, as before")
  void aFactoryTakingAnyObjectRunsAsTheSupplier() throws Exception {
    final var owed =
      "Bag[first=ada, last=lovelace, age=36, tags=[], slot=null, label=null, flag=false, made=null, count=0]";
    assertEquals(
      List.of(owed, owed),
      forwardOnBothPaths(
        "Bag",
        "compute(Bag::tags, java.util.List::of), compute(Bag::slot, java.util.concurrent.atomic.AtomicReference::new)"
      )
    );
  }

  @Test
  @DisplayName("a reference whose form taking the source is private runs as the supplier on both paths")
  void aPrivateFormRunsAsTheSupplier() throws Exception {
    final var owed =
      "Bag[first=ada, last=lovelace, age=36, tags=null, slot=null, label=plain, flag=false, made=null, count=0]";
    assertEquals(List.of(owed, owed), forwardOnBothPaths("Bag", "compute(Bag::label, Hidden::label)"));
  }

  @Test
  @DisplayName("a null Boolean or Long result into a boolean or long property writes false or zero on both paths")
  void aNullWrapperWritesThePrimitivesDefault() throws Exception {
    final var owed =
      "Bag[first=ada, last=lovelace, age=36, tags=null, slot=null, label=null, flag=false, made=null, count=0]";
    assertEquals(
      List.of(owed, owed),
      forwardOnBothPaths("Bag", "compute(Bag::flag, Names::nobool), compute(Bag::count, Names::nolong)")
    );
  }

  @Test
  @DisplayName("a constructor with a form taking the source is refused in the words the runtime uses")
  void aConstructorWithASourceFormIsRefusedOnBothPaths() throws Exception {
    final var row = "compute(Bag::made, Wrapped::new)";
    final var words =
      "Deep map Person → Bag: compute(...) fills 'made' from Wrapped::new, which Java binds as a supplier taking" +
      " no argument, though new Wrapped(Person) takes the source Person. Hold the one meant in a static final" +
      " Supplier or FromSource field, or give the form taking the source a name of its own.";
    final var generated = compile(sources(withRow("Bag", row)));
    assertTrue(generated.hasError(words), generated::errorMessages);
    final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources(withRow("Bag", row)));
    try (final var loader = load(plain)) {
      final var refused = assertThrows(InvocationTargetException.class, () ->
        loader.loadClass("cf.OddRuntime").getMethod("runtime").invoke(null)
      );
      assertEquals(words, refused.getCause().getMessage());
    }
  }

  @Test
  @DisplayName("a target, a receiver or a second row the generated bridge cannot compile is refused by name")
  void rowsTheBridgeCannotCompileAreRefused() {
    assertTrue(
      compile(sources(withRow("compute(Card::toString, Names::full)"))).hasError(
        "is not the accessor of a property of Card"
      ),
      "a target that is no property"
    );
    final var receiver = compile(sources(withRow("compute(Card::full, Names.loose::apply)")));
    assertTrue(receiver.hasError("has a receiver the generated bridge cannot name"), receiver::errorMessages);
    final var twice = compile(sources(withRow("compute(Card::full, Names::full), compute(Card::full, Names::none)")));
    assertTrue(twice.hasError("duplicate override row for target field 'full'"), twice::errorMessages);
    final var held = compile(sources(withRow("compute(Card::letters, Names.HELD)")));
    assertTrue(
      held.hasError("Names.HELD gives java.lang.String, which the int property 'letters' does not hold"),
      held::errorMessages
    );
  }

  @Test
  @DisplayName("of several forms taking the source, both paths name the one whose parameter's class sorts first")
  void severalFormsNameTheSameOne() throws Exception {
    refusedOnBothPaths(
      "compute(Card::full, Several::label)",
      "Deep map Person → Card: compute(...) fills 'full' from Several::label, which Java binds as a supplier taking" +
        " no argument, though Several.label(Named) takes the source Person. Hold the one meant in a static final" +
        " Supplier or FromSource field, or give the form taking the source a name of its own."
    );
  }

  @Test
  @DisplayName("a form a superclass declares is named by its class, the reference by the class it names")
  void anInheritedFormNamesBothClasses() throws Exception {
    refusedOnBothPaths(
      "compute(Card::full, Derived::label)",
      "Deep map Person → Card: compute(...) fills 'full' from Derived::label, which Java binds as a supplier taking" +
        " no argument, though Base.label(Person) takes the source Person. Hold the one meant in a static final" +
        " Supplier or FromSource field, or give the form taking the source a name of its own."
    );
  }

  @Test
  @DisplayName("a bound reference whose receiver's subclass declares the form taking the source is refused on both paths")
  void aBoundReferenceWithASubclassFormIsRefusedOnBothPaths() throws Exception {
    refusedOnBothPaths(
      "compute(Card::full, Lower.LOWER::ii)",
      "Deep map Person → Card: compute(...) fills 'full' from Upper::ii, which Java binds as a supplier taking" +
        " no argument, though Lower.ii(Person) takes the source Person. Hold the one meant in a static final" +
        " Supplier or FromSource field, or give the form taking the source a name of its own."
    );
  }

  @Test
  @DisplayName("a bound reference written through a superclass runs as the supplier on both paths, whatever it holds")
  void aBoundReferenceThroughASuperclassRunsAsTheSupplier() throws Exception {
    final var owed =
      "Bag[first=ada, last=lovelace, age=36, tags=null, slot=null, label=upper, flag=false, made=null, count=0]";
    assertEquals(List.of(owed, owed), forwardOnBothPaths("Bag", "compute(Bag::label, Lower.AS_UPPER::ii)"));
  }

  @Test
  @DisplayName("a static reference whose subclass declares the form taking the source runs as the supplier on both paths")
  void aStaticReferenceWithASubclassFormRunsAsTheSupplier() throws Exception {
    final var owed =
      "Bag[first=ada, last=lovelace, age=36, tags=null, slot=null, label=upper, flag=false, made=null, count=0]";
    assertEquals(List.of(owed, owed), forwardOnBothPaths("Bag", "compute(Bag::label, Lower::zz)"));
  }

  @Test
  @DisplayName("an unbound reference to a name the subclass overloads reaches neither path, since javac cannot infer it")
  void anUnboundReferenceToTheSubclassDoesNotCompile() {
    final var row = "compute(Card::full, Lower::ii)";
    final var generated = compile(sources(withRow(row)));
    assertTrue(generated.hasError("invalid method reference"), generated::errorMessages);
    final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources(withRow(row)));
    assertTrue(plain.hasError("invalid method reference"), plain::errorMessages);
  }

  @Test
  @DisplayName("a function taking another class than the source is refused in the words the runtime uses")
  void aFunctionOffTheSourceIsRefusedOnBothPaths() throws Exception {
    refusedOnBothPaths(
      "compute(Card::full, Names::foreign)",
      "Deep map Person → Card: compute(...) fills 'full' from Names::foreign, which takes Plain, and the source" +
        " Person is not one. Name a method taking Person, or a method of Person."
    );
    refusedOnBothPaths(
      "compute(Card::full, Names.OFF_REF)",
      "Deep map Person → Card: compute(...) fills 'full' from Names::foreign, which takes Plain, and the source" +
        " Person is not one. Name a method taking Person, or a method of Person."
    );
    refusedOnBothPaths(
      "compute(Card::full, Names.OFF_LAMBDA)",
      "Deep map Person → Card: compute(...) fills 'full' from a lambda, which takes Plain, and the source" +
        " Person is not one. Name a method taking Person, or a method of Person."
    );
    refusedOnBothPaths(
      "compute(Card::full, Plain::first)",
      "Deep map Person → Card: compute(...) fills 'full' from Plain::first, which takes Plain, and the source" +
        " Person is not one. Name a method taking Person, or a method of Person."
    );
  }

  @Test
  @DisplayName("a reference whose name has a form taking the source too is refused in the words the runtime uses")
  void aReferenceWithASourceFormIsRefusedOnBothPaths() throws Exception {
    refusedOnBothPaths(
      "compute(Card::full, Names::label)",
      "Deep map Person → Card: compute(...) fills 'full' from Names::label, which Java binds as a supplier taking" +
        " no argument, though Names.label(Person) takes the source Person. Hold the one meant in a static final" +
        " Supplier or FromSource field, or give the form taking the source a name of its own."
    );
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
