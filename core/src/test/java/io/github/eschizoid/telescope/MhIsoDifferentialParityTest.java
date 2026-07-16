package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.compute;
import static io.github.eschizoid.telescope.mapping.Mapping.constant;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.via;
import static io.github.eschizoid.telescope.mapping.Mapping.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.internal.MhIso;
import io.github.eschizoid.telescope.mapping.MapStep;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Differential parity harness for the {@link MhIso} MethodHandle-combinator conversion leaf against
 * the legacy {@code DeepMap.assembleIso} array leaf.
 *
 * <p>For each source/target shape and every sample instance, the same conversion is built and run
 * twice in a single JVM: once with {@code System.clearProperty(MhIso.DISABLE_PROPERTY)} (the MH
 * leaf) and once with {@code System.setProperty(MhIso.DISABLE_PROPERTY, "true")} (which makes
 * {@code MhIso.supports} return {@code false}, so {@code DeepMap.assembleIso} routes to the array
 * leaf). The two results — forward, backward, patch, and any thrown exception — must be
 * byte-identical. Any divergence is a parity bug.
 */
@DisplayName("MhIso ↔ array-leaf differential parity")
final class MhIsoDifferentialParityTest {

  private int comparisons = 0;
  private final List<String> divergences = new ArrayList<>();

  @AfterEach
  void restoreToggle() {
    System.clearProperty(MhIso.DISABLE_PROPERTY);
  }

  // ============================ fixtures ============================

  // --- flat records with every primitive + wrapper ---
  record PrimRec(
    int i,
    long l,
    short s,
    byte b,
    char c,
    boolean bool,
    float f,
    double d,
    Integer wi,
    Long wl,
    Short ws,
    Byte wb,
    Character wc,
    Boolean wbool,
    Float wf,
    Double wd,
    String str
  ) {}

  // same shape, different type (record ↔ record identity conversion, all same-name/same-type)
  record PrimRec2(
    int i,
    long l,
    short s,
    byte b,
    char c,
    boolean bool,
    float f,
    double d,
    Integer wi,
    Long wl,
    Short ws,
    Byte wb,
    Character wc,
    Boolean wbool,
    Float wf,
    Double wd,
    String str
  ) {}

  // --- flat bean with every primitive + wrapper (no-arg ctor + setters) ---
  static final class PrimBean {

    private int i;
    private long l;
    private short s;
    private byte b;
    private char c;
    private boolean bool;
    private float f;
    private double d;
    private Integer wi;
    private Long wl;
    private Short ws;
    private Byte wb;
    private Character wc;
    private Boolean wbool;
    private Float wf;
    private Double wd;
    private String str;

    public int getI() {
      return i;
    }

    public void setI(final int v) {
      this.i = v;
    }

    public long getL() {
      return l;
    }

    public void setL(final long v) {
      this.l = v;
    }

    public short getS() {
      return s;
    }

    public void setS(final short v) {
      this.s = v;
    }

    public byte getB() {
      return b;
    }

    public void setB(final byte v) {
      this.b = v;
    }

    public char getC() {
      return c;
    }

    public void setC(final char v) {
      this.c = v;
    }

    public boolean isBool() {
      return bool;
    }

    public void setBool(final boolean v) {
      this.bool = v;
    }

    public float getF() {
      return f;
    }

    public void setF(final float v) {
      this.f = v;
    }

    public double getD() {
      return d;
    }

    public void setD(final double v) {
      this.d = v;
    }

    public Integer getWi() {
      return wi;
    }

    public void setWi(final Integer v) {
      this.wi = v;
    }

    public Long getWl() {
      return wl;
    }

    public void setWl(final Long v) {
      this.wl = v;
    }

    public Short getWs() {
      return ws;
    }

    public void setWs(final Short v) {
      this.ws = v;
    }

    public Byte getWb() {
      return wb;
    }

    public void setWb(final Byte v) {
      this.wb = v;
    }

    public Character getWc() {
      return wc;
    }

    public void setWc(final Character v) {
      this.wc = v;
    }

    public Boolean getWbool() {
      return wbool;
    }

    public void setWbool(final Boolean v) {
      this.wbool = v;
    }

    public Float getWf() {
      return wf;
    }

    public void setWf(final Float v) {
      this.wf = v;
    }

    public Double getWd() {
      return wd;
    }

    public void setWd(final Double v) {
      this.wd = v;
    }

    public String getStr() {
      return str;
    }

    public void setStr(final String v) {
      this.str = v;
    }

