package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serializable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * This module as a named module beside an application module, the way the module path loads them.
 * The application does not require this module, opens one package and leaves another only exported,
 * so every lookup here crosses a module boundary that the class path never has.
 *
 * <p>The layer holds its own copy of this module's classes, which the test reaches by reflection
 * after opening their package to itself through the layer's controller.
 */
class ModuleAccessLayerTest {

  private static final String INTERNAL = "io.github.eschizoid.telescope.internal";

  private static final Map<String, String> APP = Map.ofEntries(
    Map.entry("module-info", "module app {\n  exports demo;\n  opens demo;\n  exports closed;\n}\n"),
    Map.entry("demo/Point", "package demo;\npublic record Point(int x, String label) {}\n"),
    Map.entry("demo/Secret", "package demo;\nrecord Secret(String code) {}\n"),
    Map.entry(
      "demo/Ref",
      "package demo;\npublic interface Ref<T, R> extends java.util.function.Function<T, R>, java.io.Serializable {}\n"
    ),
    Map.entry(
      "demo/Refs",
      "package demo;\npublic final class Refs {\n  public static Object label() {\n    final Ref<Point, String> ref = Point::label;\n    return ref;\n  }\n}\n"
    ),
    Map.entry("demo/PubBean", bean("public class PubBean")),
    Map.entry("demo/HidBean", bean("class HidBean")),
    Map.entry("demo/PubBuilt", built("public final class", "PubBuilt")),
    Map.entry("demo/HidBuilt", built("final class", "HidBuilt")),
    Map.entry("closed/Thing", "package closed;\npublic record Thing(String name) {}\n"),
    Map.entry("closed/ClosedBean", bean("public class ClosedBean").replace("package demo;", "package closed;")),
    Map.entry("unexported/Kept", "package unexported;\npublic record Kept(String value) {}\n"),
    Map.entry(
      "demo/Holder",
      "package demo;\npublic record Holder(unexported.Kept kept) {\n" +
        "  public static Holder of(final String value) { return new Holder(new unexported.Kept(value)); }\n}\n"
    ),
    Map.entry(
      "closed/ClosedRefs",
      "package closed;\npublic final class ClosedRefs {\n  public static Object name() {\n    final demo.Ref<Thing, String> ref = Thing::name;\n    return ref;\n  }\n}\n"
    )
  );

  private static String bean(final String header) {
    return (
      "package demo;\n" +
      header +
      " {\n  private String name;\n  public " +
      header.substring(header.lastIndexOf(' ') + 1) +
      "() {}\n  public String getName() { return name; }\n  public void setName(final String name) { this.name = name; }\n}\n"
    );
  }

  private static String built(final String modifiers, final String name) {
    return (
      "package demo;\n" +
      modifiers +
      " " +
      name +
      " {\n  private final String name;\n  private " +
      name +
      "(final String name) { this.name = name; }\n  public String getName() { return name; }\n" +
      "  public static Builder builder() { return new Builder(); }\n" +
      "  public static final class Builder {\n    private String name = \"built\";\n" +
      "    public Builder name(final String name) { this.name = name; return this; }\n" +
      "    public " +
      name +
      " build() { return new " +
      name +
      "(name); }\n  }\n}\n"
    );
  }

  private static Path compiled;
  private static Path self;

  private ModuleLayer layer;
  private ClassLoader app;
  private ClassLoader internal;

