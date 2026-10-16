package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.lang.invoke.MethodHandles;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Navigators, steps and holders generated for a generic class, or for a class whose components name
 * one. Each declared type names the generic class raw, as a static member and a caller holding
 * either a raw or a parameterised value both need, and each compiles with no warning. Every case
 * also compiles and runs a caller, which is what shows that the declared types are the ones callers
 * can use and that the lens bodies still read and rebuild the class.
 */
class GenericNavigatorTest {

  private static final String PACKAGE = "io.github.eschizoid.telescope.codegen";
  private static final String HEAD = "package " + PACKAGE + ";\n";
  private static final String FOCUS = "@io.github.eschizoid.telescope.annotations.Focus\n";
  private static final String BEAN_FOCUS = "@io.github.eschizoid.telescope.annotations.BeanFocus\n";
  private static final String IMPORTS =
    "import io.github.eschizoid.telescope.Telescope;\nimport java.util.List;\nimport java.util.Map;\n";

  private static JavaFileObject source(final String simpleName, final String code) {
    return ProcessorHarness.source(PACKAGE + "." + simpleName, HEAD + code);
  }

  /**
   * The value {@code caller}'s static no-arg {@code run()} returns, run from the compiled classes.
   */
  private static Object run(final Compilation compilation, final String caller) throws ReflectiveOperationException {
    assertTrue(compilation.success(), compilation::errorMessages);
    final var classes = compilation.define(MethodHandles.lookup());
    return classes.get(PACKAGE + "." + caller).getMethod("run").invoke(null);
  }

  @Test
  @DisplayName("a navigator and holder for a generic record compile with no warning and update the record")
  void aGenericRecordNavigatorCompilesWithNoWarning() throws ReflectiveOperationException {
    final var compilation = NavigatorCompiler.warningFree(
      new FocusProcessor(),
      source("GnrBox", FOCUS + "public record GnrBox<X>(String name, java.util.List<String> tags, int count) {}\n"),
      source(
        "GnrCaller",
        IMPORTS +
          "@SuppressWarnings({\"rawtypes\", \"unchecked\"})\n" +
          "public class GnrCaller {\n" +
          "  public static String run() {\n" +
          "    final GnrBox<Integer> box = new GnrBox<>(\"a\", List.of(\"x\"), 1);\n" +
          "    final GnrBox<Integer> renamed = GnrBoxTelescope.of().name().update(box, String::toUpperCase);\n" +
          "    final GnrBox raw = GnrBoxTelescope.of().tags().each().update(renamed, t -> t + \"!\");\n" +
          "    final Telescope<GnrBox, Integer> count = GnrBoxFieldOptics.count;\n" +
          "    final Telescope<GnrBox, GnrBox> root = GnrBoxTelescope.of().get();\n" +
          "    final Telescope<GnrBox, List<String>> tags = GnrBoxFieldOptics.tags;\n" +
          "    final GnrBox<Integer> counted = count.set(raw, 7);\n" +
          "    final Map<String, Telescope<?, ?>> constants = GnrBoxFieldOptics.constants();\n" +
          "    final GnrBox built = GnrBoxFieldOptics.construct(Map.<String, Object>of(\"name\", \"c\", \"tags\", List.of())::get);\n" +
          "    return root.read(counted).name() + tags.read(counted) + counted.count() + constants.size() + built.count();\n" +
          "  }\n" +
          "}\n"
      )
    );
    assertEquals("A[x!]730", run(compilation, "GnrCaller"));
  }

