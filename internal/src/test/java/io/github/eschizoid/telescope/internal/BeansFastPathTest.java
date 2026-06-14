package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code Beans.fastPathForward(srcClass, tgtClass)} — the Tier-A runtime fast-path for
 * bean → bean same-shape mapping. Eligibility: both non-record, same-named getters with EXACTLY
 * matching types, all flat scalars, target has a public no-arg constructor.
 */
class BeansFastPathTest {

  public static class Src {

    private long id;
    private String name;
    private int age;
    private boolean active;

    public Src() {}

    public Src(final long id, final String name, final int age, final boolean active) {
      this.id = id;
      this.name = name;
      this.age = age;
      this.active = active;
    }

    public long getId() {
      return id;
    }

    public void setId(final long id) {
      this.id = id;
    }

    public String getName() {
      return name;
    }

    public void setName(final String name) {
      this.name = name;
    }

    public int getAge() {
      return age;
    }

    public void setAge(final int age) {
      this.age = age;
    }

    public boolean isActive() {
      return active;
    }

    public void setActive(final boolean active) {
      this.active = active;
    }
  }

  public static class Dst {

    private long id;
    private String name;
    private int age;
    private boolean active;

    public Dst() {}

    public long getId() {
      return id;
    }

    public void setId(final long id) {
      this.id = id;
    }

    public String getName() {
      return name;
    }

    public void setName(final String name) {
      this.name = name;
    }

    public int getAge() {
      return age;
    }

    public void setAge(final int age) {
      this.age = age;
    }

    public boolean isActive() {
      return active;
    }

    public void setActive(final boolean active) {
      this.active = active;
    }
  }

  @Nested
  @DisplayName("Eligible — both beans, same flat scalar shape, target has no-arg ctor")
  class Eligible {

    @Test
    @DisplayName("forward maps every field via getter+setter; result is a fresh instance, not aliasing")
    void forwardCopies() {
      final var fn = Beans.fastPathForward(Src.class, Dst.class);
      assertNotNull(fn);

      final var src = new Src(42L, "Alice", 30, true);
      final var dst = fn.apply(src);

      assertNotSame(src, dst, "fast-path returns a fresh target, not the source");
      assertEquals(42L, dst.getId());
      assertEquals("Alice", dst.getName());
      assertEquals(30, dst.getAge());
      assertEquals(true, dst.isActive());
    }

    @Test
    @DisplayName("null source short-circuits to null (no NPE on the no-arg ctor invocation)")
    void nullSource() {
      final var fn = Beans.fastPathForward(Src.class, Dst.class);
      assertNotNull(fn);
      assertNull(fn.apply(null));
    }

    @Test
    @DisplayName("cache returns same Function on repeated lookups for the same pair")
    void cacheReturnsSameFunction() {
      final var first = Beans.fastPathForward(Src.class, Dst.class);
      final var second = Beans.fastPathForward(Src.class, Dst.class);
      assertSame(first, second, "fast-path Function is cached per (src, tgt) pair");
    }
  }

  @Nested
  @DisplayName("Not eligible — falls back to slow path (returns null)")
  class NotEligible {

    public static class NoNoArgCtor {

      public final String name;

      public NoNoArgCtor(final String name) {
        this.name = name;
      }

      public String getName() {
        return name;
      }
    }

    public static class DifferentName {

      private long id;
      private String fullName;

      public DifferentName() {}

      public long getId() {
        return id;
      }

      public void setId(final long id) {
        this.id = id;
      }

      public String getFullName() {
        return fullName;
      }

      public void setFullName(final String fullName) {
        this.fullName = fullName;
      }
    }

    public static class DifferentType {

      private Long id;
      private String name;

      public DifferentType() {}

      public Long getId() {
        return id;
      }

      public void setId(final Long id) {
        this.id = id;
      }

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }
    }

    public static class WithList {

      private long id;
      private List<String> tags;

      public WithList() {}

      public long getId() {
        return id;
      }

      public void setId(final long id) {
        this.id = id;
      }

      public List<String> getTags() {
        return tags;
      }

      public void setTags(final List<String> tags) {
        this.tags = tags;
      }
    }

    record SrcRec(long id, String name) {}

    @Test
    @DisplayName("source is a record → null (records use Records.fastPathForward instead)")
    void sourceIsRecord() {
      assertNull(Beans.fastPathForward(SrcRec.class, Dst.class));
    }

    @Test
    @DisplayName("target is a record → null (mixed shape: bean ↔ record, slow path)")
    void targetIsRecord() {
      assertNull(Beans.fastPathForward(Src.class, SrcRec.class));
    }

    @Test
    @DisplayName("target has no no-arg ctor → null (writeBeanProperty cannot allocate)")
    void targetMissingNoArgCtor() {
      assertNull(Beans.fastPathForward(Src.class, NoNoArgCtor.class));
    }

    @Test
    @DisplayName("different property name (fullName vs name) → null")
    void differentPropertyName() {
      assertNull(Beans.fastPathForward(Src.class, DifferentName.class));
    }

    @Test
    @DisplayName("different property type (long vs Long) → null")
    void differentPropertyType() {
      assertNull(Beans.fastPathForward(Src.class, DifferentType.class));
    }

    @Test
    @DisplayName("List<String> component → null (deep-copy / lift goes through slow path)")
    void containerComponent() {
      assertNull(Beans.fastPathForward(WithList.class, WithList.class));
    }
  }
}