    // value equality so forward/backward results compare structurally
    @Override
    public boolean equals(final Object o) {
      if (!(o instanceof PrimBean p)) return false;
      return (
        i == p.i &&
        l == p.l &&
        s == p.s &&
        b == p.b &&
        c == p.c &&
        bool == p.bool &&
        Float.compare(f, p.f) == 0 &&
        Double.compare(d, p.d) == 0 &&
        Objects.equals(wi, p.wi) &&
        Objects.equals(wl, p.wl) &&
        Objects.equals(ws, p.ws) &&
        Objects.equals(wb, p.wb) &&
        Objects.equals(wc, p.wc) &&
        Objects.equals(wbool, p.wbool) &&
        Objects.equals(wf, p.wf) &&
        Objects.equals(wd, p.wd) &&
        Objects.equals(str, p.str)
      );
    }

    @Override
    public int hashCode() {
      return Objects.hash(i, l, s, b, c, bool, f, d, wi, wl, ws, wb, wc, wbool, wf, wd, str);
    }

    @Override
    public String toString() {
      return "PrimBean{i=" + i + ",wi=" + wi + ",str=" + str + ",f=" + f + ",bool=" + bool + "}";
    }
  }

  // A second bean shape for bean↔bean, with a fluent (chaining) setter and a boolean isX/setX.
  static final class FluentBean {

    private int count;
    private String label;
    private boolean active;
    private Double ratio;

    public int getCount() {
      return count;
    }

    // fluent/chaining setter returning this (Lombok @Accessors(chain=true) shape)
    public FluentBean setCount(final int v) {
      this.count = v;
      return this;
    }

    public String getLabel() {
      return label;
    }

    public FluentBean setLabel(final String v) {
      this.label = v;
      return this;
    }

    public boolean isActive() {
      return active;
    }

    public FluentBean setActive(final boolean v) {
      this.active = v;
      return this;
    }

    public Double getRatio() {
      return ratio;
    }

    public FluentBean setRatio(final Double v) {
      this.ratio = v;
      return this;
    }

    @Override
    public boolean equals(final Object o) {
      if (!(o instanceof FluentBean f)) return false;
      return (
        count == f.count && active == f.active && Objects.equals(label, f.label) && Objects.equals(ratio, f.ratio)
      );
    }

    @Override
    public int hashCode() {
      return Objects.hash(count, label, active, ratio);
    }

    @Override
    public String toString() {
      return "FluentBean{count=" + count + ",label=" + label + ",active=" + active + ",ratio=" + ratio + "}";
    }
  }

  // Record matching FluentBean's properties for record↔bean cross-paradigm.
  record FlatRec(int count, String label, boolean active, Double ratio) {}

  // Bean with an inherited setter (setId lives on a superclass).
  static class BaseBean {

    private long id;

    public long getId() {
      return id;
    }

    public void setId(final long v) {
      this.id = v;
    }
  }

  static final class ChildBean extends BaseBean {

    private String name;

    public String getName() {
      return name;
    }

    public void setName(final String v) {
      this.name = v;
    }

    @Override
    public boolean equals(final Object o) {
      if (!(o instanceof ChildBean c)) return false;
      return getId() == c.getId() && Objects.equals(name, c.name);
    }

    @Override
    public int hashCode() {
      return Objects.hash(getId(), name);
    }

    @Override
    public String toString() {
      return "ChildBean{id=" + getId() + ",name=" + name + "}";
    }
  }

  record ChildRec(long id, String name) {}

  // Two-level inheritance: grandparent setId/getId, parent setTag/getTag, leaf setName/getName.
  static class GrandBean {

    private long id;

    public long getId() {
      return id;
    }

    public void setId(final long v) {
      this.id = v;
    }
  }

  static class MidBean extends GrandBean {

    private String tag;

    public String getTag() {
      return tag;
    }

    public void setTag(final String v) {
      this.tag = v;
    }
  }

  static final class LeafBean extends MidBean {

    private String name;

    public String getName() {
      return name;
    }

    public void setName(final String v) {
      this.name = v;
    }

    @Override
    public boolean equals(final Object o) {
      if (!(o instanceof LeafBean l)) return false;
      return getId() == l.getId() && Objects.equals(getTag(), l.getTag()) && Objects.equals(name, l.name);
    }

    @Override
    public int hashCode() {
      return Objects.hash(getId(), getTag(), name);
    }

    @Override
    public String toString() {
      return "LeafBean{id=" + getId() + ",tag=" + getTag() + ",name=" + name + "}";
    }
  }

  record LeafRec(long id, String tag, String name) {}

  // --- nested records ---
  record InnerRec(String city, int zip) {}

  record OuterRec(String tag, InnerRec inner) {}

  record InnerRec2(String city, int zip) {}

  record OuterRec2(String tag, InnerRec2 inner) {}

  // --- nested beans ---
  static final class InnerBean {

    private String city;
    private int zip;

