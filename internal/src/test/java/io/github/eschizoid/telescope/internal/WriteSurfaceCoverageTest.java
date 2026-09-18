package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A write rebuilds the bean, so the surface chosen to rebuild it decides what survives. Both
 * surfaces skip silently: each installs a no-op for a property it has no member for, and the
 * rebuilt bean then carries a default where the source had a value, with nothing raised.
 *
 * <p>Choosing between them is therefore a question about both. Asking only whether the setters are
 * complete answers about the surface being abandoned, and on a bean whose two surfaces reach
 * different properties it moves the loss onto the write that was actually requested.
 *
 * <p>Every case here writes one property on a populated bean and asserts what the result holds.
 * Asserting which writer was chosen would pin the mechanism rather than the contract, and would
 * hold on a bean where both surfaces produce the same value — which is most of them, and why
 * nothing in this repository exercised any of it.
 */
class WriteSurfaceCoverageTest {

  private static <P> P writeName(final Class<P> cls, final P seed) {
    return Beans.lens(cls, "name", Beans.autoWriter(cls)).set(seed, "bob");
  }

  @Nested
  @DisplayName("a surface that cannot carry the whole bean is not preferred over one that can")
  class Coverage {

    @Test
    @DisplayName("a property only the builder can write survives a write through another property")
    void builderCarriesWhatSettersCannot() {
      // The shape this exists for. Setters reach `name` alone, the builder reaches both, and the
      // rebuild used to go through the setters -- so writing `name` returned a bean whose `code`
      // had been dropped, silently, from a value the source held.
      final var out = writeName(PartialSettersFullBuilder.class, PartialSettersFullBuilder.of("alice", "AB"));

      assertEquals("bob", out.getName(), "the requested write lands");
      assertEquals("AB", out.getCode(), "and the property only the builder reaches is still there");
    }

    @Test
    @DisplayName("two surfaces reaching different properties leave the requested write alone")
    void disjointSurfacesKeepTheRequestedWrite() {
      // The case that makes this a question about both surfaces rather than one. Setters reach
      // `name`, the builder reaches `code`, and neither carries the bean. Moving to the builder
      // because the setters are incomplete would drop `name` -- the property being written -- and
      // keep the bystander, which is worse than the loss it would be fixing rather than louder.
      final var out = writeName(DisjointSurfaces.class, DisjointSurfaces.of("alice", "AB"));

      assertEquals("bob", out.getName(), "the requested write still lands");
      assertNull(out.getCode(), "and what neither surface reaches is still lost, as it was");
    }

    @Test
    @DisplayName("a getter neither surface can write leaves the choice where it was")
    void anUnwritablePropertyMovesNothing() {
      // A computed getter has no backing field, so it is unwritable and equally unlosable. Its
      // presence alone used to be enough to call the setters incomplete, which would move an
      // ordinary bean onto the builder for no gain.
      //
      // A control, and one no change to this decision can move: both surfaces produce the same
      // bean here by construction, so it reports that the shape works rather than that the
      // decision was made correctly.
      final var out = writeName(ComputedGetter.class, ComputedGetter.of("alice"));

      assertEquals("bob", out.getName());
      assertEquals("BOB", out.getShout(), "the computed value follows what was written");
    }
  }

  @Nested
  @DisplayName("controls — beans whose two surfaces agree, or that have only one")
  class Controls {

    @Test
    @DisplayName("setters covering the bean are used, with no builder in sight")
    void settersAloneCarryTheBean() {
      final var out = writeName(AllSetters.class, AllSetters.of("alice", "AB"));

      assertEquals("bob", out.getName());
      assertEquals("AB", out.getCode());
    }

    @Test
    @DisplayName("setters covering the bean are still used when a builder also covers it")
    void settersWinWhenBothCover() {
      // Nothing is gained by moving, so nothing moves. This row is a control rather than a guard:
      // both surfaces reproduce the same bean, so it holds whichever is chosen, and what actually
      // pins the preference is the autoWriter row in BeansTest.
      final var out = writeName(AllSettersAndBuilder.class, AllSettersAndBuilder.of("alice", "AB"));

      assertEquals("bob", out.getName());
      assertEquals("AB", out.getCode());
    }

    @Test
    @DisplayName("a method named builder that builds nothing is not a builder")
    void anUnrelatedBuilderMethodIsNotASurface() {
      // A method's name is not a contract. Nothing can finish with what this one returns, so it is
      // not a surface — and because the surface is chosen while the path is constructed, treating
      // it as one costs a read that was never going to write anything.
      final var out = assertDoesNotThrow(() -> writeName(UnrelatedBuilder.class, UnrelatedBuilder.of("alice")));

      assertEquals("bob", out.getName());
    }

    @Test
    @DisplayName("a build() nothing can call on a builder instance is not a builder either")
    void aStaticBuildIsNotASurface() {
      // Existing and being callable are different requirements. A static build() belongs to the
      // class rather than to the builder that was made, so nothing can invoke it on that builder —
      // and the failure lands where the path is constructed, not where a value is written.
      final var out = assertDoesNotThrow(() -> writeName(StaticBuild.class, StaticBuild.of("alice")));

      assertEquals("bob", out.getName());
    }

