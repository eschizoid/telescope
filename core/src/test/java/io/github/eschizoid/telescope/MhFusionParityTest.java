package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.MhIso;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Isolates full-tree fusion. {@link MhIsoDifferentialParityTest} pins the composed handle against
 * the whole array leaf; this harness flips <em>only</em> whether a nested-pair slot is fused —
 * {@link MhIso#FUSION_DISABLE_PROPERTY} routes the nested conversion through {@code Iso.to} over
 * the same composed leaf instead of inlining its raw handle — and asserts the fused handle is
 * byte-identical to the non-fused (proxy-dispatched) handle over that identical leaf, forward and
 * backward, across single-level and multi-level nesting with null-nested and null-root samples,
 * record and bean.
 */
@DisplayName("MhIso full-tree fusion ↔ proxy-dispatch parity (same leaf)")
final class MhFusionParityTest {

  @AfterEach
  void restoreToggle() {
    System.clearProperty(MhIso.FUSION_DISABLE_PROPERTY);
  }

  // Single-level nested record pair (distinct-but-equal element types).
  record Inner(String city, int zip) {}

  record Inner2(String city, int zip) {}

  record Outer(String tag, Inner inner) {}

  record Outer2(String tag, Inner2 inner) {}

  // Three-level nesting to exercise bottom-up fusion of a fused sub-leaf.
  record L3(int v) {}

  record L2(String name, L3 leaf) {}

  record L1(String top, L2 mid) {}

  record L3b(int v) {}

  record L2b(String name, L3b leaf) {}

  record L1b(String top, L2b mid) {}

  // Nested bean pair.
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
      return o instanceof InnerBean b && zip == b.zip && Objects.equals(city, b.city);
    }

    @Override
    public int hashCode() {
      return Objects.hash(city, zip);
    }

    @Override
    public String toString() {
      return "InnerBean{" + city + "," + zip + "}";
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
      return o instanceof OuterBean b && Objects.equals(tag, b.tag) && Objects.equals(inner, b.inner);
    }

    @Override
    public int hashCode() {
      return Objects.hash(tag, inner);
    }

    @Override
    public String toString() {
      return "OuterBean{" + tag + "," + inner + "}";
    }
  }

  @Test
  @DisplayName("single-level nested record: fused == proxy-dispatched over the same leaf")
  void nestedRecordFusion() {
    for (final Outer sample : List.of(new Outer("t", new Inner("NYC", 10001)), new Outer(null, new Inner(null, 0)))) {
      assertFusionParity(Outer.class, Outer2.class, sample);
    }
    assertFusionParity(Outer.class, Outer2.class, new Outer("solo", null)); // null nested
    assertFusionParity(Outer.class, Outer2.class, null); // null root
  }

  @Test
  @DisplayName("three-level nested record: bottom-up fusion == proxy-dispatched")
  void deepNestedRecordFusion() {
    for (final L1 sample : List.of(new L1("root", new L2("mid", new L3(7))), new L1(null, new L2(null, new L3(0))))) {
      assertFusionParity(L1.class, L1b.class, sample);
    }
    assertFusionParity(L1.class, L1b.class, new L1("r", new L2("m", null))); // null at the deepest boundary
  }

  @Test
  @DisplayName("nested bean: fused == proxy-dispatched over the same leaf")
  void nestedBeanFusion() {
    assertFusionParity(OuterBean.class, OuterBean.class, outerBean("t", "NYC", 10001));
    assertFusionParity(OuterBean.class, OuterBean.class, outerBean("solo", null, 0)); // null nested bean
    assertFusionParity(OuterBean.class, OuterBean.class, null);
  }

  /**
   * Build the mapper with fusion enabled (cleared) and disabled (set) and assert forward — and, on
   * a non-null match, backward — are byte-identical. Rebuilds per toggle so the flag is read at
   * construction time.
   */
  private <A, B> void assertFusionParity(final Class<A> src, final Class<B> tgt, final A sample) {
    System.clearProperty(MhIso.FUSION_DISABLE_PROPERTY);
    final B fused = Telescope.mapper(src, tgt).forward(sample);
    System.setProperty(MhIso.FUSION_DISABLE_PROPERTY, "true");
    final B proxied = Telescope.mapper(src, tgt).forward(sample);
    assertEquals(proxied, fused, "forward diverged for " + sample);

    if (fused != null && Objects.equals(fused, proxied)) {
      System.clearProperty(MhIso.FUSION_DISABLE_PROPERTY);
      final A fusedBack = Telescope.mapper(src, tgt).backward(fused);
      System.setProperty(MhIso.FUSION_DISABLE_PROPERTY, "true");
      final A proxiedBack = Telescope.mapper(src, tgt).backward(fused);
      assertEquals(proxiedBack, fusedBack, "backward diverged for " + fused);
    }
  }

  // ---- exception-labelling: a nested conversion failure must name the nested pair, not the root
  // ----

  static final class Boom extends Exception {

    private static final long serialVersionUID = 1L;

    Boom(final String m) {
      super(m);
    }
  }

  // Plain source bean; the TARGET setter throws a CHECKED exception on a sentinel value. A checked
  // throwable is the only shape whose wrapper label differs between fused (raw handle) and proxy
  // (Leaf.forward wrap) dispatch, since rethrow passes RuntimeException/Error through unchanged.
  static final class ThrowInner {

    private int v;

    public int getV() {
      return v;
    }

    public void setV(final int v) {
      this.v = v;
    }
  }

  static final class ThrowInner2 {

    private int v;

    public int getV() {
      return v;
    }

    public void setV(final int v) throws Boom {
      if (v == 42) throw new Boom("boom@" + v);
      this.v = v;
    }
  }

  record ThrowOuter(ThrowInner inner) {}

  record ThrowOuter2(ThrowInner2 inner) {}

  @Test
  @DisplayName("fused nested-conversion failure is labelled with the nested pair, not the root pair")
  void nestedFailureLabelledWithNestedClasses() {
    final var throwing = new ThrowInner();
    throwing.setV(42); // source is fine; the target setter throws on 42 during conversion

    System.clearProperty(MhIso.FUSION_DISABLE_PROPERTY); // fusion ON
    final var fused = Telescope.mapper(ThrowOuter.class, ThrowOuter2.class);
    final var ex = assertThrows(RuntimeException.class, () -> fused.forward(new ThrowOuter(throwing)));
    assertTrue(
      String.valueOf(ex.getMessage()).contains("ThrowInner"),
      "fused failure should name the nested pair; got: " + ex.getMessage()
    );

    // Same label under the non-fused proxy path — the fix keeps them identical.
    System.setProperty(MhIso.FUSION_DISABLE_PROPERTY, "true");
    final var proxied = Telescope.mapper(ThrowOuter.class, ThrowOuter2.class);
    final var ex2 = assertThrows(RuntimeException.class, () -> proxied.forward(new ThrowOuter(throwing)));
    assertTrue(
      String.valueOf(ex2.getMessage()).contains("ThrowInner"),
      "proxy failure should name the nested pair too"
    );
  }

  private static OuterBean outerBean(final String tag, final String city, final int zip) {
    final var ob = new OuterBean();
    ob.setTag(tag);
    if (city != null) {
      final var ib = new InnerBean();
      ib.setCity(city);
      ib.setZip(zip);
      ob.setInner(ib);
    }
    return ob;
  }
}