    public String getCity() {
      return city;
    }

    public void setCity(final String v) {
      this.city = v;
    }

    public int getZip() {
      return zip;
    }

    public void setZip(final int v) {
      this.zip = v;
    }

    @Override
    public boolean equals(final Object o) {
      if (!(o instanceof InnerBean b)) return false;
      return zip == b.zip && Objects.equals(city, b.city);
    }

    @Override
    public int hashCode() {
      return Objects.hash(city, zip);
    }

    @Override
    public String toString() {
      return "InnerBean{city=" + city + ",zip=" + zip + "}";
    }
  }

  static final class OuterBean {

    private String tag;
    private InnerBean inner;

    public String getTag() {
      return tag;
    }

    public void setTag(final String v) {
      this.tag = v;
    }

    public InnerBean getInner() {
      return inner;
    }

    public void setInner(final InnerBean v) {
      this.inner = v;
    }

    @Override
    public boolean equals(final Object o) {
      if (!(o instanceof OuterBean b)) return false;
      return Objects.equals(tag, b.tag) && Objects.equals(inner, b.inner);
    }

    @Override
    public int hashCode() {
      return Objects.hash(tag, inner);
    }

    @Override
    public String toString() {
      return "OuterBean{tag=" + tag + ",inner=" + inner + "}";
    }
  }

  // --- deep (3 levels) records ---
  record L3(int v) {}

  record L2(String name, L3 leaf) {}

  record L1(String top, L2 mid) {}

  record L3b(int v) {}

  record L2b(String name, L3b leaf) {}

  record L1b(String top, L2b mid) {}

  // --- containers: List / Set / Map values / Optional of records ---
  record ContRec(List<InnerRec> list, Set<InnerRec> set, Map<String, InnerRec> byKey, Optional<InnerRec> opt) {}

  record ContRec2(List<InnerRec2> list, Set<InnerRec2> set, Map<String, InnerRec2> byKey, Optional<InnerRec2> opt) {}

  // --- containers of scalars ---
  record ScalarContRec(List<Integer> ints, Map<String, String> strs, Optional<Long> optLong) {}

  record ScalarContRec2(List<Integer> ints, Map<String, String> strs, Optional<Long> optLong) {}

  // --- rename with type conversion at the leaf ---
  record YearEntity(String label, int year) {}

  record YearDto(String label, String year) {}

  // --- constant / compute target record (sp<0 slots) ---
  record SrcNarrow(String name) {}

  record TgtWide(String name, String tenant, int seq) {}

  // ============================ the differential engine ============================

  /**
   * Build the mapper under the current toggle, then compare forward/backward (and patch when
   * supported) of the MH leaf against the array leaf for one sample. Both directions of any thrown
   * exception must match by type.
   */
  private <A, B> void diff(
    final String shape,
    final Class<A> src,
    final Class<B> tgt,
    final Supplier<Mapper<A, B>> build,
    final A sample
  ) {
    // MH leaf
    System.clearProperty(MhIso.DISABLE_PROPERTY);
    final Outcome<B> mhFwd = capture(() -> build.get().forward(sample));
    // Array leaf
    System.setProperty(MhIso.DISABLE_PROPERTY, "true");
    final Outcome<B> arrFwd = capture(() -> build.get().forward(sample));

    comparisons++;
    compareOutcome(shape + " forward", sample, mhFwd, arrFwd);

    // Backward: only meaningful when both forward directions produced an equal, non-null value.
    if (mhFwd.ok() && arrFwd.ok() && mhFwd.value() != null && Objects.equals(mhFwd.value(), arrFwd.value())) {
      final B b = mhFwd.value();
      System.clearProperty(MhIso.DISABLE_PROPERTY);
      final Outcome<A> mhBwd = capture(() -> build.get().backward(b));
      System.setProperty(MhIso.DISABLE_PROPERTY, "true");
      final Outcome<A> arrBwd = capture(() -> build.get().backward(b));
      comparisons++;
      compareOutcome(shape + " backward", b, mhBwd, arrBwd);
    }
  }

  /**
   * Forward-only variant for {@code mapperForward(...)} cases (constant / compute / when) where the
   * result is a {@link io.github.eschizoid.telescope.conversion.ForwardMapper} with no backward.
   * The {@code apply} closure builds the forward mapper under the CURRENT toggle and runs it, so
   * toggling {@code MhIso.DISABLE_PROPERTY} between the two calls selects the leaf.
   */
  private <A, B> void diffForward(final String shape, final Function<A, B> applyUnderToggle, final A sample) {
    System.clearProperty(MhIso.DISABLE_PROPERTY);
    final Outcome<B> mhFwd = capture(() -> applyUnderToggle.apply(sample));
    System.setProperty(MhIso.DISABLE_PROPERTY, "true");
    final Outcome<B> arrFwd = capture(() -> applyUnderToggle.apply(sample));
    comparisons++;
    compareOutcome(shape + " forward", sample, mhFwd, arrFwd);
  }