    @Test
    @DisplayName("a build() naming an ancestor is still a builder, since the name is not what it makes")
    void aBuildDeclaredAsAnAncestorIsStillABuilder() {
      // What build() is declared to return says nothing about what it makes. A generic builder's
      // erases to Object and one on an abstract base names that base, so demanding the bean's own
      // type refuses both -- and this probe also decides the rung for a bean with no setters, so
      // refusing takes a builder that was working away from it.
      final var out = writeName(SupertypeBuild.class, SupertypeBuild.of("alice", "AB"));

      assertEquals("bob", out.getName());
      assertEquals("AB", out.getCode(), "the property only the builder reaches survives");
    }

    @Test
    @DisplayName("a build() returning something unrelated is not a builder")
    void aBuildReturningSomethingUnrelatedIsNotASurface() {
      // The other side of that, and what stops the row above from being "accept any build()". This
      // one hands back an object of a class the field cannot hold, and does it without complaint.
      final var out = assertDoesNotThrow(() -> writeName(UnrelatedBuild.class, UnrelatedBuild.of("alice")));

      assertEquals("bob", out.getName());
    }

    @Test
    @DisplayName("a property named after a method every type inherits matches no builder member")
    void aPropertyNamedLikeAnObjectMethodMovesNothing() {
      // Every builder inherits Object's methods, so a `wait` property finds `Object.wait(long)` by
      // name and by arity, and its parameter accepts the value. Reading that as coverage moves the
      // bean and then fails to bind, on a module that does not open java.lang.
      final var out = assertDoesNotThrow(() ->
        writeName(ObjectNamedProperty.class, ObjectNamedProperty.of("alice", 5L))
      );

      assertEquals("bob", out.getName(), "the requested write lands");
    }

    @Test
    @DisplayName("a builder member that answers by name but cannot hold the value moves nothing")
    void aBuilderMemberThatCannotHoldTheValueMovesNothing() {
      // `tags(String...)` matches the `tags` property by name and by arity, and cannot take a List.
      // Reading a match as coverage would move this bean onto the builder and turn a write that was
      // landing into a cast failure -- which is worse than the loss the move is for, not louder.
      final var out = assertDoesNotThrow(() ->
        writeName(VarargsBuilderMember.class, VarargsBuilderMember.of("alice", List.of("x")))
      );

      assertEquals("bob", out.getName(), "the requested write lands rather than raising a cast");
      // `tags` is still dropped, because the setters cannot carry it and the builder cannot hold
      // what it reads as either. Nothing here fixes that bean; what matters is that the write it
      // was asked for still works, where reading the name match as coverage made it throw.
      assertNull(out.getTags());
    }
  }

  // --- fixtures -------------------------------------------------------------------------------

  /** Setters reach every property; there is no builder. */
  public static class AllSetters {

    private String name;
    private String code;

    static AllSetters of(final String name, final String code) {
      final var b = new AllSetters();
      b.setName(name);
      b.setCode(code);
      return b;
    }

    public String getName() {
      return name;
    }

    public void setName(final String name) {
      this.name = name;
    }

    public String getCode() {
      return code;
    }

    public void setCode(final String code) {
      this.code = code;
    }
  }

  /** Both surfaces reach every property. */
  public static class AllSettersAndBuilder {

    private String name;
    private String code;

    static AllSettersAndBuilder of(final String name, final String code) {
      final var b = new AllSettersAndBuilder();
      b.setName(name);
      b.setCode(code);
      return b;
    }

    public String getName() {
      return name;
    }

    public void setName(final String name) {
      this.name = name;
    }

    public String getCode() {
      return code;
    }

    public void setCode(final String code) {
      this.code = code;
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      private final AllSettersAndBuilder held = new AllSettersAndBuilder();

      public Builder name(final String name) {
        held.name = name;
        return this;
      }

      public Builder code(final String code) {
        held.code = code;
        return this;
      }

      public AllSettersAndBuilder build() {
        return held;
      }
    }
  }

  /** Setters reach {@code name}; the builder reaches both. */
  public static class PartialSettersFullBuilder {

    private String name;
    private String code;

    static PartialSettersFullBuilder of(final String name, final String code) {
      final var b = new PartialSettersFullBuilder();
      b.name = name;
      b.code = code;
      return b;
    }

    public String getName() {
      return name;
    }

    public void setName(final String name) {
      this.name = name;
    }

    public String getCode() {
      return code;
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      private final PartialSettersFullBuilder held = new PartialSettersFullBuilder();

      public Builder name(final String name) {
        held.name = name;
        return this;
      }

      public Builder code(final String code) {
        held.code = code;
        return this;
      }

      public PartialSettersFullBuilder build() {
        return held;
      }
    }
  }