  @BeforeAll
  static void compileApplication() throws Exception {
    final var src = Files.createTempDirectory("app-src");
    final var files = new ArrayList<Path>();
    for (final var entry : APP.entrySet()) {
      final var file = src.resolve(entry.getKey() + ".java");
      Files.createDirectories(file.getParent());
      Files.writeString(file, entry.getValue());
      files.add(file);
    }
    final var out = Files.createTempDirectory("app-out");
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
      assertTrue(ok, "the application module compiles");
    }
    compiled = out;
    self = Path.of(ModuleAccess.class.getProtectionDomain().getCodeSource().getLocation().toURI());
  }

  /** A fresh layer per test, so a read edge one test adds is not already there for the next. */
  @BeforeEach
  void buildLayer() {
    final var configuration = Configuration.resolve(
      ModuleFinder.of(compiled, self),
      List.of(ModuleLayer.boot().configuration()),
      ModuleFinder.of(),
      Set.of("app", INTERNAL)
    );
    final var controller = ModuleLayer.defineModulesWithOneLoader(
      configuration,
      List.of(ModuleLayer.boot()),
      ClassLoader.getSystemClassLoader()
    );
    layer = controller.layer();
    controller.addOpens(layer.findModule(INTERNAL).orElseThrow(), INTERNAL, ModuleAccessLayerTest.class.getModule());
    app = layer.findLoader("app");
    internal = layer.findLoader(INTERNAL);
  }

  private Object call(final String type, final String method, final Class<?>[] parameters, final Object... args)
    throws Throwable {
    final Method target = internal.loadClass(INTERNAL + "." + type).getDeclaredMethod(method, parameters);
    target.setAccessible(true);
    try {
      return target.invoke(null, args);
    } catch (final InvocationTargetException e) {
      throw e.getCause();
    }
  }

  private Class<?> appClass(final String name) throws ClassNotFoundException {
    return app.loadClass(name);
  }

  private Object newInstance(final String name, final Object... args) throws Exception {
    final var type = appClass(name);
    final var ctor = type.getDeclaredConstructors()[0];
    ctor.setAccessible(true);
    return ctor.newInstance(args);
  }

  private Object spinner(final Class<?> type, final Executable member) throws Throwable {
    final var lookup = call("ModuleAccess", "privateLookupIn", new Class<?>[] { Class.class }, type);
    return call(
      "ModuleAccess",
      "spinner",
      new Class<?>[] { MethodHandles.Lookup.class, Executable.class },
      lookup,
      member
    );
  }

  @Test
  @DisplayName("this module reads the application only once it adds the edge itself")
  void theReadEdgeIsAdded() throws Throwable {
    final var self = layer.findModule(INTERNAL).orElseThrow();
    final var appModule = layer.findModule("app").orElseThrow();
    final var point = appClass("demo.Point");

    assertFalse(self.canRead(appModule), "the application is not required, so not read");
    call("ModuleAccess", "read", new Class<?>[] { Class.class }, point);
    assertTrue(self.canRead(appModule));
    call("ModuleAccess", "read", new Class<?>[] { Class.class }, point);
    assertTrue(self.canRead(appModule), "reading it again changes nothing");
  }

  @Test
  @DisplayName("a public member of an opened package spins in this module, and a non-public one is a closure")
  void spinnerAcrossTheModuleBoundary() throws Throwable {
    final var point = appClass("demo.Point");
    final var publicSpinner = spinner(point, point.getMethod("label"));
    final var secret = appClass("demo.Secret");
    final var hiddenSpinner = spinner(secret, secret.getDeclaredMethod("code"));

    assertNull(hiddenSpinner, "a member of a non-public class is a closure");
    if (NativeImage.IN_IMAGE) {
      assertNull(publicSpinner, "an image never spins");
    } else {
      final var lookup = assertInstanceOf(MethodHandles.Lookup.class, publicSpinner);
      assertEquals(INTERNAL, lookup.lookupClass().getModule().getName(), "spun with this module's own lookup");
    }
  }

  @Test
  @DisplayName("a package the application does not open is refused, naming the opens to add to the application")
  void anUnopenedPackageNamesTheDirective() throws Throwable {
    final var thing = appClass("closed.Thing");
    assertThrows(IllegalAccessException.class, () ->
      call("ModuleAccess", "privateLookupIn", new Class<?>[] { Class.class }, thing)
    );
    assertEquals(
      "Add 'opens closed to io.github.eschizoid.telescope.internal;' (or an unqualified 'opens closed;') to the" +
        " module-info.java of module app, which telescope's accessors are built in.",
      call("ModuleAccess", "opensRemedy", new Class<?>[] { Class.class }, thing)
    );
  }

  @Test
  @DisplayName("a method reference written in an unopened package is refused with the same directive")
  void anUnopenedMethodReferenceNamesTheDirective() throws Throwable {
    final var opened = appClass("demo.Refs").getMethod("label").invoke(null);
    assertEquals("label", call("LambdaIntrospection", "methodNameOf", new Class<?>[] { Serializable.class }, opened));

    final var closed = appClass("closed.ClosedRefs").getMethod("name").invoke(null);
    final var refusal = assertThrows(IllegalStateException.class, () ->
      call("LambdaIntrospection", "methodNameOf", new Class<?>[] { Serializable.class }, closed)
    );
    assertTrue(
      refusal.getMessage().startsWith("Cannot read the method reference closed.ClosedRefs"),
      refusal::getMessage
    );
    assertTrue(
      refusal.getMessage().contains("Add 'opens closed to io.github.eschizoid.telescope.internal;'"),
      refusal::getMessage
    );
  }

  @Test
  @DisplayName("records read through spun readers when public, and through closures when not")
  void recordsReadOnBothPaths() throws Throwable {
    final var read = new Class<?>[] { Object.class, String.class };
    assertEquals("pt", call("Records", "read", read, newInstance("demo.Point", 1, "pt"), "label"));
    assertEquals(1, call("Records", "read", read, newInstance("demo.Point", 1, "pt"), "x"));
    assertEquals("s", call("Records", "read", read, newInstance("demo.Secret", "s"), "code"));
  }

  @Test
  @DisplayName(
    "a public accessor returning a public type from a package this module cannot see is a closure, and still reads"
  )
  void aTypeFromAnUnexportedPackageIsAClosure() throws Throwable {
    final var holder = appClass("demo.Holder");
    assertNull(spinner(holder, holder.getMethod("kept")), "the spun class could not name unexported.Kept");

    final var read = call(
      "Records",
      "read",
      new Class<?>[] { Object.class, String.class },
      holder.getMethod("of", String.class).invoke(null, "k"),
      "kept"
    );
    assertEquals("Kept[value=k]", String.valueOf(read));
  }

  @Test
  @DisplayName("a record or bean in a package the application does not open is refused with the opens to add")
  void unopenedRecordsAndBeansNameTheDirective() throws Throwable {
    final var record = assertThrows(IllegalStateException.class, () ->
      call("Records", "read", new Class<?>[] { Object.class, String.class }, newInstance("closed.Thing", "t"), "name")
    );
    assertTrue(
      record.getMessage().startsWith("Cannot access closed.Thing to build its accessors."),
      record::getMessage
    );
    assertTrue(
      record.getMessage().contains("Add 'opens closed to io.github.eschizoid.telescope.internal;'"),
      record::getMessage
    );

    final var bean = assertThrows(IllegalStateException.class, () ->
      call(
        "Beans",
        "capturedReader",
        new Class<?>[] { Class.class, String.class },
        appClass("closed.ClosedBean"),
        "name"
      )
    );
    assertTrue(
      bean.getMessage().startsWith("Cannot access closed.ClosedBean to build its getter invokers."),
      bean::getMessage
    );
    assertTrue(
      bean.getMessage().contains("Add 'opens closed to io.github.eschizoid.telescope.internal;'"),
      bean::getMessage
    );
  }

  @SuppressWarnings("unchecked")
  @Test
  @DisplayName("beans are read, written, built and allocated through spun accessors when public and closures when not")
  void beansOnBothPaths() throws Throwable {
    for (final var bean : List.of("demo.PubBean", "demo.HidBean")) {
      final var type = appClass(bean);
      final var instance = newInstance(bean);

      final var writer = (BiConsumer<Object, Object>) call(
        "Beans",
        "capturedWriter",
        new Class<?>[] { Class.class, String.class },
        type,
        "name"
      );
      writer.accept(instance, "written");
      assertEquals(
        "written",
        call("Beans", "readProperty", new Class<?>[] { Object.class, String.class }, instance, "name"),
        bean
      );

      final var setters = call("Beans", "settersWriter", new Class<?>[] { Class.class }, type);
      final var built = construct(setters, "set");
      assertEquals(
        "set",
        call("Beans", "readProperty", new Class<?>[] { Object.class, String.class }, built, "name"),
        bean
      );
    }
    for (final var bean : List.of("demo.PubBuilt", "demo.HidBuilt")) {
      final var type = appClass(bean);
      final var builder = call("Beans", "builderWriter", new Class<?>[] { Class.class }, type);
      final var built = construct(builder, "from builder");
      assertEquals(
        "from builder",
        call("Beans", "readProperty", new Class<?>[] { Object.class, String.class }, built, "name"),
        bean
      );
      final var allocator = (Supplier<Object>) call(
        "Beans",
        "intermediateAllocator",
        new Class<?>[] { Class.class },
        type
      );
      final var allocated = allocator.get();
      assertNotNull(allocated, bean);
      assertEquals(
        "built",
        call("Beans", "readProperty", new Class<?>[] { Object.class, String.class }, allocated, "name"),
        bean
      );
    }
  }

  @Test
  @DisplayName("a public constructor on a class that is not public is bound across the module boundary")
  void aPublicConstructorIsBoundAcrossTheModuleBoundary() throws Throwable {
    final var self = layer.findModule(INTERNAL).orElseThrow();
    final var appModule = layer.findModule("app").orElseThrow();
    final var hidden = appClass("demo.HidBean");
    assertFalse(self.canRead(appModule), "nothing has added the read edge yet");
    assertTrue(appModule.isOpen("demo", self), "the package is opened to this module");
    assertFalse(Modifier.isPublic(hidden.getModifiers()), "so only a private lookup reaches the class");

    final var ctor = (MethodHandle) call(
      "Beans",
      "publicConstructor",
      new Class<?>[] { Class.class, Class[].class },
      hidden,
      new Class<?>[0]
    );

    assertNotNull(ctor, "bound through the private lookup, after adding the read edge it needs");
    assertEquals(hidden, ctor.invoke().getClass());
  }

  @Test
  @DisplayName("a public constructor in a package neither exported nor opened to this module is not bound")
  void aConstructorNoLookupCanReachIsNotBound() throws Throwable {
    final var self = layer.findModule(INTERNAL).orElseThrow();
    final var appModule = layer.findModule("app").orElseThrow();
    final var kept = appClass("unexported.Kept");
    assertFalse(appModule.isExported("unexported"), "the public lookup cannot reach the package");
    assertFalse(appModule.isOpen("unexported", self), "and neither can a private one");
    assertTrue(Modifier.isPublic(kept.getConstructor(String.class).getModifiers()), "though the constructor is public");

    assertNull(
      call(
        "Beans",
        "publicConstructor",
        new Class<?>[] { Class.class, Class[].class },
        kept,
        new Class<?>[] { String.class }
      )
    );
  }

  /** A writer's {@code construct} for one property, {@code name}, holding {@code value}. */
  private Object construct(final Object writer, final Object value) throws Throwable {
    final var construct = internal
      .loadClass(INTERNAL + ".Beans$BeanWriter")
      .getMethod("construct", String[].class, Function.class);
    construct.setAccessible(true);
    try {
      return construct.invoke(writer, new String[] { "name" }, (Function<String, Object>) n -> value);
    } catch (final InvocationTargetException e) {
      throw e.getCause();
    }
  }
}
