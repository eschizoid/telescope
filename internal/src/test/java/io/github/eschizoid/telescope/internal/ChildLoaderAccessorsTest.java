package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every accessor this module builds, over public classes that a child class loader holds. This
 * module's own loader cannot resolve those classes by name, so an accessor spun in this module
 * could not link against them; each one here has to come back as a closure and still work.
 */
class ChildLoaderAccessorsTest {

  private static final Map<String, String> SOURCES = Map.ofEntries(
    Map.entry("kid/Point", "package kid;\npublic record Point(int x, String label) {}\n"),
    Map.entry(
      "kid/PointBean",
      "package kid;\npublic class PointBean {\n  private int x;\n  private String label;\n" +
        "  public int getX() { return x; }\n  public void setX(final int x) { this.x = x; }\n" +
        "  public String getLabel() { return label; }\n" +
        "  public void setLabel(final String label) { this.label = label; }\n}\n"
    ),
    Map.entry(
      "kid/PointBuilt",
      "package kid;\npublic final class PointBuilt {\n  private final int x;\n  private final String label;\n" +
        "  private PointBuilt(final int x, final String label) { this.x = x; this.label = label; }\n" +
        "  public int getX() { return x; }\n  public String getLabel() { return label; }\n" +
        "  public static Builder builder() { return new Builder(); }\n" +
        "  public static final class Builder {\n    private int x = 7;\n    private String label = \"built\";\n" +
        "    public Builder x(final int x) { this.x = x; return this; }\n" +
        "    public Builder label(final String label) { this.label = label; return this; }\n" +
        "    public PointBuilt build() { return new PointBuilt(x, label); }\n  }\n}\n"
    ),
    Map.entry("kid/Pub", "package kid;\npublic class Pub extends java.util.TreeSet<String> {}\n"),
    Map.entry("kid/Held", "package kid;\npublic record Held(String name) {}\n"),
    Map.entry(
      "kid/HeldFieldOptics",
      "package kid;\npublic final class HeldFieldOptics {\n  private HeldFieldOptics() {}\n" +
        "  public static java.util.Map<String, Object> constants() { return java.util.Map.of(); }\n" +
        "  public static Held construct(final java.util.function.Function<String, Object> values) {\n" +
        "    return new Held((String) values.apply(\"name\"));\n  }\n}\n"
    )
  );

  private static URLClassLoader loader;

  @BeforeAll
  static void compileIntoAChildLoader() throws IOException {
    final var src = Files.createTempDirectory("kid-src");
    final var files = new ArrayList<Path>();
    for (final var entry : SOURCES.entrySet()) {
      final var file = src.resolve(entry.getKey() + ".java");
      Files.createDirectories(file.getParent());
      Files.writeString(file, entry.getValue());
      files.add(file);
    }
    final var out = Files.createTempDirectory("kid-out");
    final var compiler = ToolProvider.getSystemJavaCompiler();
    try (final var fileManager = compiler.getStandardFileManager(null, Locale.ROOT, null)) {
      final var ok = compiler
        .getTask(
          null,
          fileManager,
          null,
          List.of("-d", out.toString()),
          null,
          fileManager.getJavaFileObjectsFromPaths(files)
        )
        .call();
      assertTrue(ok, "the fixtures compile");
    }
    loader = new URLClassLoader(new URL[] { out.toUri().toURL() }, ChildLoaderAccessorsTest.class.getClassLoader());
  }

  @AfterAll
  static void closeTheLoader() throws IOException {
    loader.close();
  }

  private static Class<?> load(final String name) throws ClassNotFoundException {
    final var type = loader.loadClass(name);
    assertSame(loader, type.getClassLoader(), "the class comes from the child loader");
    return type;
  }

  private static Object instance(final String name, final Object... args) throws Exception {
    return load(name).getDeclaredConstructors()[0].newInstance(args);
  }

  private static Function<String, Object> values(final Object x, final Object label) {
    return name -> name.equals("x") ? x : label;
  }

  @Test
  @DisplayName("an accessor that names a class this module's loader cannot resolve is a closure")
  void aClassThisLoaderCannotResolveIsAClosure() throws Exception {
    final var point = load("kid.Point");
    final var lookup = ModuleAccess.privateLookupIn(point);

    assertNull(ModuleAccess.spinner(lookup, point.getMethod("label")), "a public accessor");
    assertNull(ModuleAccess.spinner(lookup, point.getDeclaredConstructors()[0]), "a public constructor");
    assertNull(
      ModuleAccess.spinner(MethodHandles.lookup(), load("kid.HeldFieldOptics").getMethod("construct", Function.class)),
      "a public method reached through this module's own full-privilege lookup"
    );
  }

  @Test
  @DisplayName("a record is read through its component accessors")
  void aRecordIsRead() throws Exception {
    final var point = instance("kid.Point", 3, "p");

    assertEquals("p", Records.read(point, "label"));
    assertEquals(3, Records.read(point, "x"));
  }

  @Test
  @DisplayName("a bean is read and written through its getters and setters")
  void aBeanIsReadAndWritten() throws Exception {
    final var type = load("kid.PointBean");
    final var bean = instance("kid.PointBean");

    Beans.capturedWriter(type, "label").accept(bean, "w");
    Beans.capturedWriter(type, "x").accept(bean, 4);

    assertEquals("w", Beans.capturedReader(type, "label").apply(bean));
    assertEquals(4, Beans.readProperty(bean, "x"));
  }

  @Test
  @DisplayName("a bean is built through its constructor and setters, and through its builder")
  void aBeanIsBuilt() throws Exception {
    final var names = new String[] { "x", "label" };

    final var set = Beans.settersWriter(load("kid.PointBean")).construct(names, values(5, "s"));
    assertEquals(5, Beans.readProperty(set, "x"));
    assertEquals("s", Beans.readProperty(set, "label"));

    final var built = Beans.builderWriter(load("kid.PointBuilt")).construct(names, values(6, "b"));
    assertEquals(6, Beans.readProperty(built, "x"));
    assertEquals("b", Beans.readProperty(built, "label"));
  }

  @Test
  @DisplayName("a container subtype, a bean and a builder-built class are allocated empty")
  void classesAreAllocated() throws Exception {
    final var set = Beans.intermediateAllocator(load("kid.Pub")).get();
    assertEquals(load("kid.Pub"), set.getClass());
    assertTrue(((Collection<?>) set).isEmpty());

    final var bean = Beans.intermediateAllocator(load("kid.PointBean")).get();
    assertEquals(load("kid.PointBean"), bean.getClass());

    final var built = Beans.intermediateAllocator(load("kid.PointBuilt")).get();
    assertNotNull(built);
    assertEquals("built", Beans.readProperty(built, "label"));
  }

  @Test
  @DisplayName("a generated metadata holder's construct method is bound")
  void aHolderConstructs() throws Exception {
    final var holder = MetadataHolderProbe.probeFor(load("kid.Held")).orElseThrow();

    assertEquals(instance("kid.Held", "h"), holder.constructor().apply(name -> "h"));
    assertEquals(Set.of(), holder.constantsByName().keySet());
  }
}
