package io.github.eschizoid.telescope.codegen.fromjson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.FromJsonProcessor;
import io.github.eschizoid.telescope.codegen.ProcessorHarness;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.jackson.JsonBinder;
import io.github.eschizoid.telescope.jackson.TelescopeJson;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The {@code @FromJson} processor, driven in memory. Where the runtime binder refuses a type, the
 * processor refuses it at compile time in the same words, after its own prefix in place of the
 * runtime's; where the processor cannot build a type the way the runtime does, it refuses the type
 * by name.
 */
class FromJsonProcessorTest {

  private static final String PACKAGE = "io.github.eschizoid.telescope.codegen.fromjson";

  private static final String HEAD =
    "package " +
    PACKAGE +
    ";\nimport io.github.eschizoid.telescope.jackson.FromJson;\nimport com.fasterxml.jackson.annotation.*;\n" +
    "import tools.jackson.databind.annotation.JsonNaming;\nimport tools.jackson.databind.PropertyNamingStrategies;\n" +
    "import java.util.*;\n";

  /**
   * A type declared in source, compiled once with the processor and once without, with the types it
   * names, each written {@code Name|source}.
   */
  private record Shape(String name, String body, String... helpers) {}

  private static JavaFileObject[] sources(final Shape shape) {
    final var all = new ArrayList<JavaFileObject>();
    all.add(ProcessorHarness.source(PACKAGE + "." + shape.name(), HEAD + shape.body()));
    for (final var helper : shape.helpers()) {
      final var bar = helper.indexOf('|');
      all.add(ProcessorHarness.source(PACKAGE + "." + helper.substring(0, bar), HEAD + helper.substring(bar + 1)));
    }
    return all.toArray(JavaFileObject[]::new);
  }

  /** Every refusal the processor reports on the target. */
  private static List<String> refusals(final Shape shape) {
    final var compilation = ProcessorHarness.compile(new FromJsonProcessor(), sources(shape));
    return compilation
      .errors()
      .stream()
      .map(d -> d.getMessage(Locale.ROOT))
      .toList();
  }

