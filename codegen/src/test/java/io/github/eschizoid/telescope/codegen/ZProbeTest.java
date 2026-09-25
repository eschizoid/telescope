package io.github.eschizoid.telescope.codegen;

import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/** Scratch probe: print what the processor actually emits for builder-reachable containers. */
class ZProbeTest {

  private static void run(final String label, final List<JavaFileObject> sources) {
    final var c = ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      sources.toArray(JavaFileObject[]::new)
    );
    System.out.println("========== " + label + " ==========");
    System.out.println("success=" + c.success());
    System.out.println("errors=" + c.errorMessages());
    c.generated().forEach((k, v) -> {
      if (k.endsWith("Bridge")) {
        System.out.println("---- " + k + " ----");
        System.out.println(v);
      }
    });
  }

  private static JavaFileObject src(final String fqn, final String body) {
    return ProcessorHarness.source(fqn, body);
  }

  private static List<JavaFileObject> elements() {
    return List.of(
      src("demo.SA", "package demo; public record SA(String v) {}"),
      src("demo.SB", "package demo; public record SB(String v) {}")
    );
  }

  private static List<JavaFileObject> pair(final String sf, final String tf) {
    return List.of(
      src(
        "demo.BSrc",
        "package demo;\nimport io.github.eschizoid.telescope.annotations.Bridge;\n@Bridge(demo.BDst.class)\npublic record BSrc(" +
          sf +
          " items) {}\n"
      ),
      src("demo.BDst", "package demo; public record BDst(" + tf + " items) {}")
    );
  }

  /** PROBE 1: a concrete, fully-allocatable container that ALSO happens to have a builder(). */
  @Test
  void concreteWithBothCtorAndBuilder() {
    final var tags = src(
      "demo.Tags",
      """
      package demo;
      public class Tags<E> extends java.util.ArrayList<E> {
        private static final long serialVersionUID = 1L;
        public Tags() {}
        public Tags(java.util.Collection<? extends E> c) { super(c); }
        public static Builder builder() { return new Builder(); }
        public static final class Builder {
          public Tags<Object> build() { return new Tags<>(); }
        }
      }
      """
    );
    run(
      "concrete + public no-arg ctor + copy ctor + builder (elements bridged)",
      concat(elements(), List.of(tags), pair("demo.Tags<demo.SA>", "demo.Tags<demo.SB>"))
    );
    run(
      "concrete + public no-arg ctor + builder (elements identity, differing shape)",
      concat(List.of(tags), pair("java.util.List<String>", "demo.Tags<String>"))
    );
  }

  /** PROBE 2: a builder() inherited from a superclass rather than declared. */
  @Test
  void inheritedBuilder() {
    final var base = src(
      "demo.BaseB",
      """
      package demo;
      public abstract class BaseB<E> extends java.util.ArrayList<E> {
        private static final long serialVersionUID = 1L;
        protected BaseB() {}
        public static Builder builder() { return new Builder(); }
        public static final class Builder {
          public SubB<Object> build() { return new SubB<>(); }
        }
      }
      """
    );
    final var sub = src(
      "demo.SubB",
      """
      package demo;
      public class SubB<E> extends BaseB<E> {
        private static final long serialVersionUID = 1L;
        public SubB() {}
      }
      """
    );
    // Declared type is the ABSTRACT base whose builder is declared on it: accepted.
    run("abstract base with own builder", concat(elements(), List.of(base, sub), pair("demo.BaseB<demo.SA>", "demo.BaseB<demo.SB>")));
  }

  /** PROBE 3: build() declared on a SUPERCLASS of the builder returned by builder(). */
  @Test
  void buildInheritedOnBuilder() {
    final var t = src(
      "demo.InhB",
      """
      package demo;
      public abstract class InhB<E> extends java.util.ArrayList<E> {
        private static final long serialVersionUID = 1L;
        protected InhB() {}
        public static Builder builder() { return new Builder(); }
        public abstract static class BaseBuilder {
          public InhB<Object> build() { return new InhBImpl<>(); }
        }
        public static final class Builder extends BaseBuilder {}
      }
      """
    );
    final var impl = src(
      "demo.InhBImpl",
      "package demo; public final class InhBImpl<E> extends InhB<E> { private static final long serialVersionUID = 1L; public InhBImpl() {} }"
    );
    run("build() inherited on the builder", concat(elements(), List.of(t, impl), pair("demo.InhB<demo.SA>", "demo.InhB<demo.SB>")));
  }

  /** PROBE 4: builder() whose build() returns a SUBTYPE (narrower). */
  @Test
  void narrowerBuild() {
    final var t = src(
      "demo.NarB",
      """
      package demo;
      public abstract class NarB<E> extends java.util.ArrayList<E> {
        private static final long serialVersionUID = 1L;
        protected NarB() {}
        public static Builder builder() { return new Builder(); }
        public static final class Builder {
          public NarBImpl<Object> build() { return new NarBImpl<>(); }
        }
      }
      """
    );
    final var impl = src(
      "demo.NarBImpl",
      "package demo; public final class NarBImpl<E> extends NarB<E> { private static final long serialVersionUID = 1L; public NarBImpl() {} }"
    );
    run("build() returns a subtype", concat(elements(), List.of(t, impl), pair("demo.NarB<demo.SA>", "demo.NarB<demo.SB>")));
  }

  /** PROBE 5: a builder-reachable SORTED set — does the comparator survive? */
  @Test
  void sortedThroughBuilder() {
    final var t = src(
      "demo.SortB",
      """
      package demo;
      public abstract class SortB<E> extends java.util.TreeSet<E> {
        private static final long serialVersionUID = 1L;
        protected SortB() {}
        public static Builder builder() { return new Builder(); }
        public static final class Builder {
          public SortB<Object> build() { return new SortBImpl<>(); }
        }
      }
      """
    );
    final var impl = src(
      "demo.SortBImpl",
      "package demo; public final class SortBImpl<E> extends SortB<E> { private static final long serialVersionUID = 1L; public SortBImpl() {} }"
    );
    run(
      "sorted set through a builder, identity elements",
      concat(List.of(t, impl), pair("java.util.SortedSet<String>", "demo.SortB<String>"))
    );
  }

  @SafeVarargs
  private static List<JavaFileObject> concat(final List<JavaFileObject>... parts) {
    final var out = new java.util.ArrayList<JavaFileObject>();
    for (final var p : parts) out.addAll(p);
    return out;
  }
}