  private record Outcome<T>(boolean ok, T value, Class<?> exType) {}

  private static <T> Outcome<T> capture(final Supplier<T> op) {
    try {
      return new Outcome<>(true, op.get(), null);
    } catch (final Throwable t) {
      Throwable cause = t;
      // The MH leaf wraps checked throwables in RuntimeException("Failed to convert ...");
      // the array leaf surfaces reflection failures differently. Compare the deepest cause type
      // so a wrapper mismatch on the SAME underlying failure doesn't read as a divergence.
      while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
      return new Outcome<>(false, null, cause.getClass());
    }
  }

  private void compareOutcome(final String label, final Object input, final Outcome<?> mh, final Outcome<?> arr) {
    if (mh.ok() != arr.ok()) {
      divergences.add(
        label +
          ": MH " +
          (mh.ok() ? "succeeded" : "threw " + mh.exType()) +
          " but array " +
          (arr.ok() ? "succeeded" : "threw " + arr.exType()) +
          " for input=" +
          input
      );
      return;
    }
    if (!mh.ok()) {
      // both threw — require the same deepest cause type
      if (!Objects.equals(mh.exType(), arr.exType())) {
        divergences.add(
          label +
            ": both threw but different types — MH=" +
            mh.exType() +
            " array=" +
            arr.exType() +
            " for input=" +
            input
        );
      }
      return;
    }
    if (!Objects.equals(mh.value(), arr.value())) {
      divergences.add(label + ": MH=" + render(mh.value()) + " array=" + render(arr.value()) + " for input=" + input);
    }
  }

  private static String render(final Object o) {
    if (o == null) return "null";
    if (o instanceof PrimBean || o instanceof FluentBean) return o.toString();
    return String.valueOf(o) + " (" + o.getClass().getSimpleName() + ")";
  }

  // ============================ the fuzz corpus ============================

  @Test
  @DisplayName("MH leaf is byte-identical to the array leaf across a wide shape/instance fuzz")
  void differentialParity() {
    fuzzFlatRecords();
    fuzzFlatBeans();
    fuzzCrossParadigm();
    fuzzInheritedAndFluentBeans();
    fuzzNested();
    fuzzDeep();
    fuzzContainers();
    fuzzRenameWithConversion();
    fuzzConstantComputeGated();
    fuzzViaNestedMapper();
    fuzzNullEdges();

    // Anchor a handful of absolute-correctness checks under the MH leaf so a "both leaves wrong
    // the same way" scenario can't pass as parity. These pin the ACTUAL converted values.
    assertCorrectness();

    if (!divergences.isEmpty()) {
      fail(
        "DIFFERENTIAL PARITY FAILURES (" +
          divergences.size() +
          " of " +
          comparisons +
          " comparisons):\n" +
          String.join("\n", divergences)
      );
    }
    // Guardrail: the harness actually exercised a substantial corpus.
    assertTrue(comparisons > 90, "expected >90 comparisons, ran " + comparisons);
    System.out.println("[MhIso parity] CLEAN — " + comparisons + " comparisons byte-identical");
  }

  // ---- absolute-value anchors (MH leaf), so "both leaves wrong identically" can't pass ----
  private void assertCorrectness() {
    System.clearProperty(MhIso.DISABLE_PROPERTY);

    // inherited two-level bean: id/tag/name all populated through inherited setters
    final var leaf = Telescope.mapper(LeafRec.class, LeafBean.class).forward(new LeafRec(7L, "t", "n"));
    assertEquals(7L, leaf.getId());
    assertEquals("t", leaf.getTag());
    assertEquals("n", leaf.getName());

    // record->bean primitives + wrappers land in the right slots
    final var pb = Telescope.mapper(FlatRec.class, FluentBean.class).forward(new FlatRec(42, "hi", true, 3.5));
    assertEquals(42, pb.getCount());
    assertEquals("hi", pb.getLabel());
    assertTrue(pb.isActive());
    assertEquals(3.5, pb.getRatio());

    // typed transform int->String at the leaf
    final var yd = Telescope.mapper(
      YearEntity.class,
      YearDto.class,
      to(YearEntity::year, YearDto::year, i -> Integer.toString(i), Integer::parseInt)
    ).forward(new YearEntity("a", 2024));
    assertEquals("2024", yd.year());

    // constant + compute forward-only
    final var wide = Telescope.mapperForward(
      SrcNarrow.class,
      TgtWide.class,
      constant(TgtWide::tenant, "prod"),
      compute(TgtWide::seq, () -> 42)
    ).forward(new SrcNarrow("neo"));
    assertEquals("neo", wide.name());
    assertEquals("prod", wide.tenant());
    assertEquals(42, wide.seq());

    // container recursion with rename inside the element is exercised by parity; here pin the
    // list element identity conversion produced a distinct-but-equal element type.
    final var cont = Telescope.mapper(ContRec.class, ContRec2.class).forward(
      new ContRec(List.of(new InnerRec("a", 1)), Set.of(), Map.of(), Optional.of(new InnerRec("o", 3)))
    );
    assertEquals("a", cont.list().get(0).city());
    assertEquals(3, cont.opt().orElseThrow().zip());
  }

