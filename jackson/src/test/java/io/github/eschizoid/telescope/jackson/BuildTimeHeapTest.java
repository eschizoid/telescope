package io.github.eschizoid.telescope.jackson;

import static io.github.eschizoid.telescope.mapping.MapExtractStep.extract;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.eschizoid.telescope.jackson.Fixtures.Address;
import io.github.eschizoid.telescope.jackson.Fixtures.Booking;
import io.github.eschizoid.telescope.jackson.Fixtures.Keyed;
import io.github.eschizoid.telescope.jackson.Fixtures.Order;
import io.github.eschizoid.telescope.jackson.Fixtures.Tree;
import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A native image initializes telescope's classes while it is built, as {@code telescope-core}'s
 * {@code native-image.properties} asks for every {@code io.github.eschizoid.telescope} package, and
 * stores in the image heap whatever their static fields reach. It stores a binder an application
 * keeps in a static field of such a class the same way. Jackson's types are initialized at run
 * time, and an object of one in the image heap fails the build, so neither may reach one.
 */
class BuildTimeHeapTest {

  private static final List<String> MODULE_CLASSES = List.of(
    "io.github.eschizoid.telescope.jackson.TelescopeJson",
    "io.github.eschizoid.telescope.jackson.JsonBinder",
    "io.github.eschizoid.telescope.jackson.Plan",
    "io.github.eschizoid.telescope.jackson.Frame",
    "io.github.eschizoid.telescope.jackson.Refused",
    "io.github.eschizoid.telescope.jackson.TelescopeModule",
    "io.github.eschizoid.telescope.jackson.TelescopeModule$Builder"
  );

  @Test
  @DisplayName("initializing the module's classes stores no Jackson object in a static field")
  void classInitializationReachesNoJacksonObject() throws Exception {
    // A loader of its own, so the classes initialize here as an image build initializes them, and
    // not as they stand after other tests have read with them.
    try (final var fresh = new URLClassLoader(classPath(), ClassLoader.getPlatformClassLoader())) {
      for (final var name : MODULE_CLASSES) {
        final var type = Class.forName(name, true, fresh);
        for (final var field : type.getDeclaredFields()) {
          if (!Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
            continue;
          }
          field.setAccessible(true);
          assertNull(jacksonReachable(field.get(null), name + "." + field.getName()));
        }
      }
    }
  }

  @Test
  @DisplayName("a built binder reaches no Jackson object, even after it has read a document")
  void binderReachesNoJacksonObject() throws IllegalAccessException {
    final var binders = List.of(
      TelescopeJson.fromJson(Order.class),
      TelescopeJson.fromJson(Tree.class),
      TelescopeJson.fromJson(Keyed.class),
      TelescopeJson.fromJson(Booking.class, extract("postal_code", Address::zip, Object::toString))
    );
    assertEquals("x", TelescopeJson.fromJson(Order.class).read("{\"number\":\"x\"}").number());
    for (final var binder : binders) {
      assertNull(jacksonReachable(binder, binder.toString()));
    }
  }

  private static URL[] classPath() throws MalformedURLException {
    final var entries = System.getProperty("java.class.path").split(File.pathSeparator);
    final var urls = new URL[entries.length];
    for (var i = 0; i < entries.length; i++) {
      urls[i] = new File(entries[i]).toURI().toURL();
    }
    return urls;
  }

  /** The path to the first Jackson object reachable from {@code root}, or null when none is. */
  private static String jacksonReachable(final Object root, final String rootName) throws IllegalAccessException {
    final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    final var pending = new ArrayList<Object[]>();
    pending.add(new Object[] { root, rootName });
    while (!pending.isEmpty()) {
      final var next = pending.removeLast();
      final var value = next[0];
      final var path = (String) next[1];
      if (value == null || value instanceof Class<?> || !seen.add(value)) {
        continue;
      }
      final var type = value.getClass();
      if (type.getName().startsWith("tools.jackson.") || type.getName().startsWith("com.fasterxml.jackson.")) {
        return path + " holds a " + type.getName();
      }
      if (type.isArray()) {
        if (!type.getComponentType().isPrimitive()) {
          for (var i = 0; i < Array.getLength(value); i++) {
            pending.add(new Object[] { Array.get(value, i), path + "[" + i + "]" });
          }
        }
        continue;
      }
      if (value instanceof Collection<?> collection) {
        for (final var element : collection) {
          pending.add(new Object[] { element, path + "[]" });
        }
        continue;
      }
      if (value instanceof Map<?, ?> map) {
        for (final var entry : map.entrySet()) {
          pending.add(new Object[] { entry.getKey(), path + "{key}" });
          pending.add(new Object[] { entry.getValue(), path + "{" + entry.getKey() + "}" });
        }
        continue;
      }
      if (value instanceof Optional<?> optional) {
        pending.add(new Object[] { optional.orElse(null), path + ".get()" });
        continue;
      }
      if (type.getName().startsWith("java.")) {
        continue;
      }
      for (var c = type; c != null && !c.getName().startsWith("java."); c = c.getSuperclass()) {
        for (final Field field : c.getDeclaredFields()) {
          if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
            continue;
          }
          field.setAccessible(true);
          pending.add(new Object[] { field.get(value), path + "." + field.getName() });
        }
      }
    }
    return null;
  }
}
