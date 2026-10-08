package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Conversion between classes that a child class loader holds, the way a plugin host or an
 * application server loads them. Telescope's own loader cannot resolve these classes by name, so a
 * class spun in that loader could not link against them; their accessors and allocators have to be
 * method handle closures instead.
 */
class ChildLoaderConversionTest {

  private static URLClassLoader loader;

  @BeforeAll
  static void compileIntoAChildLoader() throws IOException {
    final var compilation = ProcessorHarness.compileFully(
      List.of(),
      List.of(),
      ProcessorHarness.source("cl.Pub", "package cl;\npublic class Pub extends java.util.TreeSet<String> {}\n"),
      ProcessorHarness.source("cl.Tags", "package cl;\npublic record Tags(java.util.Set<String> tags) {}\n"),
      ProcessorHarness.source("cl.PubTags", "package cl;\npublic record PubTags(Pub tags) {}\n"),
      ProcessorHarness.source("cl.Point", "package cl;\npublic record Point(int x, String label) {}\n"),
      ProcessorHarness.source(
        "cl.PointBean",
        "package cl;\npublic class PointBean {\n  private int x;\n  private String label;\n" +
          "  public int getX() { return x; }\n  public void setX(final int x) { this.x = x; }\n" +
          "  public String getLabel() { return label; }\n" +
          "  public void setLabel(final String label) { this.label = label; }\n}\n"
      ),
      ProcessorHarness.source(
        "cl.PointBuilt",
        "package cl;\npublic final class PointBuilt {\n  private final int x;\n  private final String label;\n" +
          "  private PointBuilt(final int x, final String label) { this.x = x; this.label = label; }\n" +
          "  public int getX() { return x; }\n  public String getLabel() { return label; }\n" +
          "  public static Builder builder() { return new Builder(); }\n" +
          "  public static final class Builder {\n    private int x;\n    private String label;\n" +
          "    public Builder x(final int x) { this.x = x; return this; }\n" +
          "    public Builder label(final String label) { this.label = label; return this; }\n" +
          "    public PointBuilt build() { return new PointBuilt(x, label); }\n  }\n}\n"
      )
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var dir = Files.createTempDirectory("child-loader");
    for (final var entry : compilation.classes().entrySet()) {
      final var file = dir.resolve(entry.getKey().replace('.', '/') + ".class");
      Files.createDirectories(file.getParent());
      Files.write(file, entry.getValue());
    }
    loader = new URLClassLoader(new URL[] { dir.toUri().toURL() }, ChildLoaderConversionTest.class.getClassLoader());
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

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Object forward(final Class<?> source, final Class<?> target, final Object value) {
    return Telescope.mapper((Class) source, (Class) target).forward(value);
  }

  private static Object get(final Object bean, final String getter) throws Exception {
    final Method method = bean.getClass().getMethod(getter);
    return method.invoke(bean);
  }

  @Test
  @DisplayName("a public container subtype from a child loader is allocated and filled")
  void aPublicContainerSubtypeConverts() throws Exception {
    final var converted = forward(load("cl.Tags"), load("cl.PubTags"), instance("cl.Tags", Set.of("b", "a")));

    final var tags = get(converted, "tags");
    assertEquals(load("cl.Pub"), tags.getClass());
    assertEquals(List.of("a", "b"), List.copyOf((Collection<?>) tags));
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Telescope<Object, Object> path(final Telescope<?, ?> root, final String field) {
    return (Telescope) root.fieldByName(field);
  }

  @Test
  @DisplayName("a record from a child loader is read and rebuilt through a path")
  void aRecordIsNavigated() throws Exception {
    final var label = path(Telescope.of(load("cl.Point")), "label");
    final var point = instance("cl.Point", 3, "p");

    assertEquals("p", label.read(point));
    assertEquals(instance("cl.Point", 3, "q"), label.set(point, "q"));
  }

  @Test
  @DisplayName("a bean from a child loader is read and rebuilt through a path")
  void aBeanIsNavigated() throws Exception {
    final var label = path(Telescope.ofBean(load("cl.PointBean")), "label");
    final var bean = forward(load("cl.Point"), load("cl.PointBean"), instance("cl.Point", 3, "p"));

    assertEquals("p", label.read(bean));
    final var updated = label.set(bean, "q");
    assertEquals(3, get(updated, "getX"));
    assertEquals("q", get(updated, "getLabel"));
  }

  @Test
  @DisplayName("a builder-built class from a child loader is read and rebuilt through a path")
  void aBuiltClassIsNavigated() throws Exception {
    final var label = path(Telescope.ofBean(load("cl.PointBuilt")), "label");
    final var built = forward(load("cl.Point"), load("cl.PointBuilt"), instance("cl.Point", 3, "p"));

    assertEquals("p", label.read(built));
    final var updated = label.set(built, "q");
    assertEquals(3, get(updated, "getX"));
    assertEquals("q", get(updated, "getLabel"));
  }
}
