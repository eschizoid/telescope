package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.BUILDER;
import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.CONSTRUCTOR;
import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.SETTERS;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBean;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.internal.MhIso;
import io.github.eschizoid.telescope.mapping.MapStep;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The composed build of a bean written through its constructor or its builder, against the array
 * leaf and against the proxy dispatch of a nested slot.
 *
 * <p>Each bean's constructor and builder normalise what they are given (trim, upper-case), and its
 * setters store it as given, so a composed build that went through a different writer than the
 * array leaf shows up as a different value rather than passing unnoticed. Every pair below is also
 * checked to take the composed leaf, so the comparisons run against a composed build and not
 * against the array leaf twice.
 */
@DisplayName("MhIso constructor and builder builds ↔ array leaf and proxy parity")
final class MhComposedBuildParityTest {

  @AfterEach
  void restoreToggles() {
    System.clearProperty(MhIso.DISABLE_PROPERTY);
    System.clearProperty(MhIso.FUSION_DISABLE_PROPERTY);
  }

  public record Src(int i, Integer wi, String str, long l) {}

  /**
   * The same properties, readable and buildable by no writer: its one constructor names nothing. A
   * pair from it composes only the forward direction, so a composed leaf from it means the target's
   * build composed.
   */
  public static final class ReadOnlySrc {

    private final int i;
    private final Integer wi;
    private final String str;
    private final long l;

    public ReadOnlySrc(final int a, final Integer b, final String c, final long d) {
      this.i = a;
      this.wi = b;
      this.str = c;
      this.l = d;
    }

    public int getI() {
      return i;
    }

    public Integer getWi() {
      return wi;
    }

    public String getStr() {
      return str;
    }

    public long getL() {
      return l;
    }
  }

  /** Boxed where {@link CtorBean} is primitive, so a null reaches a primitive parameter. */
  public record NullableSrc(Integer i, Integer wi, String str, Long l) {}

  /** Built through its constructor, which trims; its setters store what they are given. */
  public static final class CtorBean {

    private int i;
    private Integer wi;
    private String str;
    private long l;

    public CtorBean() {}

    public CtorBean(final int i, final Integer wi, final String str, final long l) {
      this.i = i;
      this.wi = wi;
      this.str = str == null ? null : str.trim();
      this.l = l;
    }

    public int getI() {
      return i;
    }

    public void setI(final int i) {
      this.i = i;
    }

    public Integer getWi() {
      return wi;
    }

    public void setWi(final Integer wi) {
      this.wi = wi;
    }

    public String getStr() {
      return str;
    }

    public void setStr(final String str) {
      this.str = str;
    }

    public long getL() {
      return l;
    }

    public void setL(final long l) {
      this.l = l;
    }

    @Override
    public boolean equals(final Object o) {
      return o instanceof CtorBean b && i == b.i && l == b.l && Objects.equals(wi, b.wi) && Objects.equals(str, b.str);
    }

    @Override
    public int hashCode() {
      return Objects.hash(i, wi, str, l);
    }

    @Override
    public String toString() {
      return "CtorBean{" + i + "," + wi + "," + str + "," + l + "}";
    }
  }

  /** Built through its builder, which upper-cases; its setters store what they are given. */
  public static final class BuiltBean {

    private int i;
    private Integer wi;
    private String str;
    private long l;

    public BuiltBean() {}

    public int getI() {
      return i;
    }

    public void setI(final int i) {
      this.i = i;
    }

    public Integer getWi() {
      return wi;
    }

    public void setWi(final Integer wi) {
      this.wi = wi;
    }

    public String getStr() {
      return str;
    }

    public void setStr(final String str) {
      this.str = str;
    }

    public long getL() {
      return l;
    }