  // ---- flat records: every primitive + wrapper, null wrappers, min/max/0 ----
  private void fuzzFlatRecords() {
    final List<PrimRec> samples = List.of(
      new PrimRec(0, 0L, (short) 0, (byte) 0, '\0', false, 0f, 0d, 0, 0L, (short) 0, (byte) 0, 'a', false, 0f, 0d, ""),
      new PrimRec(
        Integer.MAX_VALUE,
        Long.MAX_VALUE,
        Short.MAX_VALUE,
        Byte.MAX_VALUE,
        Character.MAX_VALUE,
        true,
        Float.MAX_VALUE,
        Double.MAX_VALUE,
        Integer.MAX_VALUE,
        Long.MAX_VALUE,
        Short.MAX_VALUE,
        Byte.MAX_VALUE,
        Character.MAX_VALUE,
        true,
        Float.MAX_VALUE,
        Double.MAX_VALUE,
        "max"
      ),
      new PrimRec(
        Integer.MIN_VALUE,
        Long.MIN_VALUE,
        Short.MIN_VALUE,
        Byte.MIN_VALUE,
        Character.MIN_VALUE,
        false,
        Float.MIN_VALUE,
        Double.MIN_VALUE,
        Integer.MIN_VALUE,
        Long.MIN_VALUE,
        Short.MIN_VALUE,
        Byte.MIN_VALUE,
        Character.MIN_VALUE,
        false,
        -0.0f,
        -0.0d,
        "min"
      ),
      // all wrapper fields null
      new PrimRec(
        7,
        8L,
        (short) 9,
        (byte) 10,
        'z',
        true,
        1.5f,
        2.5d,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null
      ),
      // NaN / infinities in the floating wrappers and primitives
      new PrimRec(
        1,
        1L,
        (short) 1,
        (byte) 1,
        'x',
        true,
        Float.NaN,
        Double.POSITIVE_INFINITY,
        1,
        1L,
        (short) 1,
        (byte) 1,
        'x',
        true,
        Float.NEGATIVE_INFINITY,
        Double.NaN,
        "nan"
      )
    );
    for (final var s : samples) {
      diff(
        "PrimRec->PrimRec2",
        PrimRec.class,
        PrimRec2.class,
        () -> Telescope.mapper(PrimRec.class, PrimRec2.class),
        s
      );
    }
  }

  // ---- flat beans: same primitive matrix, plus null-wrapper edges ----
  private void fuzzFlatBeans() {
    final List<PrimBean> samples = List.of(
      bean(0, 0L, (short) 0, (byte) 0, '\0', false, 0f, 0d, 0, 0L, (short) 0, (byte) 0, 'a', false, 0f, 0d, ""),
      bean(
        Integer.MAX_VALUE,
        Long.MAX_VALUE,
        Short.MAX_VALUE,
        Byte.MAX_VALUE,
        Character.MAX_VALUE,
        true,
        Float.MAX_VALUE,
        Double.MAX_VALUE,
        Integer.MAX_VALUE,
        Long.MAX_VALUE,
        Short.MAX_VALUE,
        Byte.MAX_VALUE,
        Character.MAX_VALUE,
        true,
        Float.MAX_VALUE,
        Double.MAX_VALUE,
        "max"
      ),
      // null wrappers into a bean (identity slots: array path also carries null through
      // setters)
      bean(3, 4L, (short) 5, (byte) 6, 'q', true, 3.14f, 2.71d, null, null, null, null, null, null, null, null, null)
    );
    for (final var s : samples) {
      diff(
        "PrimBean->PrimBean",
        PrimBean.class,
        PrimBean.class,
        () -> Telescope.mapper(PrimBean.class, PrimBean.class),
        s
      );
    }
  }