  /** Setters reach {@code name}; the builder reaches {@code code}. Neither carries the bean. */
  public static class DisjointSurfaces {

    private String name;
    private String code;

    static DisjointSurfaces of(final String name, final String code) {
      final var b = new DisjointSurfaces();
      b.name = name;
      b.code = code;
      return b;
    }

    public String getName() {
      return name;
    }

    public void setName(final String name) {
      this.name = name;
    }

    public String getCode() {
      return code;
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      private final DisjointSurfaces held = new DisjointSurfaces();

      public Builder code(final String code) {
        held.code = code;
        return this;
      }

      public DisjointSurfaces build() {
        return held;
      }
    }
  }

  /** {@code shout} is computed, so neither surface writes it and neither loses it. */
  public static class ComputedGetter {

    private String name;

    static ComputedGetter of(final String name) {
      final var b = new ComputedGetter();
      b.setName(name);
      return b;
    }

    public String getName() {
      return name;
    }

    public void setName(final String name) {
      this.name = name;
    }

    public String getShout() {
      return name == null ? null : name.toUpperCase(java.util.Locale.ROOT);
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      private final ComputedGetter held = new ComputedGetter();

      public Builder name(final String name) {
        held.name = name;
        return this;
      }

      public ComputedGetter build() {
        return held;
      }
    }
  }

  /**
   * A static {@code builder()} returning something with no {@code build()}, and <em>no
   * setters</em>.
   *
   * <p>The absence matters. With a setter it takes the setters branch whatever the builder probe
   * decides, never reaches that probe, and pins nothing about it.
   */
  public static class UnrelatedBuilder {

    private String name;

    static UnrelatedBuilder of(final String name) {
      final var b = new UnrelatedBuilder();
      b.name = name;
      return b;
    }

    public String getName() {
      return name;
    }

    public static String builder() {
      return "not a builder";
    }
  }

  /** A {@code build()} that is static, so nothing can call it on a builder instance. */
  public static class StaticBuild {

    private String name;

    static StaticBuild of(final String name) {
      final var b = new StaticBuild();
      b.name = name;
      return b;
    }

    public String getName() {
      return name;
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      public Builder name(final String name) {
        return this;
      }

      public static StaticBuild build() {
        return new StaticBuild();
      }
    }
  }

  /**
   * Its {@code build()} names an ancestor and returns something concrete, as a generic one does.
   */
  public static class SupertypeBuild extends SupertypeBuildBase {

    private String name;
    private String code;

    static SupertypeBuild of(final String name, final String code) {
      final var b = new SupertypeBuild();
      b.name = name;
      b.code = code;
      return b;
    }

    public String getName() {
      return name;
    }

    public void setName(final String name) {
      this.name = name;
    }

    public String getCode() {
      return code;
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      private final SupertypeBuild held = new SupertypeBuild();

      public Builder name(final String name) {
        held.name = name;
        return this;
      }

      public Builder code(final String code) {
        held.code = code;
        return this;
      }

      /** Declared as the base, which says nothing about what it makes. */
      public SupertypeBuildBase build() {
        return held;
      }
    }
  }

  /** The ancestor {@code build()} names. */
  public static class SupertypeBuildBase {}

  /** Its {@code build()} returns something the bean has nothing to do with. */
  public static class UnrelatedBuild {

    private String name;

    static UnrelatedBuild of(final String name) {
      final var b = new UnrelatedBuild();
      b.name = name;
      return b;
    }

    public String getName() {
      return name;
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      public String build() {
        return "not the bean";
      }
    }
  }

  /** A property whose name collides with a method every type inherits. */
  public static class ObjectNamedProperty {

    private String name;
    private long wait;

    static ObjectNamedProperty of(final String name, final long wait) {
      final var b = new ObjectNamedProperty();
      b.name = name;
      b.wait = wait;
      return b;
    }

    public String getName() {
      return name;
    }

    public void setName(final String name) {
      this.name = name;
    }

    public long getWait() {
      return wait;
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      private final ObjectNamedProperty held = new ObjectNamedProperty();

      public Builder name(final String name) {
        held.name = name;
        return this;
      }

      public ObjectNamedProperty build() {
        return held;
      }
    }
  }

  /** A builder member that answers by name and arity and cannot hold the property's value. */
  public static class VarargsBuilderMember {

    private String name;
    private List<String> tags;

    static VarargsBuilderMember of(final String name, final List<String> tags) {
      final var b = new VarargsBuilderMember();
      b.name = name;
      b.tags = tags;
      return b;
    }

    public String getName() {
      return name;
    }

    public void setName(final String name) {
      this.name = name;
    }

    public List<String> getTags() {
      return tags;
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      private final VarargsBuilderMember held = new VarargsBuilderMember();

      public Builder name(final String name) {
        held.name = name;
        return this;
      }

      public Builder tags(final String... tags) {
        held.tags = List.of(tags);
        return this;
      }

      public VarargsBuilderMember build() {
        return held;
      }
    }
  }
}
