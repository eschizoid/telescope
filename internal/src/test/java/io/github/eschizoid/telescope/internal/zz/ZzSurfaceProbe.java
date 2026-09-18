package io.github.eschizoid.telescope.internal.zz;

import io.github.eschizoid.telescope.internal.Beans;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

@SuppressWarnings({ "unchecked", "rawtypes" })
class ZzSurfaceProbe {

  /** 1. setters cover everything, no builder. */
  public static class AllSetters {
    private String name; private String code;
    public AllSetters() {}
    public String getName() { return name; } public void setName(String n) { name = n; }
    public String getCode() { return code; } public void setCode(String c) { code = c; }
  }

  /** 2. setters cover everything AND a builder covers everything. */
  public static class AllSettersAndBuilder {
    private String name; private String code;
    public AllSettersAndBuilder() {}
    public String getName() { return name; } public void setName(String n) { name = n; }
    public String getCode() { return code; } public void setCode(String c) { code = c; }
    public static B builder() { return new B(); }
    public static final class B {
      private final AllSettersAndBuilder t = new AllSettersAndBuilder();
      public B name(String n) { t.name = n; return this; }
      public B code(String c) { t.code = c; return this; }
      public AllSettersAndBuilder build() { return t; }
    }
  }

  /** 3. setters reach name only; builder reaches BOTH. The case #398 is about. */
  public static class PartialSettersFullBuilder {
    private String name; private String code;
    public PartialSettersFullBuilder() {}
    public String getName() { return name; } public void setName(String n) { name = n; }
    public String getCode() { return code; }
    public static B builder() { return new B(); }
    public static final class B {
      private final PartialSettersFullBuilder t = new PartialSettersFullBuilder();
      public B name(String n) { t.name = n; return this; }
      public B code(String c) { t.code = c; return this; }
      public PartialSettersFullBuilder build() { return t; }
    }
  }

  /** 4. setters reach name, builder reaches code. Neither covers. The strictly-worse case. */
  public static class DisjointSurfaces {
    private String name; private String code;
    public DisjointSurfaces() {}
    public String getName() { return name; } public void setName(String n) { name = n; }
    public String getCode() { return code; }
    public static B builder() { return new B(); }
    public static final class B {
      private final DisjointSurfaces t = new DisjointSurfaces();
      public B code(String c) { t.code = c; return this; }
      public DisjointSurfaces build() { return t; }
    }
  }

  /** 5. a computed getter neither surface can write, everything else covered by both. */
  public static class ComputedGetter {
    private String name;
    public ComputedGetter() {}
    public String getName() { return name; } public void setName(String n) { name = n; }
    public String getShout() { return name == null ? null : name.toUpperCase(); }
    public static B builder() { return new B(); }
    public static final class B {
      private final ComputedGetter t = new ComputedGetter();
      public B name(String n) { t.name = n; return this; }
      public ComputedGetter build() { return t; }
    }
  }

  /** 6. a builder() that returns something with no build(). */
  public static class UnrelatedBuilder {
    private String name; private String code;
    public UnrelatedBuilder() {}
    public String getName() { return name; } public void setName(String n) { name = n; }
    public String getCode() { return code; }
    public static String builder() { return "not a builder"; }
  }

  /** Writes `name` through the chosen surface and reports what the rebuilt bean holds. */
  private static String surface(
    final String label,
    final Class<?> c,
    final java.util.function.Supplier<Object> seed,
    final java.util.function.Function<Object, String> show
  ) {
    String writer;
    try {
      writer = Beans.autoWriter(c).getClass().getSimpleName();
    } catch (final Throwable t) {
      writer = "THREW " + t.getClass().getSimpleName();
      return String.format("  %-34s %-16s", label, writer);
    }
    String result;
    try {
      final var lens = Beans.lens((Class<Object>) c, "name", (Beans.BeanWriter<Object>) Beans.autoWriter(c));
      result = show.apply(lens.set(seed.get(), "bob"));
    } catch (final Throwable t) {
      result = "write THREW " + t.getClass().getSimpleName();
    }
    return String.format("  %-34s %-16s %s", label, writer, result);
  }

  @Test
  void probe() throws Exception {
    Files.writeString(Path.of("/tmp/surface398.txt"), String.join("\n",
      surface("1 all setters, no builder", AllSetters.class,
        () -> { var b = new AllSetters(); b.setName("alice"); b.setCode("AB"); return b; },
        o -> "name=" + ((AllSetters) o).getName() + " code=" + ((AllSetters) o).getCode()),
      surface("2 all setters + full builder", AllSettersAndBuilder.class,
        () -> { var b = new AllSettersAndBuilder(); b.setName("alice"); b.setCode("AB"); return b; },
        o -> "name=" + ((AllSettersAndBuilder) o).getName() + " code=" + ((AllSettersAndBuilder) o).getCode()),
      surface("3 partial setters, full builder", PartialSettersFullBuilder.class,
        () -> { var b = new PartialSettersFullBuilder(); b.setName("alice"); b.code = "AB"; return b; },
        o -> "name=" + ((PartialSettersFullBuilder) o).getName() + " code=" + ((PartialSettersFullBuilder) o).getCode()),
      surface("4 disjoint surfaces", DisjointSurfaces.class,
        () -> { var b = new DisjointSurfaces(); b.setName("alice"); b.code = "AB"; return b; },
        o -> "name=" + ((DisjointSurfaces) o).getName() + " code=" + ((DisjointSurfaces) o).getCode()),
      surface("5 computed getter", ComputedGetter.class,
        () -> { var b = new ComputedGetter(); b.setName("alice"); return b; },
        o -> "name=" + ((ComputedGetter) o).getName() + " shout=" + ((ComputedGetter) o).getShout()),
      surface("6 unrelated builder()", UnrelatedBuilder.class,
        () -> { var b = new UnrelatedBuilder(); b.setName("alice"); b.code = "AB"; return b; },
        o -> "name=" + ((UnrelatedBuilder) o).getName() + " code=" + ((UnrelatedBuilder) o).getCode())));
  }
}