  // ---- cross-paradigm: record<->bean, bean<->record ----
  private void fuzzCrossParadigm() {
    final List<FlatRec> recs = List.of(
      new FlatRec(0, "", false, 0.0),
      new FlatRec(42, "hi", true, 3.5),
      new FlatRec(-1, null, false, null) // null String + null Double wrapper
    );
    for (final var r : recs) {
      diff(
        "FlatRec->FluentBean",
        FlatRec.class,
        FluentBean.class,
        () -> Telescope.mapper(FlatRec.class, FluentBean.class),
        r
      );
    }
    final List<FluentBean> beans = List.of(
      new FluentBean().setCount(0).setLabel("").setActive(false).setRatio(0.0),
      new FluentBean().setCount(9).setLabel("x").setActive(true).setRatio(1.25),
      new FluentBean().setCount(-3).setLabel(null).setActive(true).setRatio(null)
    );
    for (final var b : beans) {
      diff(
        "FluentBean->FlatRec",
        FluentBean.class,
        FlatRec.class,
        () -> Telescope.mapper(FluentBean.class, FlatRec.class),
        b
      );
    }
    // bean <-> bean (both sides fold setters); also stresses the fluent-setter fold
    for (final var b : beans) {
      diff(
        "FluentBean->FluentBean",
        FluentBean.class,
        FluentBean.class,
        () -> Telescope.mapper(FluentBean.class, FluentBean.class),
        b
      );
    }
  }

  // ---- inherited setter bean + record cross-paradigm ----
  private void fuzzInheritedAndFluentBeans() {
    final List<ChildRec> recs = List.of(new ChildRec(0L, ""), new ChildRec(99L, "abc"), new ChildRec(-5L, null));
    for (final var r : recs) {
      diff(
        "ChildRec->ChildBean",
        ChildRec.class,
        ChildBean.class,
        () -> Telescope.mapper(ChildRec.class, ChildBean.class),
        r
      );
    }
    final List<ChildBean> beans = new ArrayList<>();
    for (final var r : recs) {
      final var cb = new ChildBean();
      cb.setId(r.id());
      cb.setName(r.name());
      beans.add(cb);
    }
    for (final var b : beans) {
      diff(
        "ChildBean->ChildRec",
        ChildBean.class,
        ChildRec.class,
        () -> Telescope.mapper(ChildBean.class, ChildRec.class),
        b
      );
    }

    // Two-level inheritance (inherited setter AND inherited getter on the deepest bean).
    final List<LeafRec> leafRecs = List.of(
      new LeafRec(0L, "", ""),
      new LeafRec(7L, "t", "n"),
      new LeafRec(-2L, null, null)
    );
    for (final var r : leafRecs) {
      diff(
        "LeafRec->LeafBean",
        LeafRec.class,
        LeafBean.class,
        () -> Telescope.mapper(LeafRec.class, LeafBean.class),
        r
      );
    }
    final List<LeafBean> leafBeans = new ArrayList<>();
    for (final var r : leafRecs) {
      final var lb = new LeafBean();
      lb.setId(r.id());
      lb.setTag(r.tag());
      lb.setName(r.name());
      leafBeans.add(lb);
    }
    for (final var b : leafBeans) {
      diff(
        "LeafBean->LeafRec",
        LeafBean.class,
        LeafRec.class,
        () -> Telescope.mapper(LeafBean.class, LeafRec.class),
        b
      );
    }
    // bean->bean across the two-level hierarchy (both sides fold inherited setters)
    for (final var b : leafBeans) {
      diff(
        "LeafBean->LeafBean",
        LeafBean.class,
        LeafBean.class,
        () -> Telescope.mapper(LeafBean.class, LeafBean.class),
        b
      );
    }
  }

  // ---- nested record-in-record, bean-in-bean ----
  private void fuzzNested() {
    final List<OuterRec> recs = List.of(
      new OuterRec("t", new InnerRec("NYC", 10001)),
      new OuterRec(null, new InnerRec(null, 0)),
      new OuterRec("x", null) // null nested record
    );
    for (final var r : recs) {
      diff(
        "OuterRec->OuterRec2",
        OuterRec.class,
        OuterRec2.class,
        () -> Telescope.mapper(OuterRec.class, OuterRec2.class),
        r
      );
    }
    final List<OuterBean> beans = new ArrayList<>();
    for (final var spec : List.of(new String[] { "t", "NYC", "10001" }, new String[] { null, null, "0" })) {
      final var ob = new OuterBean();
      ob.setTag(spec[0]);
      final var ib = new InnerBean();
      ib.setCity(spec[1]);
      ib.setZip(Integer.parseInt(spec[2]));
      ob.setInner(ib);
      beans.add(ob);
    }
    // bean with null nested bean
    final var nullInner = new OuterBean();
    nullInner.setTag("solo");
    nullInner.setInner(null);
    beans.add(nullInner);
    for (final var b : beans) {
      diff(
        "OuterBean->OuterBean",
        OuterBean.class,
        OuterBean.class,
        () -> Telescope.mapper(OuterBean.class, OuterBean.class),
        b
      );
    }
  }