    public void setL(final long l) {
      this.l = l;
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      private final BuiltBean built = new BuiltBean();

      public Builder i(final int i) {
        built.i = i;
        return this;
      }

      public Builder wi(final Integer wi) {
        built.wi = wi;
        return this;
      }

      public Builder str(final String str) {
        built.str = str == null ? null : str.toUpperCase(Locale.ROOT);
        return this;
      }

      public Builder l(final long l) {
        built.l = l;
        return this;
      }

      public BuiltBean build() {
        return built;
      }
    }

    @Override
    public boolean equals(final Object o) {
      return o instanceof BuiltBean b && i == b.i && l == b.l && Objects.equals(wi, b.wi) && Objects.equals(str, b.str);
    }

    @Override
    public int hashCode() {
      return Objects.hash(i, wi, str, l);
    }

    @Override
    public String toString() {
      return "BuiltBean{" + i + "," + wi + "," + str + "," + l + "}";
    }
  }

  /** A source whose {@code upper} getter throws, so reading that slot is observable. */
  public static final class ThrowingSrc {

    private int i;
    private String str;

    public ThrowingSrc() {}

    public int getI() {
      return i;
    }

    public void setI(final int i) {
      this.i = i;
    }

    public String getStr() {
      return str;
    }

    public void setStr(final String str) {
      this.str = str;
    }

    public String getUpper() {
      throw new IllegalStateException("upper read");
    }

    public void setUpper(final String upper) {}
  }

  /** Built through its builder, which has no member for the computed {@code upper}. */
  public static final class ComputedBuilt {

    private final int i;
    private final String str;

    private ComputedBuilt(final int i, final String str) {
      this.i = i;
      this.str = str;
    }

    public int getI() {
      return i;
    }

    public String getStr() {
      return str;
    }

    public String getUpper() {
      return str == null ? null : str.toUpperCase(Locale.ROOT);
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      private int i;
      private String str;

      public Builder i(final int i) {
        this.i = i;
        return this;
      }

      public Builder str(final String str) {
        this.str = str;
        return this;
      }

      public ComputedBuilt build() {
        return new ComputedBuilt(i, str);
      }
    }

    @Override
    public boolean equals(final Object o) {
      return o instanceof ComputedBuilt b && i == b.i && Objects.equals(str, b.str);
    }

    @Override
    public int hashCode() {
      return Objects.hash(i, str);
    }

    @Override
    public String toString() {
      return "ComputedBuilt{" + i + "," + str + "}";
    }
  }

  public record OuterRec(String tag, Src inner) {}

  /** A builder-built parent around a constructor-built child, for fusion through a nested slot. */
  public static final class OuterBuilt {

    private String tag;
    private CtorBean inner;

    public OuterBuilt() {}

    public String getTag() {
      return tag;
    }

    public void setTag(final String tag) {
      this.tag = tag;
    }

    public CtorBean getInner() {
      return inner;
    }

    public void setInner(final CtorBean inner) {
      this.inner = inner;
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      private final OuterBuilt built = new OuterBuilt();

      public Builder tag(final String tag) {
        built.tag = tag == null ? null : "<" + tag + ">";
        return this;
      }

      public Builder inner(final CtorBean inner) {
        built.inner = inner;
        return this;
      }

      public OuterBuilt build() {
        return built;
      }
    }

    @Override
    public boolean equals(final Object o) {
      return o instanceof OuterBuilt b && Objects.equals(tag, b.tag) && Objects.equals(inner, b.inner);
    }

    @Override
    public int hashCode() {
      return Objects.hash(tag, inner);
    }

    @Override
    public String toString() {
      return "OuterBuilt{" + tag + "," + inner + "}";
    }
  }

  private static final List<Src> SAMPLES = List.of(
    new Src(1, 2, "  padded  ", 3L),
    new Src(0, null, null, 0L),
    new Src(-7, Integer.MIN_VALUE, "", Long.MAX_VALUE)
  );

  private static final List<NullableSrc> NULLABLE_SAMPLES = List.of(
    new NullableSrc(1, 2, " x ", 3L),
    new NullableSrc(null, null, null, null)
  );