  /** What the runtime binder says of the same type, compiled without the processor. */
  private static String runtimeRefusal(final Shape shape) {
    final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources(shape));
    assertTrue(plain.success(), plain::errorMessages);
    final var type = plain.define(MethodHandles.lookup()).get(PACKAGE + "." + shape.name());
    try {
      TelescopeJson.fromJson(type);
      return null;
    } catch (final IllegalArgumentException | IllegalStateException e) {
      return e.getMessage();
    }
  }

  private static final List<Shape> SHARED = List.of(
    new Shape(
      "Named",
      "@FromJson @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class) public record Named(String a) {}"
    ),
    new Shape("Formatted", "@FromJson public record Formatted(@JsonFormat(pattern = \"x\") String a) {}"),
    new Shape("Viewed", "@FromJson @JsonView(Object.class) public record Viewed(String a) {}"),
    new Shape("IgnoreList", "@FromJson @JsonIgnoreProperties({\"x\"}) public record IgnoreList(String a) {}"),
    new Shape("Unwrapped", "@FromJson public record Unwrapped(@JsonUnwrapped String a) {}"),
    new Shape("Setter", "@FromJson public record Setter(@JsonSetter(\"x\") String a) {}"),
    new Shape("Created", "@FromJson public record Created(String a) { @JsonCreator public Created {} }"),
    new Shape(
      "Factory",
      "@FromJson public record Factory(String a) { @JsonCreator public static Factory of(String a) { return new Factory(a); } }"
    ),
    new Shape(
      "AnySetter",
      "@FromJson public final class AnySetter { private String a; public String getA() { return a; }" +
        " public void setA(String a) { this.a = a; } @JsonAnySetter public void other(String k, Object v) {} }"
    ),
    new Shape(
      "Bundled",
      "@FromJson public record Bundled(@Both String a) {}",
      "Both|@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) @JacksonAnnotationsInside" +
        " @JsonProperty(\"b\") public @interface Both {}"
    ),
    new Shape(
      "Coloured",
      "@FromJson public record Coloured(Colour colour) {}",
      "Colour|public enum Colour { @JsonProperty(\"red\") RED, BLUE }"
    ),
    new Shape(
      "Aliased",
      "@FromJson public record Aliased(Map<Hue, String> byHue) {}",
      "Hue|public enum Hue { @JsonAlias(\"r\") RED }"
    ),
    new Shape("Clash", "@FromJson public record Clash(@JsonProperty(\"b\") String a, String b) {}"),
    new Shape("AliasClash", "@FromJson public record AliasClash(@JsonAlias(\"b\") String a, String b) {}"),
    new Shape(
      "TwoNames",
      "@FromJson public final class TwoNames { @JsonProperty(\"x\") private String a; public String getA() { return a; }" +
        " @JsonProperty(\"y\") public void setA(String a) { this.a = a; } }"
    ),
    new Shape("Arrayed", "@FromJson public record Arrayed(String[] values) {}"),
    new Shape("Listy", "@FromJson public record Listy(ArrayList<String> values) {}"),
    new Shape("Threaded", "@FromJson public record Threaded(Thread thread) {}"),
    new Shape("Shaped", "@FromJson public record Shaped(Shape shape) {}", "Shape|public interface Shape {}"),
    new Shape(
      "BadKeys",
      "@FromJson public record BadKeys(Map<Key, String> byKey) {}",
      "Key|public record Key(String k) {}"
    ),
    new Shape("Wild", "@FromJson public record Wild(List<?> values) {}"),
    new Shape("Raw", "@FromJson public record Raw(@SuppressWarnings(\"rawtypes\") List values) {}"),
    new Shape(
      "OnlyFields",
      "@FromJson public class OnlyFields { @JsonProperty(\"f\") private String p; public String getP() { return p; } }"
    ),
    new Shape(
      "NoWay",
      "@FromJson public final class NoWay { private final String a; public NoWay(String a, int b) { this.a = a; }" +
        " public String getA() { return a; } }"
    ),
    new Shape(
      "Thin",
      "@FromJson public final class Thin { private String a; private String b;" +
        " private Thin(String a) { this.a = a; } public String getA() { return a; } public String getB() { return b; }" +
        " public static Builder builder() { return new Builder(); }" +
        " public static final class Builder { private String a;" +
        " public Builder a(String a) { this.a = a; return this; } public Thin build() { return new Thin(a); } } }"
    )
  );

  @Test
  @DisplayName("a type the runtime binder refuses is refused at compile time in the same words")
  void sharedRefusalsAgree() {
    final var failures = new ArrayList<String>();
    for (final var shape : SHARED) {
      final var runtime = runtimeRefusal(shape);
      if (runtime == null) {
        failures.add(shape.name() + ": the runtime binder accepts it");
        continue;
      }
      final var prefix = "TelescopeJson.fromJson: ";
      final var owed = "@FromJson: " + (runtime.startsWith(prefix) ? runtime.substring(prefix.length()) : runtime);
      final var reported = refusals(shape);
      if (!reported.equals(List.of(owed))) failures.add(
        shape.name() + ":\n  owed     " + owed + "\n  reported " + reported
      );
    }
    assertEquals(List.of(), failures, () -> String.join("\n", failures));
  }

  @Nested
  @DisplayName("a shape the processor cannot build the way the runtime does is refused by name")
  class OwnRefusals {

    private void refusedWith(final Shape shape, final String fragment) {
      final var reported = refusals(shape);
      assertEquals(1, reported.size(), () -> "one refusal: " + reported);
      assertTrue(reported.getFirst().contains(fragment), () -> reported.getFirst());
    }

    @Test
    @DisplayName("a nested type")
    void nested() {
      refusedWith(
        new Shape("Holder", "public final class Holder { @FromJson public record In(String a) {} }"),
        "top-level types"
      );
    }

    @Test
    @DisplayName("an enum or an interface")
    void notARecordOrClass() {
      refusedWith(new Shape("Mode", "@FromJson public enum Mode { A }"), "records and classes");
      refusedWith(new Shape("Kind", "@FromJson public interface Kind {}"), "records and classes");
    }

    @Test
    @DisplayName("a generic type")
    void generic() {
      refusedWith(new Shape("Box", "@FromJson public record Box<T>(String a) {}"), "is generic");
    }

    @Test
    @DisplayName("a bean the runtime builds through its private no-argument constructor")
    void privateConstructor() {
      refusedWith(
        new Shape(
          "Closed",
          "@FromJson public final class Closed { private String a; private Closed() {} public String getA() { return a; }" +
            " public void setA(String a) { this.a = a; } }"
        ),
        "private no-argument constructor"
      );
    }

    @Test
    @DisplayName("an inner class, whose instances need an enclosing instance")
    void innerClass() {
      refusedWith(
        new Shape(
          "Holds",
          "@FromJson public record Holds(Box.Inner inner) {}",
          "Box|public class Box { public class Inner { private String a; public String getA() { return a; }" +
            " public void setA(String a) { this.a = a; } } }"
        ),
        "is an inner class"
      );
    }

    @Test
    @DisplayName("a type whose @FromMap binder was compiled apart from it")
    void separateFromMapBinder() {
      refusedWith(
        new Shape(
          "Kept",
          "@FromJson public record Kept(String a) {}",
          "KeptFromMap|public final class KeptFromMap { public static final class Provider {} }"
        ),
        "compiled apart from it"
      );
    }

    @Test
    @DisplayName("a type the generated binder cannot reach")
    void unreachable() {
      refusedWith(
        new Shape("Reach", "@FromJson public record Reach(Reach.Hidden hidden) { private record Hidden(String a) {} }"),
        "cannot be reached"
      );
    }
  }

  @Test
  @DisplayName(
    "a bean built through its constructor: with -parameters both binders build it; without, the runtime refuses in its words"
  )
  void constructorParameterNames() throws ReflectiveOperationException {
    final var shape = new Shape(
      "Made",
      "@FromJson public final class Made { private final String a; private final int b;" +
        " public Made(int b, String a) { this.a = a; this.b = b; }" +
        " public String getA() { return a; } public int getB() { return b; }" +
        " @Override public String toString() { return a + \"/\" + b; } }"
    );
    final var compiled = ProcessorHarness.compileFully(
      List.of(new FromJsonProcessor()),
      List.of("-parameters"),
      sources(shape)
    );
    assertTrue(compiled.success(), compiled::errorMessages);
    final var classes = compiled.define(MethodHandles.lookup());
    final var made = classes.get(PACKAGE + ".Made");
    final var generated = (JsonBinder<?>) classes.get(PACKAGE + ".MadeFromJson").getField("BINDER").get(null);
    final var document = "{\"a\":\"x\",\"b\":2}";
    assertEquals("x/2", TelescopeJson.fromJson(made).read(document).toString());
    assertEquals("x/2", generated.read(document).toString());

    // The same class under another name, so it can be defined beside the first, compiled with
    // neither -parameters nor -g, which leaves the runtime no parameter names to match.
    final var bare = new Shape("Unnamed", shape.body().replace("Made", "Unnamed"));
    final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources(bare));
    assertTrue(plain.success(), plain::errorMessages);
    final var unnamed = plain.define(MethodHandles.lookup()).get(PACKAGE + ".Unnamed");
    final var refused = assertThrows(IllegalStateException.class, () -> TelescopeJson.fromJson(unnamed));
    assertEquals(PairingMessages.noNameBasedWriteStrategy(PACKAGE + ".Unnamed", 2), refused.getMessage());
  }

  @Test
  @DisplayName("the processor claims @FromJson, so a build under -Xlint:all -Werror compiles")
  void claimsTheAnnotation() {
    final var compiled = ProcessorHarness.compileFullyWithOnly(
      List.of(new FromJsonProcessor()),
      List.of("-Xlint:all", "-Werror"),
      sources(new Shape("Claimed", "@FromJson public record Claimed(String a) {}"))
    );
    assertTrue(compiled.success(), compiled::errorMessages);
    assertTrue(compiled.generated().containsKey(PACKAGE + ".ClaimedFromJson"));
  }

  @Test
  @DisplayName("with Lombok present, only a target carrying a Lombok trigger waits for the final round")
  void onlyLombokTargetsWait() {
    // A stand-in for lombok.Data, which is what tells the processor Lombok is on the path. A
    // target written in the final round draws javac's "created in the last round" warning, which
    // -Werror turns into a failure.
    final var withLombok = ProcessorHarness.compileFullyWithOnly(
      List.of(new FromJsonProcessor()),
      List.of("-Xlint:all", "-Werror"),
      ProcessorHarness.source("lombok.Data", "package lombok; public @interface Data {}"),
      ProcessorHarness.source(PACKAGE + ".Untouched", HEAD + "@FromJson public record Untouched(String a) {}")
    );
    assertTrue(withLombok.success(), withLombok::errorMessages);
    assertTrue(withLombok.generated().containsKey(PACKAGE + ".UntouchedFromJson"));
  }

  @Test
  @DisplayName("every fixture the cross-path test reads compiles through the processor in memory")
  void theFixturesCompile() throws Exception {
    final var directory = Path.of("../jackson/src/test/java/io/github/eschizoid/telescope/jackson/fromjson");
    final var sources = new ArrayList<JavaFileObject>();
    try (final Stream<Path> files = Files.list(directory)) {
      for (final var file : files
        .filter(f -> f.toString().endsWith(".java") && !f.toString().endsWith("Test.java"))
        .toList()) {
        final var name = file.getFileName().toString().replace(".java", "");
        sources.add(
          ProcessorHarness.source("io.github.eschizoid.telescope.jackson.fromjson." + name, Files.readString(file))
        );
      }
    }
    final var compilation = ProcessorHarness.compileFully(
      List.of(new FromJsonProcessor()),
      List.of(),
      sources.toArray(JavaFileObject[]::new)
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    assertFalse(compilation.generated().isEmpty());
    for (final var binder : compilation.generated().values()) {
      assertFalse(binder.contains("import "), () -> "a binder names every type in full:\n" + binder);
      assertFalse(binder.contains("java.lang.reflect"), () -> "a binder uses no reflection:\n" + binder);
    }
  }
}
