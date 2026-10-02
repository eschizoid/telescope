package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The runtime path converting an application's own types when the application is a named module on
 * the module path. Telescope's modules do not require the application module, and an application
 * module opening its package grants access but not readability, so this is where a lookup into the
 * application's classes either works or fails.
 *
 * <p>The application is compiled as module {@code app}, loaded into a module layer beside
 * telescope's modules, and asked to run every conversion inside itself. A conversion that fails
 * reports its exception, so one assertion names every capability that broke.
 */
class ModulePathConversionTest {

  private static final String MAIN = """
    package demo;

    import static io.github.eschizoid.telescope.mapping.MapExtractStep.extract;
    import static io.github.eschizoid.telescope.mapping.MergeStep.from;

    import io.github.eschizoid.telescope.Sources;
    import io.github.eschizoid.telescope.Telescope;
    import java.util.ArrayList;
    import java.util.List;
    import java.util.Map;
    import java.util.function.Supplier;

    public final class Main {

      private Main() {}

      public static List<String> run() {
        final var out = new ArrayList<String>();
        final var outer = new Outer(new Inner("Austin"), "ada");
        attempt(out, "record mapper forward", () -> Telescope.mapper(Outer.class, OuterView.class).forward(outer));
        attempt(out, "record mapper backward", () ->
          Telescope.mapper(Outer.class, OuterView.class).backward(new OuterView(new Inner("Lima"), "bo"))
        );
        attempt(out, "record field update", () ->
          Telescope.of(Outer.class).field(Outer::inner).field(Inner::city).update(outer, String::toUpperCase)
        );
        attempt(out, "record to bean mapper forward", () ->
          describe(Telescope.mapper(Outer.class, Person.class).forward(outer))
        );
        attempt(out, "bean to record mapper forward", () -> {
          final var person = new Person();
          person.setName("cy");
          person.setInner(new Inner("Oslo"));
          return Telescope.mapper(Person.class, Outer.class).forward(person);
        });
        attempt(out, "bean field read and update", () -> {
          final var person = new Person();
          person.setName("di");
          final var name = Telescope.ofBean(Person.class).field(Person::getName);
          return name.read(person) + "/" + describe(name.update(person, String::toUpperCase));
        });
        attempt(out, "fromMap", () ->
          Telescope.fromMap(
            Outer.class,
            extract("name", Outer::name, Object::toString),
            extract("city", Outer::inner, v -> new Inner(v.toString()))
          ).forward(Map.of("name", "ed", "city", "Kyiv"))
        );
        attempt(out, "record to builder bean mapper forward", () -> {
          final var card = Telescope.mapper(Outer.class, Card.class).forward(outer);
          return "Card[" + card.getName() + ", " + card.getInner() + "]";
        });
        attempt(out, "builder bean field update", () -> {
          final var card = Telescope.ofBean(Card.class)
            .field(Card::getName)
            .update(Card.builder().name("gus").inner(new Inner("Bern")).build(), String::toUpperCase);
          return "Card[" + card.getName() + ", " + card.getInner() + "]";
        });
        attempt(out, "non-public nested record field update", () ->
          Telescope.of(Wrapper.class)
            .field(Wrapper::hidden)
            .field(Hidden::label)
            .update(new Wrapper(new Hidden("x"), "w"), String::toUpperCase)
        );
        attempt(out, "merge", () ->
          Telescope.merge(Outer.class, from(Person::getName, Outer::name), from(Holder::inner, Outer::inner)).forward(
            Sources.of(person("fa"), new Holder(new Inner("Rome")))
          )
        );
        return out;
      }

      private static Person person(final String name) {
        final var person = new Person();
        person.setName(name);
        return person;
      }

      private static String describe(final Person person) {
        return "Person[" + person.getName() + ", " + person.getInner() + "]";
      }

      private static void attempt(final List<String> out, final String name, final Supplier<Object> run) {
        try {
          out.add(name + ": " + run.get());
        } catch (final RuntimeException | LinkageError e) {
          final var chain = new StringBuilder(name).append(" FAILED: ").append(e);
          for (var cause = e.getCause(); cause != null; cause = cause.getCause()) chain.append(" <- ").append(cause);
          out.add(chain.toString());
        }
      }
    }
    """;

  private static final String PERSON = """
    package demo;

    public class Person {
      private String name;
      private Inner inner;

      public Person() {}

      public String getName() { return name; }

      public void setName(final String name) { this.name = name; }

      public Inner getInner() { return inner; }

      public void setInner(final Inner inner) { this.inner = inner; }
    }
    """;

