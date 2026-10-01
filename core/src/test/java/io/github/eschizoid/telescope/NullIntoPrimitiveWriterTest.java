package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.MergeStep.from;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A null reaching a primitive property becomes its JLS default whichever writer builds the bean.
 * The setter writer skips the null and leaves the field at its default; the constructor and builder
 * writers pass the default in place of the null, since a primitive parameter cannot hold one. Each
 * fixture below is built by a different writer, and each tags nothing, so the assertions hold only
 * when the null was replaced rather than thrown on.
 */
class NullIntoPrimitiveWriterTest {

  public record Rec(String s) {}

  /** Built through its constructor: a name-matched all-args constructor comes before setters. */
  public static class CtorBean {

    private String s;
    private int n;

    public CtorBean() {}

    public CtorBean(final String s, final int n) {
      this.s = s;
      this.n = n;
    }

    public String getS() {
      return s;
    }

    public void setS(final String s) {
      this.s = s;
    }

    public int getN() {
      return n;
    }

    public void setN(final int n) {
      this.n = n;
    }
  }

  /** Built through its builder: a builder carrying every property comes first. */
  public static class BuilderBean {

    private String s;
    private int n;

    public BuilderBean() {}

    public String getS() {
      return s;
    }

    public void setS(final String s) {
      this.s = s;
    }

    public int getN() {
      return n;
    }

    public void setN(final int n) {
      this.n = n;
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      private String s;
      private int n = -1;

      public Builder s(final String s) {
        this.s = s;
        return this;
      }

      public Builder n(final int n) {
        this.n = n;
        return this;
      }

      public BuilderBean build() {
        final var built = new BuilderBean();
        built.s = s;
        built.n = n;
        return built;
      }
    }
  }

  /** Built through its setters, the control the other two have to match. */
  public static class SettersBean {

    private String s;
    private int n;

    public SettersBean() {}

    public String getS() {
      return s;
    }

    public void setS(final String s) {
      this.s = s;
    }

    public int getN() {
      return n;
    }

    public void setN(final int n) {
      this.n = n;
    }
  }

  @Nested
  @DisplayName("merge with the primitive slot unbound")
  class Merge {

    @Test
    @DisplayName("a constructor-built bean takes the JLS default for the unbound primitive")
    void constructorBuiltBean() {
      final var out = Telescope.merge(CtorBean.class, from(Rec::s, CtorBean::getS)).forward(Sources.of(new Rec("a")));
      assertEquals("a", out.getS());
      assertEquals(0, out.getN());
    }

    @Test
    @DisplayName("a builder-built bean takes the JLS default for the unbound primitive")
    void builderBuiltBean() {
      // The builder's own field starts at -1, so a skipped call would show -1 here.
      final var out = Telescope.merge(BuilderBean.class, from(Rec::s, BuilderBean::getS)).forward(
        Sources.of(new Rec("a"))
      );
      assertEquals("a", out.getS());
      assertEquals(0, out.getN());
    }

    @Test
    @DisplayName("a setter-built bean leaves the unbound primitive at its JLS default")
    void setterBuiltBean() {
      final var out = Telescope.merge(SettersBean.class, from(Rec::s, SettersBean::getS)).forward(
        Sources.of(new Rec("a"))
      );
      assertEquals(0, out.getN());
    }
  }

  @Nested
  @DisplayName("ofBean set(x, null) on a primitive property")
  class OfBeanSet {

    @Test
    @DisplayName("through the constructor writer")
    void constructorWriter() {
      final var seed = new CtorBean("a", 5);
      final var out = Telescope.ofBean(CtorBean.class).fieldByName("n", Integer.class).set(seed, null);
      assertEquals(0, out.getN());
      assertEquals("a", out.getS());
    }

    @Test
    @DisplayName("through the builder writer")
    void builderWriter() {
      final var seed = new BuilderBean();
      seed.setS("a");
      seed.setN(5);
      final var out = Telescope.ofBean(BuilderBean.class).fieldByName("n", Integer.class).set(seed, null);
      assertEquals(0, out.getN());
      assertEquals("a", out.getS());
    }

    @Test
    @DisplayName("through the setter writer")
    void settersWriter() {
      final var seed = new SettersBean();
      seed.setN(5);
      assertEquals(0, Telescope.ofBean(SettersBean.class).fieldByName("n", Integer.class).set(seed, null).getN());
    }
  }
}
