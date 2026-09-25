package io.github.eschizoid.telescope.codegen;

import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class AuditScratchTest {

  private static ProcessorHarness.Compilation compile(final List<JavaFileObject> sources) {
    return ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      sources.toArray(JavaFileObject[]::new)
    );
  }

  private static List<JavaFileObject> elements() {
    return List.of(
      ProcessorHarness.source("demo.SA", "package demo; public record SA(String v) {}"),
      ProcessorHarness.source("demo.SB", "package demo; public record SB(String v) {}")
    );
  }

  private static List<JavaFileObject> pair(final String srcField, final String tgtField) {
    return List.of(
      ProcessorHarness.source(
        "demo.BSrc",
        "package demo;\nimport io.github.eschizoid.telescope.annotations.Bridge;\n@Bridge(demo.BDst.class)\npublic record BSrc(%s items) {}\n".formatted(
            srcField
          )
      ),
      ProcessorHarness.source("demo.BDst", "package demo; public record BDst(%s items) {}".formatted(tgtField))
    );
  }

  private static final java.nio.file.Path OUT = java.nio.file.Path.of("/private/tmp/claude-501/-Users-mariano-development-telescope/d6c5686e-9a65-4589-a058-36b220ca22c1/scratchpad/audit.txt");

  private static void report(final String label, final ProcessorHarness.Compilation c) {
    final var sb = new StringBuilder();
    sb.append("################ ").append(label).append('\n');
    sb.append("success=").append(c.success()).append('\n');
    sb.append("--- diagnostics ---\n").append(c.errorMessages()).append('\n');
    sb.append("--- demo.BSrcBridge ---\n").append(c.generated().get("demo.BSrcBridge")).append('\n');
    sb.append("################ end ").append(label).append("\n\n");
    try {
      java.nio.file.Files.writeString(
        OUT,
        sb.toString(),
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.APPEND
      );
    } catch (final java.io.IOException e) {
      throw new RuntimeException(e);
    }
  }

  @SafeVarargs
  private static List<JavaFileObject> concat(final List<JavaFileObject>... parts) {
    final var out = new ArrayList<JavaFileObject>();
    for (final var p : parts) out.addAll(p);
    return out;
  }

  /** CASE A: an immutable container reached through builder(). */
  @Test
  void immutableThroughBuilder() {
    final var frozen = ProcessorHarness.source(
      "demo.Frozen",
      """
      package demo;
      public final class Frozen<E> extends java.util.AbstractList<E> {
        private final java.util.List<E> items;
        private Frozen(java.util.List<E> items) { this.items = items; }
        @Override public E get(int i) { return items.get(i); }
        @Override public int size() { return items.size(); }
        public static Builder builder() { return new Builder(); }
        public static final class Builder {
          public Frozen<Object> build() { return new Frozen<>(java.util.List.of()); }
        }
      }
      """
    );
    report(
      "A immutable list via builder (bridged elements)",
      compile(concat(elements(), List.of(frozen), pair("demo.Frozen<demo.SA>", "demo.Frozen<demo.SB>")))
    );
    report(
      "A2 immutable list via builder (identity elements)",
      compile(concat(List.of(frozen), pair("java.util.List<String>", "demo.Frozen<String>")))
    );
  }

  /** CASE B: build() returns null. */
  @Test
  void nullBuild() {
    final var src = ProcessorHarness.source(
      "demo.NullBuilt",
      """
      package demo;
      public abstract class NullBuilt<E> extends java.util.ArrayList<E> {
        private static final long serialVersionUID = 1L;
        protected NullBuilt() {}
        public static Builder builder() { return new Builder(); }
        public static final class Builder {
          public NullBuilt<Object> build() { return null; }
        }
      }
      """
    );
    report(
      "B build() returns null",
      compile(concat(elements(), List.of(src), pair("demo.NullBuilt<demo.SA>", "demo.NullBuilt<demo.SB>")))
    );
  }

  /** CASE C: build() throws because the builder was never configured. */
  @Test
  void throwingBuild() {
    final var src = ProcessorHarness.source(
      "demo.Validated",
      """
      package demo;
      public abstract class Validated<E> extends java.util.ArrayList<E> {
        private static final long serialVersionUID = 1L;
        protected Validated() {}
        public static Builder builder() { return new Builder(); }
        public static final class Builder {
          private String name;
          public Builder name(String n) { this.name = n; return this; }
          public Validated<Object> build() {
            if (name == null) throw new IllegalStateException("name is required");
            return new ValidatedImpl<>();
          }
        }
      }
      """
    );
    final var impl = ProcessorHarness.source(
      "demo.ValidatedImpl",
      """
      package demo;
      public final class ValidatedImpl<E> extends Validated<E> {
        private static final long serialVersionUID = 1L;
        public ValidatedImpl() {}
      }
      """
    );
    report(
      "C build() throws when unconfigured",
      compile(concat(elements(), List.of(src, impl), pair("demo.Validated<demo.SA>", "demo.Validated<demo.SB>")))
    );
  }

  /** CASE D: build() hands back a shared, cached instance. */
  @Test
  void sharedBuild() {
    final var src = ProcessorHarness.source(
      "demo.Cached",
      """
      package demo;
      public abstract class Cached<E> extends java.util.ArrayList<E> {
        private static final long serialVersionUID = 1L;
        protected Cached() {}
        public static Builder builder() { return new Builder(); }
        public static final class Builder {
          private static final CachedImpl<Object> SHARED = new CachedImpl<>();
          public Cached<Object> build() { return SHARED; }
        }
      }
      """
    );
    final var impl = ProcessorHarness.source(
      "demo.CachedImpl",
      """
      package demo;
      public final class CachedImpl<E> extends Cached<E> {
        private static final long serialVersionUID = 1L;
        public CachedImpl() {}
      }
      """
    );
    report(
      "D build() returns a shared instance",
      compile(concat(elements(), List.of(src, impl), pair("demo.Cached<demo.SA>", "demo.Cached<demo.SB>")))
    );
  }

  /** CASE G: build() hands back a PREFILLED container (defaults baked into the builder). */
  @Test
  void prefilledBuild() {
    final var src = ProcessorHarness.source(
      "demo.Seeded",
      """
      package demo;
      public abstract class Seeded<E> extends java.util.ArrayList<E> {
        private static final long serialVersionUID = 1L;
        protected Seeded() {}
        public static Builder builder() { return new Builder(); }
        public static final class Builder {
          public Seeded<Object> build() {
            final SeededImpl<Object> out = new SeededImpl<>();
            out.add("seed");
            return out;
          }
        }
      }
      """
    );
    final var impl = ProcessorHarness.source(
      "demo.SeededImpl",
      """
      package demo;
      public final class SeededImpl<E> extends Seeded<E> {
        private static final long serialVersionUID = 1L;
        public SeededImpl() {}
      }
      """
    );
    report(
      "G build() returns a prefilled container",
      compile(concat(elements(), List.of(src, impl), pair("demo.Seeded<demo.SA>", "demo.Seeded<demo.SB>")))
    );
  }

  /** CASE E: a type that clearly intends the builder route but whose build() does not qualify. */
  @Test
  void widerBuildDiagnostic() {
    final var widened = ProcessorHarness.source(
      "demo.WideList",
      """
      package demo;
      public abstract class WideList<E> extends java.util.ArrayList<E> {
        private static final long serialVersionUID = 1L;
        protected WideList() {}
        public static Builder builder() { return new Builder(); }
        public static final class Builder {
          public java.util.ArrayList<Object> build() { return new java.util.ArrayList<>(); }
        }
      }
      """
    );
    report(
      "E build() is wider than the field",
      compile(concat(elements(), List.of(widened), pair("demo.WideList<demo.SA>", "demo.WideList<demo.SB>")))
    );

    // Same shape, but build() is inherited from an abstract builder base rather than declared.
    final var inherited = ProcessorHarness.source(
      "demo.InheritedList",
      """
      package demo;
      public abstract class InheritedList<E> extends java.util.ArrayList<E> {
        private static final long serialVersionUID = 1L;
        protected InheritedList() {}
        public static Builder builder() { return new Builder(); }
        public abstract static class BaseBuilder {
          public InheritedList<Object> build() { return new InheritedListImpl<>(); }
        }
        public static final class Builder extends BaseBuilder {}
      }
      """
    );
    final var inheritedImpl = ProcessorHarness.source(
      "demo.InheritedListImpl",
      """
      package demo;
      public final class InheritedListImpl<E> extends InheritedList<E> {
        private static final long serialVersionUID = 1L;
        public InheritedListImpl() {}
      }
      """
    );
    report(
      "E2 build() inherited from a builder base",
      compile(
        concat(
          elements(),
          List.of(inherited, inheritedImpl),
          pair("demo.InheritedList<demo.SA>", "demo.InheritedList<demo.SB>")
        )
      )
    );
  }

  /** CASE F: a concrete builder container that ALSO has a public copy constructor. */
  @Test
  void copyCtorAndBuilder() {
    final var src = ProcessorHarness.source(
      "demo.Both",
      """
      package demo;
      public final class Both<E> extends java.util.ArrayList<E> {
        private static final long serialVersionUID = 1L;
        private Both() {}
        public Both(java.util.Collection<? extends E> c) { super(c); }
        public static Builder builder() { return new Builder(); }
        public static final class Builder {
          public Both<Object> build() { return new Both<>(java.util.List.of()); }
        }
      }
      """
    );
    report(
      "F copy ctor + builder, identity elements",
      compile(concat(List.of(src), pair("java.util.List<String>", "demo.Both<String>")))
    );
  }
}