  private final List<String> divergences = new ArrayList<>();

  @Test
  @DisplayName("a constructor-built and a builder-built bean take the composed leaf in both directions")
  void builderAndConstructorBeansTakeTheComposedLeaf() {
    assertForwardComposed(CtorBean.class);
    assertForwardComposed(BuiltBean.class);
  }

  @Test
  @DisplayName("the composed constructor and builder builds equal the array leaf, forward and backward")
  void composedBuildsEqualTheArrayLeaf() {
    for (final var sample : SAMPLES) {
      diff("Src→CtorBean", () -> Telescope.mapper(Src.class, CtorBean.class), sample);
      diff("Src→BuiltBean", () -> Telescope.mapper(Src.class, BuiltBean.class), sample);
      diff(
        "Src→CtorBean (CONSTRUCTOR hint)",
        () -> Telescope.mapper(Src.class, CtorBean.class, writeBean(CtorBean.class, CONSTRUCTOR)),
        sample
      );
      diff(
        "Src→CtorBean (SETTERS hint)",
        () -> Telescope.mapper(Src.class, CtorBean.class, writeBean(CtorBean.class, SETTERS)),
        sample
      );
      diff(
        "OuterRec→OuterBuilt",
        () -> Telescope.mapper(OuterRec.class, OuterBuilt.class),
        new OuterRec(" t ", sample)
      );
    }
    for (final var sample : NULLABLE_SAMPLES) {
      diff("NullableSrc→CtorBean", () -> Telescope.mapper(NullableSrc.class, CtorBean.class), sample);
      diff("NullableSrc→BuiltBean", () -> Telescope.mapper(NullableSrc.class, BuiltBean.class), sample);
    }
    final var ctorSample = new CtorBean(5, null, " c ", 6L);
    diff("CtorBean→BuiltBean", () -> Telescope.mapper(CtorBean.class, BuiltBean.class), ctorSample);
    diff(
      "CtorBean→BuiltBean (BUILDER hint)",
      () -> Telescope.mapper(CtorBean.class, BuiltBean.class, writeBean(BuiltBean.class, BUILDER)),
      ctorSample
    );
    diff(
      "OuterRec→OuterBuilt (null child)",
      () -> Telescope.mapper(OuterRec.class, OuterBuilt.class),
      new OuterRec("t", null)
    );
    // A transform that yields null for a primitive property: the writers pass its default.
    final var src = SAMPLES.getFirst();
    diffForward("Src→CtorBean (null into int)", () ->
      Telescope.mapperForward(
        Src.class,
        CtorBean.class,
        to(Src::str, CtorBean::getI, s -> (Integer) null, i -> "")
      ).forward(src)
    );
    diffForward("Src→BuiltBean (null into int)", () ->
      Telescope.mapperForward(
        Src.class,
        BuiltBean.class,
        to(Src::str, BuiltBean::getI, s -> (Integer) null, i -> "")
      ).forward(src)
    );
    assertTrue(divergences.isEmpty(), () -> String.join("\n", divergences));
  }

  @Test
  @DisplayName("a slot no writer takes is read by neither leaf, so a throwing getter behind it is not called")
  void aSlotNoWriterTakesIsReadByNeither() {
    final var src = new ThrowingSrc();
    src.setI(3);
    src.setStr("ab");
    diffForward("ThrowingSrc→ComputedBuilt", () ->
      Telescope.mapperForward(ThrowingSrc.class, ComputedBuilt.class).forward(src)
    );
    System.clearProperty(MhIso.DISABLE_PROPERTY);
    assertEquals("AB", Telescope.mapperForward(ThrowingSrc.class, ComputedBuilt.class).forward(src).getUpper());
    assertTrue(divergences.isEmpty(), () -> String.join("\n", divergences));
  }