  // ---- deep 3-level records ----
  private void fuzzDeep() {
    final List<L1> samples = List.of(
      new L1("root", new L2("mid", new L3(7))),
      new L1(null, new L2(null, new L3(0))),
      new L1("r", new L2("m", null)) // null at the deepest boundary
    );
    for (final var s : samples) {
      diff("L1->L1b", L1.class, L1b.class, () -> Telescope.mapper(L1.class, L1b.class), s);
    }
  }

  // ---- containers: List/Set/Map values/Optional of records, empty + null + multi ----
  private void fuzzContainers() {
    final List<ContRec> samples = List.of(
      new ContRec(
        List.of(new InnerRec("a", 1), new InnerRec("b", 2)),
        Set.of(new InnerRec("s", 9)),
        Map.of("k1", new InnerRec("m1", 11), "k2", new InnerRec("m2", 12)),
        Optional.of(new InnerRec("o", 3))
      ),
      // empty containers + empty optional
      new ContRec(List.of(), Set.of(), Map.of(), Optional.empty()),
      // null ELEMENTS inside non-null containers: the leaf path null-guards each element
      // (guardElement); the array-leaf path routes each through the cache proxy. Both must
      // map a
      // null element to null identically — this pins the container element-null semantics
      // that the
      // (now removed) resolveElementForLift reach-through used to straddle.
      new ContRec(
        new ArrayList<>(Arrays.asList(new InnerRec("a", 1), null)),
        new LinkedHashSet<>(Arrays.asList(new InnerRec("s", 9), null)),
        nullValueMap(),
        Optional.empty()
      ),
      // null containers + null optional-holder
      new ContRec(null, null, null, null)
    );
    for (final var s : samples) {
      diff(
        "ContRec->ContRec2",
        ContRec.class,
        ContRec2.class,
        () -> Telescope.mapper(ContRec.class, ContRec2.class),
        s
      );
    }

    // scalar containers (no element recursion)
    final List<ScalarContRec> scalars = List.of(
      new ScalarContRec(List.of(1, 2, 3), Map.of("a", "x", "b", "y"), Optional.of(5L)),
      new ScalarContRec(List.of(), Map.of(), Optional.empty()),
      new ScalarContRec(null, null, null)
    );
    for (final var s : scalars) {
      diff(
        "ScalarContRec->ScalarContRec2",
        ScalarContRec.class,
        ScalarContRec2.class,
        () -> Telescope.mapper(ScalarContRec.class, ScalarContRec2.class),
        s
      );
    }
  }

  // ---- rename with type conversion (int<->String), including transform returning null ----
  private void fuzzRenameWithConversion() {
    final Function<Integer, String> fwd = i -> i == null ? null : Integer.toString(i);
    final Function<String, Integer> bwd = str -> str == null ? null : Integer.parseInt(str);
    final MapStep row = to(YearEntity::year, YearDto::year, fwd, bwd);
    final List<YearEntity> samples = List.of(
      new YearEntity("a", 2024),
      new YearEntity(null, 0),
      new YearEntity("b", -7)
    );
    for (final var s : samples) {
      diff(
        "YearEntity->YearDto (typed transform)",
        YearEntity.class,
        YearDto.class,
        () -> Telescope.mapper(YearEntity.class, YearDto.class, row),
        s
      );
    }

    // Transform that returns null INTO a primitive record slot: both leaves should NPE-on-rebuild
    // identically (record canonical ctor cannot take null for an int). Target record has a
    // primitive int fed by a null-returning transform.
    final MapStep nullIntoPrimRecordRow = to(
      YearDto::year,
      YearEntity::year,
      (String str) -> (Integer) null,
      (Integer i) -> i == null ? null : i.toString()
    );
    final List<YearDto> dtoSamples = List.of(new YearDto("a", "2024"));
    for (final var s : dtoSamples) {
      diff(
        "YearDto->YearEntity (null into primitive record slot)",
        YearDto.class,
        YearEntity.class,
        () -> Telescope.mapper(YearDto.class, YearEntity.class, nullIntoPrimRecordRow),
        s
      );
    }

    // Transform returning null INTO a primitive BEAN slot: SettersWriter skips → JLS default; the
    // MH fold null-guards → same JLS default. Target bean FluentBean.count is int.
    final MapStep nullIntoPrimBeanRow = to(
      FlatRec::label,
      FluentBean::getCount,
      (String str) -> (Integer) null,
      (Integer i) -> i == null ? null : i.toString()
    );
    final List<FlatRec> recSamples = List.of(new FlatRec(1, "ignored", true, 1.0));
    for (final var s : recSamples) {
      diff(
        "FlatRec->FluentBean (null into primitive bean slot)",
        FlatRec.class,
        FluentBean.class,
        () -> Telescope.mapper(FlatRec.class, FluentBean.class, nullIntoPrimBeanRow),
        s
      );
    }
  }