  @Test
  @DisplayName("a navigator and holder for a generic bean compile with no warning under every rebuild")
  void aGenericBeanNavigatorCompilesWithNoWarning() throws ReflectiveOperationException {
    final var setters =
      BEAN_FOCUS +
      "public class GnbSet<X> {\n" +
      "  private String name; private java.util.List<String> tags; private int count;\n" +
      "  public String getName() { return name; }\n" +
      "  public void setName(final String v) { name = v; }\n" +
      "  public java.util.List<String> getTags() { return tags; }\n" +
      "  public void setTags(final java.util.List<String> v) { tags = v; }\n" +
      "  public int getCount() { return count; }\n" +
      "  public void setCount(final int v) { count = v; }\n" +
      "}\n";
    final var constructor =
      BEAN_FOCUS +
      "public class GnbCtor<X> {\n" +
      "  private final String name; private final java.util.List<String> tags;\n" +
      "  public GnbCtor(final String name, final java.util.List<String> tags) { this.name = name; this.tags = tags; }\n" +
      "  public String getName() { return name; }\n" +
      "  public java.util.List<String> getTags() { return tags; }\n" +
      "}\n";
    final var builder =
      BEAN_FOCUS +
      "public class GnbBuilt<X> {\n" +
      "  private final String name; private final java.util.List<String> tags;\n" +
      "  private GnbBuilt(final String name, final java.util.List<String> tags) { this.name = name; this.tags = tags; }\n" +
      "  public String getName() { return name; }\n" +
      "  public java.util.List<String> getTags() { return tags; }\n" +
      "  public static <X> Builder<X> builder() { return new Builder<>(); }\n" +
      "  public static final class Builder<X> {\n" +
      "    private String name; private java.util.List<String> tags;\n" +
      "    public Builder<X> name(final String v) { name = v; return this; }\n" +
      "    public Builder<X> tags(final java.util.List<String> v) { tags = v; return this; }\n" +
      "    public GnbBuilt<X> build() { return new GnbBuilt<>(name, tags); }\n" +
      "  }\n" +
      "}\n";
    final var caller =
      IMPORTS +
      "@SuppressWarnings({\"rawtypes\", \"unchecked\"})\n" +
      "public class GnbCaller {\n" +
      "  public static String run() {\n" +
      "    final GnbSet<Integer> set = new GnbSet<>();\n" +
      "    set.setName(\"a\"); set.setTags(List.of(\"x\")); set.setCount(2);\n" +
      "    final GnbSet<Integer> renamed = GnbSetTelescope.of().name().update(set, String::toUpperCase);\n" +
      "    final Telescope<GnbSet, Integer> count = GnbSetFieldOptics.count;\n" +
      "    final GnbCtor<Integer> ctor = new GnbCtor<>(\"b\", List.of(\"y\"));\n" +
      "    final GnbCtor tagged = GnbCtorTelescope.of().tags().each().update(ctor, t -> t + \"!\");\n" +
      "    final Telescope<GnbCtor, String> ctorName = GnbCtorFieldOptics.name;\n" +
      "    final GnbBuilt<Integer> built = GnbBuilt.<Integer>builder().name(\"c\").tags(List.of(\"z\")).build();\n" +
      "    final GnbBuilt<Integer> rebuilt = GnbBuiltFieldOptics.name.set(built, \"C\");\n" +
      "    final Telescope<GnbBuilt, GnbBuilt> root = GnbBuiltTelescope.of().get();\n" +
      "    return renamed.getName() + renamed.getTags() + count.read(renamed)\n" +
      "      + ctorName.read(tagged) + tagged.getTags()\n" +
      "      + root.read(rebuilt).getName() + rebuilt.getTags();\n" +
      "  }\n" +
      "}\n";
    final var compilation = NavigatorCompiler.warningFree(
      new BeanFocusProcessor(),
      source("GnbSet", setters),
      source("GnbCtor", constructor),
      source("GnbBuilt", builder),
      source("GnbCaller", caller)
    );
    assertEquals("A[x]2b[y!]C[z]", run(compilation, "GnbCaller"));
  }