  @Test
  @DisplayName("fusing a constructor-built child into a builder-built parent equals the proxy dispatch")
  void fusionThroughABuilderParentEqualsTheProxy() {
    for (final var sample : SAMPLES) {
      final var outer = new OuterRec(" t ", sample);
      System.clearProperty(MhIso.FUSION_DISABLE_PROPERTY);
      final var fused = Telescope.mapper(OuterRec.class, OuterBuilt.class).forward(outer);
      System.setProperty(MhIso.FUSION_DISABLE_PROPERTY, "true");
      final var proxied = Telescope.mapper(OuterRec.class, OuterBuilt.class).forward(outer);
      assertEquals(proxied, fused, "forward diverged for " + outer);

      System.clearProperty(MhIso.FUSION_DISABLE_PROPERTY);
      final var fusedBack = Telescope.mapper(OuterRec.class, OuterBuilt.class).backward(fused);
      System.setProperty(MhIso.FUSION_DISABLE_PROPERTY, "true");
      final var proxiedBack = Telescope.mapper(OuterRec.class, OuterBuilt.class).backward(fused);
      assertEquals(proxiedBack, fusedBack, "backward diverged for " + fused);
    }
  }

  @Test
  @DisplayName("the composed builds run the writer's normalisation, not the setters'")
  void composedBuildsNormaliseAsTheirWriter() {
    final var src = new Src(1, 2, "  padded  ", 3L);
    assertEquals("padded", Telescope.mapper(Src.class, CtorBean.class).forward(src).getStr());
    assertEquals("  PADDED  ", Telescope.mapper(Src.class, BuiltBean.class).forward(src).getStr());
    assertEquals(
      0,
      Telescope.mapper(NullableSrc.class, CtorBean.class).forward(new NullableSrc(null, 1, "", null)).getI()
    );
  }

  /**
   * The source here is built by no writer, so its backward direction goes to the array leaf, and
   * the leaf is a composed one only when the forward direction, which builds {@code tgt}, composed.
   */
  private static <B> void assertForwardComposed(final Class<B> tgt) {
    System.clearProperty(MhIso.DISABLE_PROPERTY);
    assertTrue(MhIso.isComposedLeaf(DeepMap.resolve(ReadOnlySrc.class, tgt, new MapStep[0])), tgt.getSimpleName());
  }

  private <A, B> void diff(final String shape, final Supplier<Mapper<A, B>> build, final A sample) {
    System.clearProperty(MhIso.DISABLE_PROPERTY);
    final var composed = outcome(() -> build.get().forward(sample));
    System.setProperty(MhIso.DISABLE_PROPERTY, "true");
    final var array = outcome(() -> build.get().forward(sample));
    if (!composed.equals(array)) divergences.add(shape + " forward: composed=" + composed + " array=" + array);
    if (composed.value() == null) return;
    @SuppressWarnings("unchecked")
    final B b = (B) composed.value();
    System.clearProperty(MhIso.DISABLE_PROPERTY);
    final var composedBack = outcome(() -> build.get().backward(b));
    System.setProperty(MhIso.DISABLE_PROPERTY, "true");
    final var arrayBack = outcome(() -> build.get().backward(b));
    if (!composedBack.equals(arrayBack)) {
      divergences.add(shape + " backward: composed=" + composedBack + " array=" + arrayBack);
    }
  }

  private void diffForward(final String shape, final Supplier<?> forward) {
    System.clearProperty(MhIso.DISABLE_PROPERTY);
    final var composed = outcome(forward);
    System.setProperty(MhIso.DISABLE_PROPERTY, "true");
    final var array = outcome(forward);
    if (!composed.equals(array)) divergences.add(shape + ": composed=" + composed + " array=" + array);
  }

  /** A value, or the class and message of what was thrown. */
  private record Outcome(Object value, String failure) {}

  private static Outcome outcome(final Supplier<?> op) {
    try {
      return new Outcome(op.get(), null);
    } catch (final RuntimeException e) {
      return new Outcome(null, e.getClass().getName() + ": " + e.getMessage());
    }
  }
}