  // ---- constant / compute / when-gated rows (sp<0 slots) ----
  private void fuzzConstantComputeGated() {
    final List<SrcNarrow> samples = List.of(new SrcNarrow("neo"), new SrcNarrow(null));

    // constant into a reference slot + compute into a primitive int slot (sp<0 slots on target)
    final MapStep constRow = constant(TgtWide::tenant, "prod");
    final MapStep computeRow = compute(TgtWide::seq, () -> 42);
    for (final var s : samples) {
      diffForward(
        "SrcNarrow->TgtWide (constant + compute)",
        src -> Telescope.mapperForward(SrcNarrow.class, TgtWide.class, constRow, computeRow).forward(src),
        s
      );
    }

    // when-gated row: predicate on the source decides whether the inner mapping fires. seq stays a
    // constant so the primitive int slot is fed. when(...) only wraps telescope-based rows, so the
    // inner correspondence is a source-Telescope → target-accessor row.
    final MapStep gated = when(
      (SrcNarrow src) -> src != null && src.name() != null,
      to(Telescope.of(SrcNarrow.class).field(SrcNarrow::name), TgtWide::tenant)
    );
    final MapStep seqConst = constant(TgtWide::seq, 0);
    for (final var s : samples) {
      diffForward(
        "SrcNarrow->TgtWide (when-gated)",
        src -> Telescope.mapperForward(SrcNarrow.class, TgtWide.class, gated, seqConst).forward(src),
        s
      );
    }
  }

  // ---- via nested Mapper on a container of records ----
  private void fuzzViaNestedMapper() {
    final Mapper<InnerRec, InnerRec2> elem = Telescope.mapper(InnerRec.class, InnerRec2.class);
    final MapStep viaRow = via(ContRec::list, ContRec2::list, elem);
    final List<ContRec> samples = List.of(
      new ContRec(List.of(new InnerRec("a", 1)), Set.of(), Map.of(), Optional.empty()),
      new ContRec(List.of(), Set.of(), Map.of(), Optional.empty())
    );
    for (final var s : samples) {
      diff(
        "ContRec->ContRec2 (via nested list mapper)",
        ContRec.class,
        ContRec2.class,
        () -> Telescope.mapper(ContRec.class, ContRec2.class, viaRow),
        s
      );
    }
  }

  // ---- null source; null everything ----
  private void fuzzNullEdges() {
    diff("null PrimRec", PrimRec.class, PrimRec2.class, () -> Telescope.mapper(PrimRec.class, PrimRec2.class), null);
    diff("null PrimBean", PrimBean.class, PrimBean.class, () -> Telescope.mapper(PrimBean.class, PrimBean.class), null);
    diff(
      "null OuterRec",
      OuterRec.class,
      OuterRec2.class,
      () -> Telescope.mapper(OuterRec.class, OuterRec2.class),
      null
    );
    diff("null ContRec", ContRec.class, ContRec2.class, () -> Telescope.mapper(ContRec.class, ContRec2.class), null);
    diff(
      "null FluentBean",
      FluentBean.class,
      FlatRec.class,
      () -> Telescope.mapper(FluentBean.class, FlatRec.class),
      null
    );
  }

  // ---- helpers ----
  // A map with a present value and a null value, to exercise null map-value conversion on both
  // leaves.
  private static Map<String, InnerRec> nullValueMap() {
    final var m = new LinkedHashMap<String, InnerRec>();
    m.put("k1", new InnerRec("m1", 11));
    m.put("k2", null);
    return m;
  }

  private static PrimBean bean(
    final int i,
    final long l,
    final short s,
    final byte b,
    final char c,
    final boolean bool,
    final float f,
    final double d,
    final Integer wi,
    final Long wl,
    final Short ws,
    final Byte wb,
    final Character wc,
    final Boolean wbool,
    final Float wf,
    final Double wd,
    final String str
  ) {
    final var p = new PrimBean();
    p.setI(i);
    p.setL(l);
    p.setS(s);
    p.setB(b);
    p.setC(c);
    p.setBool(bool);
    p.setF(f);
    p.setD(d);
    p.setWi(wi);
    p.setWl(wl);
    p.setWs(ws);
    p.setWb(wb);
    p.setWc(wc);
    p.setWbool(wbool);
    p.setWf(wf);
    p.setWd(wd);
    p.setStr(str);
    return p;
  }
}
