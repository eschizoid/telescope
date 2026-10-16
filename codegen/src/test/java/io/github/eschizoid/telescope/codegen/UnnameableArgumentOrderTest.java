package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.conversion.ForwardMapper;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A constructor argument whose type the bridge's package cannot name stays inline in the call, so
 * every argument after it stays inline too, and the call evaluates them in component order.
 *
 * <p>The target's first component is a container nested in a class that is not public, which a
 * forward-only transform produces: the bridge passes the transform's result straight to the
 * constructor and never names its type. The second is a pair whose own bridge converts it. Both
 * conversions log as they run. The classes live in two packages, so they are compiled into a jar
 * and loaded through a loader of their own.
 */
class UnnameableArgumentOrderTest {

  @TempDir
  Path dir;

  private static final List<JavaFileObject> SOURCES = List.of(
    ProcessorHarness.source(
      "p7.Log",
      "package p7; public final class Log { public static final StringBuilder LOG = new StringBuilder(); }"
    ),
    ProcessorHarness.source(
      "q7.Hid",
      """
      package q7;
      class Hid {
        public static class Bag<E> extends java.util.ArrayList<E> {
          private static final long serialVersionUID = 1L;
          public Bag() {}
        }
      }
      """
    ),
    ProcessorHarness.source(
      "q7.ToBag",
      """
      package q7;
      @SuppressWarnings("exports")
      public final class ToBag
        implements io.github.eschizoid.telescope.conversion.BridgeFn<java.util.List<String>, Hid.Bag<String>> {
        @Override public Hid.Bag<String> forward(final java.util.List<String> in) {
          p7.Log.LOG.append("v;");
          final var out = new Hid.Bag<String>();
          out.addAll(in);
          return out;
        }
        @Override public java.util.List<String> backward(final Hid.Bag<String> in) { return in; }
      }
      """
    ),
    ProcessorHarness.source("q7.CDto", "package q7; public record CDto(String n) {}"),
    ProcessorHarness.source(
      "q7.Dst",
      "package q7; @SuppressWarnings(\"exports\") public record Dst(Hid.Bag<String> items, CDto c) {}"
    ),
    ProcessorHarness.source(
      "p7.CFn",
      """
      package p7;
      public final class CFn implements io.github.eschizoid.telescope.conversion.BridgeFn<String, String> {
        @Override public String forward(final String a) { Log.LOG.append("c;"); return a; }
        @Override public String backward(final String b) { Log.LOG.append("c;"); return b; }
      }
      """
    ),
    ProcessorHarness.source(
      "p7.C",
      """
      package p7;
      @io.github.eschizoid.telescope.annotations.Bridge(value = q7.CDto.class, transforms =
        @io.github.eschizoid.telescope.annotations.Transform(field = "n", using = CFn.class))
      public record C(String n) {}
      """
    ),
    ProcessorHarness.source(
      "p7.Src",
      """
      package p7;
      @io.github.eschizoid.telescope.annotations.Bridge(value = q7.Dst.class, transforms =
        @io.github.eschizoid.telescope.annotations.Transform(field = "items", using = q7.ToBag.class, forwardOnly = true))
      public record Src(java.util.List<String> items, C c) {}
      """
    ),
    ProcessorHarness.source(
      "q7.Runtime",
      """
      package q7;
      public final class Runtime {
        public static Object mapper() {
          final var fn = new ToBag();
          return io.github.eschizoid.telescope.Telescope.mapperForward(p7.Src.class, Dst.class,
            io.github.eschizoid.telescope.mapping.Mapping.<p7.Src, Dst, java.util.List<String>, Hid.Bag<String>>toOneWay(
              p7.Src::items, Dst::items, fn::forward));
        }
      }
      """
    )
  );

  @Test
  @DisplayName("an argument whose type the bridge cannot name keeps the call in component order on both paths")
  void anUnnameableArgumentKeepsTheCallInComponentOrder() throws Exception {
    final var compiled = ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      SOURCES.toArray(JavaFileObject[]::new)
    );
    assertTrue(compiled.success(), compiled::errorMessages);
    final var jar = dir.resolve("order.jar");
    try (final var out = new JarOutputStream(Files.newOutputStream(jar))) {
      for (final var entry : compiled.classes().entrySet()) {
        out.putNextEntry(new JarEntry(entry.getKey().replace('.', '/') + ".class"));
        out.write(entry.getValue());
        out.closeEntry();
      }
    }
    try (final var loader = new URLClassLoader(new URL[] { jar.toUri().toURL() }, getClass().getClassLoader())) {
      final var src = loader.loadClass("p7.Src");
      final var c = loader.loadClass("p7.C");
      final var log = (StringBuilder) loader.loadClass("p7.Log").getField("LOG").get(null);
      final var sample = src.getConstructors()[0].newInstance(List.of("a"), c.getConstructors()[0].newInstance("b"));
      log.setLength(0);
      loader.loadClass("p7.SrcBridge").getMethod("forward", src).invoke(null, sample);
      final var generated = log.toString();
      @SuppressWarnings("unchecked")
      final var mapper = (ForwardMapper<Object, Object>) loader
        .loadClass("q7.Runtime")
        .getMethod("mapper")
        .invoke(null);
      log.setLength(0);
      mapper.forward(sample);
      final var reflective = log.toString();
      assertEquals("v;c;", generated, "the generated path converts in component order");
      assertEquals("v;c;", reflective, "the runtime converts in component order");
    }
  }
}