  private static final String CARD = """
    package demo;

    public final class Card {
      private final String name;
      private final Inner inner;

      private Card(final String name, final Inner inner) {
        this.name = name;
        this.inner = inner;
      }

      public String getName() { return name; }

      public Inner getInner() { return inner; }

      public static Builder builder() { return new Builder(); }

      public static final class Builder {
        private String name;
        private Inner inner;

        public Builder name(final String name) { this.name = name; return this; }

        public Builder inner(final Inner inner) { this.inner = inner; return this; }

        public Card build() { return new Card(name, inner); }
      }
    }
    """;

  private static ProcessorHarness.Compilation compileApp(final String directives) {
    return ProcessorHarness.compileFully(
      List.of(),
      List.of("--module-path", ModuleLayers.modulePath()),
      ProcessorHarness.source(
        "module-info",
        "module app {\n  requires io.github.eschizoid.telescope;\n  exports demo;\n" + directives + "}\n"
      ),
      ProcessorHarness.source("demo.Inner", "package demo;\npublic record Inner(String city) {}\n"),
      ProcessorHarness.source("demo.Outer", "package demo;\npublic record Outer(Inner inner, String name) {}\n"),
      ProcessorHarness.source(
        "demo.OuterView",
        "package demo;\npublic record OuterView(Inner inner, String name) {}\n"
      ),
      ProcessorHarness.source("demo.Holder", "package demo;\npublic record Holder(Inner inner) {}\n"),
      ProcessorHarness.source("demo.Person", PERSON),
      ProcessorHarness.source("demo.Card", CARD),
      ProcessorHarness.source("demo.Hidden", "package demo;\nrecord Hidden(String label) {}\n"),
      ProcessorHarness.source("demo.Wrapper", "package demo;\npublic record Wrapper(Hidden hidden, String name) {}\n"),
      ProcessorHarness.source("demo.Main", MAIN)
    );
  }

  @SuppressWarnings("unchecked")
  private static List<String> runApp(final String directives) throws Exception {
    final var compilation = compileApp(directives);
    assertTrue(compilation.success(), compilation::errorMessages);
    final var layer = ModuleLayers.layer(compilation, "app");
    try {
      return (List<String>) layer.findLoader("app").loadClass("demo.Main").getMethod("run").invoke(null);
    } catch (final InvocationTargetException e) {
      throw new AssertionError("the application threw outside a conversion", e.getCause());
    }
  }

  @Test
  @DisplayName("an application module that opens its package converts its records and beans, nested, both ways")
  void anOpenedModuleConverts() throws Exception {
    assertEquals(
      List.of(
        "record mapper forward: OuterView[inner=Inner[city=Austin], name=ada]",
        "record mapper backward: Outer[inner=Inner[city=Lima], name=bo]",
        "record field update: Outer[inner=Inner[city=AUSTIN], name=ada]",
        "record to bean mapper forward: Person[ada, Inner[city=Austin]]",
        "bean to record mapper forward: Outer[inner=Inner[city=Oslo], name=cy]",
        "bean field read and update: di/Person[DI, null]",
        "fromMap: Outer[inner=Inner[city=Kyiv], name=ed]",
        "record to builder bean mapper forward: Card[ada, Inner[city=Austin]]",
        "builder bean field update: Card[GUS, Inner[city=Bern]]",
        "non-public nested record field update: Wrapper[hidden=Hidden[label=X], name=w]",
        "merge: Outer[inner=Inner[city=Rome], name=fa]"
      ),
      runApp("  opens demo;\n")
    );
  }

  @Test
  @DisplayName("an application module that opens its package only to the module building the accessors converts")
  void aModuleOpenedToTelescopeConverts() throws Exception {
    final var results = runApp("  opens demo to io.github.eschizoid.telescope.internal;\n");
    assertTrue(results.stream().noneMatch(r -> r.contains("FAILED")), () -> String.join("\n", results));
  }

  @Test
  @DisplayName("an application module that does not open its package is told which opens to add, and to which module")
  void anUnopenedModuleIsToldWhatToOpen() throws Exception {
    final var results = runApp("");
    final var forward = results.getFirst();
    assertTrue(forward.startsWith("record mapper forward FAILED"), forward);
    assertTrue(
      forward.contains(
        "Add 'opens demo to io.github.eschizoid.telescope.internal;' (or an unqualified 'opens demo;') to the" +
          " module-info.java of module app"
      ),
      forward
    );
    // A method reference is read back through its own class, which the same opens covers.
    final var update = results.get(2);
    assertTrue(update.startsWith("record field update FAILED"), update);
    assertTrue(update.contains("Cannot read the method reference demo.Main"), update);
    assertTrue(update.contains("Add 'opens demo to io.github.eschizoid.telescope.internal;'"), update);
  }
}
