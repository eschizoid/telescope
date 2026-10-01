package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Which writer {@link Beans#autoWriter} picks, branch by branch of the shared rule: the builder's
 * member for each property (absent, accepting, rejecting), what the next strategy writes, and which
 * properties only a strategy can set (a non-final field) as against ones nothing writes (a computed
 * getter, a final field). Each fixture's writers tag what they build, so the assertions name the
 * writer by its effect as well as its type.
 */
class BeansAutoOrderTest {

  private static <P> P build(final Class<P> cls, final String name) {
    return Beans.autoWriter(cls).construct(Beans.propertyNames(cls), n -> n.equals("name") ? name : null);
  }

  // --- a builder with no member for a property the setters write --------------------------------

  /**
   * Setters write {@code name} and {@code code}; the builder has a member for {@code name} only.
   */
  public static final class BuilderMissingASetterProperty {

    private String name;
    private String code;

    public BuilderMissingASetterProperty() {}

    public String getName() {
      return name;
    }

    public void setName(final String name) {
      this.name = name + "[setters]";
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

      private String name;

      public Builder name(final String name) {
        this.name = name;
        return this;
      }

      public BuilderMissingASetterProperty build() {
        final var built = new BuilderMissingASetterProperty();
        built.name = name + "[builder]";
        return built;
      }
    }
  }

  // --- a builder whose member rejects the property's type --------------------------------------

  /** The builder's {@code name} member takes an {@code Integer}, which cannot hold a String. */
  public static final class BuilderRejectingAType {

    private String name;

    public BuilderRejectingAType() {}

    public String getName() {
      return name;
    }

    public void setName(final String name) {
      this.name = name + "[setters]";
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      public Builder name(final Integer name) {
        return this;
      }

      public BuilderRejectingAType build() {
        return new BuilderRejectingAType();
      }
    }
  }

  // --- sole builders -----------------------------------------------------------------------------

  /** The builder has no member for {@code label}, which only a strategy can set. */
  public static final class SoleBuilderMissingStored {

    private String name;
    private String label;

    private SoleBuilderMissingStored() {}

    public String getName() {
      return name;
    }

    public String getLabel() {
      return label;
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      private String name;

      public Builder name(final String name) {
        this.name = name;
        return this;
      }

      public SoleBuilderMissingStored build() {
        final var built = new SoleBuilderMissingStored();
        built.name = name;
        return built;
      }
    }
  }

  /** The builder has no member for {@code kind}, a final field set where it is declared. */
  public static final class SoleBuilderMissingFinal {

    private final String name;
    private final String kind = "K";

    private SoleBuilderMissingFinal(final String name) {
      this.name = name;
    }

    public String getName() {
      return name;
    }

    public String getKind() {
      return kind;
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      private String name;

      public Builder name(final String name) {
        this.name = name;
        return this;
      }

      public SoleBuilderMissingFinal build() {
        return new SoleBuilderMissingFinal(name + "[builder]");
      }
    }
  }

  /** The builder has no member for {@code shout}, a getter computed from {@code name}. */
  public static final class SoleBuilderMissingComputed {

    private final String name;

    private SoleBuilderMissingComputed(final String name) {
      this.name = name;
    }

    public String getName() {
      return name;
    }

    public String getShout() {
      return name == null ? null : name.toUpperCase(Locale.ROOT);
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      private String name;

      public Builder name(final String name) {
        this.name = name;
        return this;
      }

      public SoleBuilderMissingComputed build() {
        return new SoleBuilderMissingComputed(name + "[builder]");
      }
    }
  }

  /** A field that only a strategy can set, inherited by a builder-only subclass. */
  public static class StoredBase {

    protected String label;

    public String getLabel() {
      return label;
    }
  }

  /** The builder has no member for the inherited {@code label}. */
  public static final class SoleBuilderMissingInherited extends StoredBase {

    private String name;

    private SoleBuilderMissingInherited() {}

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

      public SoleBuilderMissingInherited build() {
        return new SoleBuilderMissingInherited();
      }
    }
  }

  /**
   * The builder's only member for {@code name} takes an {@code Integer}; nothing else writes it.
   */
  public static final class SoleBuilderRejecting {

    private String name;

    private SoleBuilderRejecting() {}

    public String getName() {
      return name;
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      public Builder name(final Integer name) {
        return this;
      }

      public SoleBuilderRejecting build() {
        return new SoleBuilderRejecting();
      }
    }
  }

  @Nested
  @DisplayName("the builder against the setters")
  class AgainstTheSetters {

    @Test
    @DisplayName("a builder with no member for a property a setter writes is passed over")
    void absentMemberForASetterProperty() {
      assertInstanceOf(Beans.SettersWriter.class, Beans.autoWriter(BuilderMissingASetterProperty.class));
      assertEquals("a[setters]", build(BuilderMissingASetterProperty.class, "a").getName());
    }

    @Test
    @DisplayName("a builder whose member cannot take its property's type is passed over")
    void rejectingMember() {
      assertInstanceOf(Beans.SettersWriter.class, Beans.autoWriter(BuilderRejectingAType.class));
      assertEquals("a[setters]", build(BuilderRejectingAType.class, "a").getName());
    }
  }

  @Nested
  @DisplayName("a builder that is the only strategy")
  class SoleBuilder {

    @Test
    @DisplayName("is refused when it has no member for a field only a strategy can set, naming that field")
    void refusedForAStoredField() {
      final var ex = assertThrows(IllegalStateException.class, () -> Beans.autoWriter(SoleBuilderMissingStored.class));
      assertTrue(ex.getMessage().contains("has no member that takes [label]"), ex.getMessage());
      assertTrue(ex.getMessage().contains(SoleBuilderMissingStored.Builder.class.getName()), ex.getMessage());
      assertNull(Beans.autoWriterOrNull(SoleBuilderMissingStored.class), "the refusal surfaces where it is built");
    }

    @Test
    @DisplayName("is refused when a member cannot take its property's type, naming that property")
    void refusedForARejectingMember() {
      final var ex = assertThrows(IllegalStateException.class, () -> Beans.autoWriter(SoleBuilderRejecting.class));
      assertTrue(ex.getMessage().contains("has no member that takes [name]"), ex.getMessage());
    }

    @Test
    @DisplayName("is refused for an inherited field only a strategy can set")
    void refusedForAnInheritedStoredField() {
      final var ex = assertThrows(IllegalStateException.class, () ->
        Beans.autoWriter(SoleBuilderMissingInherited.class)
      );
      assertTrue(ex.getMessage().contains("[label]"), ex.getMessage());
    }

    @Test
    @DisplayName("is taken when the property it lacks is a final field, which nothing writes")
    void takenForAFinalField() {
      assertInstanceOf(Beans.BuilderWriter.class, Beans.autoWriter(SoleBuilderMissingFinal.class));
      final var built = build(SoleBuilderMissingFinal.class, "a");
      assertEquals("a[builder]", built.getName());
      assertEquals("K", built.getKind());
    }

    @Test
    @DisplayName("is taken when the property it lacks is computed")
    void takenForAComputedGetter() {
      assertInstanceOf(Beans.BuilderWriter.class, Beans.autoWriter(SoleBuilderMissingComputed.class));
      assertEquals("A[BUILDER]", build(SoleBuilderMissingComputed.class, "a").getShout());
    }
  }

  @Nested
  @DisplayName("what each writer writes")
  class Writes {

    @Test
    @DisplayName("a builder writes the properties it has a member for")
    void builderWrites() {
      final var writer = Beans.autoWriter(SoleBuilderMissingComputed.class);
      assertTrue(writer.writes("name"));
      assertFalse(writer.writes("shout"));
    }

    @Test
    @DisplayName("a setter writer writes the properties it has a setter for")
    void settersWrite() {
      final var writer = Beans.autoWriter(BuilderMissingASetterProperty.class);
      assertTrue(writer.writes("name"));
      assertTrue(writer.writes("code"));
    }

    @Test
    @DisplayName("a constructor writer with parameter names writes exactly those")
    void constructorWrites() {
      final var writer = Beans.constructorWriter(Named.class, 1);
      assertTrue(writer.writes("name"));
      assertFalse(writer.writes("other"));
    }
  }

  /** One constructor, named {@code name}; this module compiles with {@code -parameters}. */
  public static final class Named {

    private final String name;

    public Named(final String name) {
      this.name = name;
    }

    public String getName() {
      return name;
    }

    public String getOther() {
      return null;
    }
  }

  @Test
  @DisplayName("autoWriterOrNull hands back the writer autoWriter picks")
  void autoWriterOrNullAgreesWithAutoWriter() {
    for (final var cls : List.<Class<?>>of(
      BuilderMissingASetterProperty.class,
      SoleBuilderMissingFinal.class,
      SoleBuilderMissingComputed.class
    )) {
      assertEquals(Beans.autoWriter(cls), Beans.autoWriterOrNull(cls), cls.getSimpleName());
    }
  }
}