  @Test
  @DisplayName("a component naming a generic class compiles with no warning, raw or parameterised")
  void aComponentNamingAGenericClassCompilesWithNoWarning() throws ReflectiveOperationException {
    // A parameterised component of a navigable generic class ends the path in a terminal, as a
    // container of one does: its navigator holds the class raw and cannot take the parameterised
    // path. A raw component of the same class descends into that navigator.
    final var compilation = NavigatorCompiler.warningFree(
      new FocusProcessor(),
      source("GncLeaf", FOCUS + "public record GncLeaf<Q>(String label) {}\n"),
      source("GncPlain", "public record GncPlain<Q>(String label) {}\n"),
      source(
        "GncHolder",
        FOCUS +
          "@SuppressWarnings(\"rawtypes\")\n" +
          "public record GncHolder(GncLeaf<String> leaf, GncLeaf raw, GncPlain<String> plain, GncPlain rawPlain,\n" +
          "    java.util.List<GncLeaf<Integer>> leaves, java.util.List<GncLeaf> rawLeaves,\n" +
          "    java.util.Map<String, GncPlain> byName, java.util.Optional<GncLeaf<String>> maybe) {}\n"
      ),
      source(
        "GncCaller",
        IMPORTS +
          "@SuppressWarnings({\"rawtypes\", \"unchecked\"})\n" +
          "public class GncCaller {\n" +
          "  public static String run() {\n" +
          "    final GncHolder h = new GncHolder(new GncLeaf<>(\"a\"), new GncLeaf<>(\"b\"), new GncPlain<>(\"c\"),\n" +
          "      new GncPlain<>(\"d\"), List.of(new GncLeaf<>(\"e\")), List.of(new GncLeaf<>(\"f\")),\n" +
          "      Map.of(\"k\", new GncPlain<>(\"g\")), java.util.Optional.of(new GncLeaf<>(\"h\")));\n" +
          "    final Telescope<GncHolder, GncLeaf<String>> leaf = GncHolderTelescope.of().leaf();\n" +
          "    final GncHolder out = GncHolderTelescope.of().raw().label().update(\n" +
          "      GncHolderTelescope.of().rawLeaves().each().label().update(h, String::toUpperCase), String::toUpperCase);\n" +
          "    final Telescope<GncHolder, GncPlain> rawPlain = GncHolderTelescope.of().rawPlain();\n" +
          "    final Telescope<GncHolder, GncLeaf<Integer>> leaves = GncHolderTelescope.of().leaves().each();\n" +
          "    final Telescope<GncHolder, GncPlain> byName = GncHolderTelescope.of().byName().eachValue();\n" +
          "    final Telescope<GncHolder, GncLeaf<String>> maybe = GncHolderTelescope.of().maybe().whenPresent();\n" +
          "    return leaf.read(out).label() + out.raw().label() + ((GncPlain) rawPlain.read(out)).label()\n" +
          "      + leaves.read(out).label() + ((GncLeaf) out.rawLeaves().get(0)).label()\n" +
          "      + byName.read(out).label() + maybe.read(out).label();\n" +
          "  }\n" +
          "}\n"
      )
    );
    assertEquals("aBdeFgh", run(compilation, "GncCaller"));
  }

  @Test
  @DisplayName("a bridge hop to a generic target compiles with no warning, navigable or not")
  void aBridgeHopToAGenericTargetCompilesWithNoWarning() throws ReflectiveOperationException {
    final var bridge = "@io.github.eschizoid.telescope.annotations.Bridge(";
    final var compilation = NavigatorCompiler.warningFree(
      List.of(new FocusProcessor(), new BridgeProcessor()),
      source("GnhSrc", FOCUS + bridge + "GnhDst.class)\npublic record GnhSrc(String name) {}\n"),
      source("GnhDst", FOCUS + "public record GnhDst<Y>(String name) {}\n"),
      source("GnhOther", FOCUS + bridge + "GnhEnd.class)\npublic record GnhOther(String name) {}\n"),
      source("GnhEnd", "public record GnhEnd<Y>(String name) {}\n"),
      source("GnhGeneric", FOCUS + bridge + "GnhFlat.class)\npublic record GnhGeneric<X>(String name) {}\n"),
      source("GnhFlat", "public record GnhFlat(String name) {}\n"),
      source(
        "GnhCaller",
        IMPORTS +
          "@SuppressWarnings({\"rawtypes\", \"unchecked\"})\n" +
          "public class GnhCaller {\n" +
          "  public static String run() {\n" +
          "    final Telescope<GnhSrc, String> viaNavigator = GnhSrcTelescope.of().asGnhDst().name();\n" +
          "    final Telescope<GnhOther, GnhEnd> terminal = GnhOtherTelescope.of().asGnhEnd();\n" +
          "    final Telescope<GnhGeneric, GnhFlat> fromGeneric = GnhGenericTelescope.of().asGnhFlat();\n" +
          "    return viaNavigator.read(new GnhSrc(\"a\")) + terminal.read(new GnhOther(\"b\")).name()\n" +
          "      + fromGeneric.read(new GnhGeneric<String>(\"c\")).name();\n" +
          "  }\n" +
          "}\n"
      )
    );
    assertEquals("abc", run(compilation, "GnhCaller"));
  }
}
