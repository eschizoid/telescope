package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The copy of a primitive property from one bean into another that passes the value from getter to
 * setter unboxed: on the JVM through {@code LambdaMetafactory}-built functions of the JDK's
 * primitive shapes, and inside a native image through one composed {@code MethodHandle} closure,
 * which the {@code imageTest} run of this suite exercises.
 */
class BeansCopierTest {

  @Test
  @DisplayName("an int, a long and a double property are copied from one bean into another")
  void copiesThePrimitiveShapes() {
    final var from = new Measured();
    from.setCount(1_000);
    from.setTotal(5_000_000_000L);
    from.setRatio(0.25);
    final var into = new Measured();
    for (final var name : new String[] { "count", "total", "ratio" }) {
      final var copier = Beans.capturedCopier(Measured.class, Measured.class, name);
      assertNotNull(copier, name);
      copier.accept(into, from);
    }
    assertEquals(1_000, into.getCount());
    assertEquals(5_000_000_000L, into.getTotal());
    assertEquals(0.25, into.getRatio());
  }

  @Test
  @DisplayName(
    "a reference property has no copier, and a primitive with no JDK shape has one only in an image, whose closure" +
      " takes any primitive"
  )
  void othersHaveNone() {
    assertNull(Beans.capturedCopier(Measured.class, Measured.class, "label"));
    final var open = Beans.capturedCopier(Measured.class, Measured.class, "open");
    if (!NativeImage.IN_IMAGE) {
      assertNull(open);
      return;
    }
    final var from = new Measured();
    from.setOpen(true);
    final var into = new Measured();
    open.accept(into, from);
    assertTrue(into.isOpen());
  }

  @Test
  @DisplayName("on the JVM the copy is a lambda of telescope's own over spun accessors, never a MethodHandle closure")
  void theJvmCopyIsSpun() {
    final var copier = Beans.capturedCopier(Measured.class, Measured.class, "count");
    final var name = copier.getClass().getName();
    if (NativeImage.IN_IMAGE) assertTrue(name.startsWith(MhAccessors.class.getName()), name);
    else assertTrue(name.startsWith(Beans.class.getName()), name);
  }

  /** A bean of primitive and reference properties. */
  public static final class Measured {

    private int count;
    private long total;
    private double ratio;
    private String label;
    private boolean open;

    public int getCount() {
      return count;
    }

    public void setCount(final int count) {
      this.count = count;
    }

    public long getTotal() {
      return total;
    }

    public void setTotal(final long total) {
      this.total = total;
    }

    public double getRatio() {
      return ratio;
    }

    public void setRatio(final double ratio) {
      this.ratio = ratio;
    }

    public String getLabel() {
      return label;
    }

    public void setLabel(final String label) {
      this.label = label;
    }

    public boolean isOpen() {
      return open;
    }

    public void setOpen(final boolean open) {
      this.open = open;
    }
  }
}
