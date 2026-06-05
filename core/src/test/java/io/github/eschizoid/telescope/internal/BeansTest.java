package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link Beans} reflection helpers + the four {@code BeanWriter} strategies + the {@link
 * Beans#autoWriter} ladder including the 4th-rung constructor fallback. Each test exercises one
 * surface of the bean machinery so that a regression in any rung surfaces independently from the
 * end-to-end {@code Telescope.map} / {@code Telescope.ofBean} paths.
 */
class BeansTest {

  // ----- Fixtures -----

  static final class WithGetters {

    private final String id;
    private final boolean active;
    private final Boolean flag;
    private final String url;

    WithGetters(final String id, final boolean active, final Boolean flag, final String url) {
      this.id = id;
      this.active = active;
      this.flag = flag;
      this.url = url;
    }

    public String getId() {
      return id;
    }

    public boolean isActive() {
      return active;
    }

    public Boolean isFlag() {
      return flag;
    }

    // "URL" — JavaBeans 'two leading caps' rule keeps the property name as "URL", not "uRL".
    public String getURL() {
      return url;
    }
  }

  static final class NoArgFields {

    private String name;
    private int score;

    public NoArgFields() {}

    public String getName() {
      return name;
    }

    public int getScore() {
      return score;
    }
  }

  static final class NoArgSetters {

    private String name;
    private int score;

    public NoArgSetters() {}

    public String getName() {
      return name;
    }

    public int getScore() {
      return score;
    }

    public void setName(final String name) {
      this.name = name;
    }

    public void setScore(final int score) {
      this.score = score;
    }
  }

  static final class WithBuilder {

    private final String name;
    private final int score;

    private WithBuilder(final String name, final int score) {
      this.name = name;
      this.score = score;
    }

    public String getName() {
      return name;
    }

    public int getScore() {
      return score;
    }

    public static Builder builder() {
      return new Builder();
    }

    static final class Builder {

      private String name;
      private int score;

      // Cover the three setter-name conventions consumed by BuilderWriter: bare name, setX, withX.
      public Builder name(final String n) {
        this.name = n;
        return this;
      }

      public Builder setScore(final int s) {
        this.score = s;
        return this;
      }

      public WithBuilder build() {
        return new WithBuilder(name, score);
      }
    }
  }

  static final class WithBuilderWithSetter {

    private final String name;

    private WithBuilderWithSetter(final String name) {
      this.name = name;
    }

    public String getName() {
      return name;
    }

    public static Builder builder() {
      return new Builder();
    }

    static final class Builder {

      private String name;

      // Exercise the "withX" setter-name pattern that BuilderWriter falls through to last.
      public Builder withName(final String n) {
        this.name = n;
        return this;
      }

      public WithBuilderWithSetter build() {
        return new WithBuilderWithSetter(name);
      }
    }
  }

  static final class ImmutableCtor {

    private final String label;
    private final int count;

    public ImmutableCtor(final String label, final int count) {
      this.label = label;
      this.count = count;
    }

    public String getLabel() {
      return label;
    }

    public int getCount() {
      return count;
    }
  }

  static final class TwoCtorsSameArity {

    private final String a;

    public TwoCtorsSameArity(final String a) {
      this.a = a;
    }

    public TwoCtorsSameArity(final Integer ignored) {
      this.a = String.valueOf(ignored);
    }

    public String getA() {
      return a;
    }
  }

  static final class NothingBuildable {

    private final String x;

    private NothingBuildable(final String x) {
      this.x = x;
    }

    public String getX() {
      return x;
    }
  }

  static final class NoStaticBuilder {

    private String id;

    public NoStaticBuilder() {}

    public String getId() {
      return id;
    }

    public Object builder() {
      // Instance method, not static — autoWriter's hasStaticBuilder check must reject it.
      return new Object();
    }
  }

  static final class BuilderWithoutBuild {

    private final String id;

    private BuilderWithoutBuild(final String id) {
      this.id = id;
    }

    public String getId() {
      return id;
    }

    public static Builder builder() {
      return new Builder();
    }

    static final class Builder {

      public Builder id(final String id) {
        return this;
      }
      // Deliberately missing build() — BuilderWriter must reject.
    }
  }

  // ----- Getter scan / property metadata -----

  @Nested
  @DisplayName("Getter scanning and property metadata")
  class GetterScan {

    @Test
    @DisplayName("getX / isX / boolean and Boolean isX / URL two-caps rule all resolve to expected property names")
    void propertyNamesCoverConventions() {
      final var names = java.util.Arrays.asList(Beans.propertyNames(WithGetters.class));
      assertTrue(names.contains("id"));
      assertTrue(names.contains("active")); // isActive (boolean)
      assertTrue(names.contains("flag")); // isFlag (Boolean)
      assertTrue(names.contains("URL")); // two leading caps preserved
    }

    @Test
    @DisplayName("readProperty returns the getter value")
    void readPropertyHappy() {
      final var pojo = new WithGetters("u1", true, Boolean.FALSE, "https://x");
      assertEquals("u1", Beans.readProperty(pojo, "id"));
      assertEquals(true, Beans.readProperty(pojo, "active"));
      assertEquals(Boolean.FALSE, Beans.readProperty(pojo, "flag"));
      assertEquals("https://x", Beans.readProperty(pojo, "URL"));
    }

    @Test
    @DisplayName("readProperty throws IllegalArgumentException for a property that has no getter")
    void readPropertyMissingThrows() {
      final var pojo = new WithGetters("u1", true, null, "x");
      final var ex = assertThrows(IllegalArgumentException.class, () -> Beans.readProperty(pojo, "ghost"));
      assertTrue(ex.getMessage().contains("ghost"), ex.getMessage());
    }

    @Test
    @DisplayName("hasProperty reflects scan results")
    void hasPropertyReflectsScan() {
      assertTrue(Beans.hasProperty(WithGetters.class, "id"));
      assertFalse(Beans.hasProperty(WithGetters.class, "ghost"));
    }

    @Test
    @DisplayName("propertyType returns the generic return type of the getter")
    void propertyTypeHappy() {
      assertEquals(String.class, Beans.propertyType(WithGetters.class, "id"));
      assertEquals(boolean.class, Beans.propertyType(WithGetters.class, "active"));
    }

    @Test
    @DisplayName("propertyType throws when the named property has no getter")
    void propertyTypeMissingThrows() {
      assertThrows(IllegalArgumentException.class, () -> Beans.propertyType(WithGetters.class, "ghost"));
    }

    @Test
    @DisplayName("propertyOf strips get/is prefix; unknown prefix is returned unchanged")
    void propertyOfPrefixStripping() {
      assertEquals("name", Beans.propertyOf("getName"));
      assertEquals("active", Beans.propertyOf("isActive"));
      assertEquals("name", Beans.propertyOf("name")); // no prefix → returned as-is
      assertEquals("URL", Beans.propertyOf("getURL")); // two-caps rule preserved
    }
  }

  // ----- FieldsWriter -----

  @Nested
  @DisplayName("FieldsWriter — no-arg ctor + reflective field injection")
  class Fields {

    @Test
    @DisplayName("round-trip via fieldsWriter populates declared fields by name")
    void fieldsRoundTrip() {
      final var writer = Beans.fieldsWriter(NoArgFields.class);
      final var values = Map.<String, Object>of("name", "alice", "score", 9);
      final var pojo = writer.construct(new String[] { "name", "score" }, values::get);
      assertEquals("alice", pojo.getName());
      assertEquals(9, pojo.getScore());
    }

    @Test
    @DisplayName("fieldsWriter throws when no no-arg constructor exists")
    void fieldsRequiresNoArgCtor() {
      assertThrows(IllegalStateException.class, () -> Beans.fieldsWriter(ImmutableCtor.class));
    }

    @Test
    @DisplayName("construct throws when a name has no matching declared field")
    void fieldsUnknownNameThrows() {
      final var writer = Beans.fieldsWriter(NoArgFields.class);
      assertThrows(IllegalArgumentException.class, () -> writer.construct(new String[] { "ghost" }, n -> "x"));
    }
  }

  // ----- SettersWriter -----

  @Nested
  @DisplayName("SettersWriter — no-arg ctor + public setX(value)")
  class Setters {

    @Test
    @DisplayName("round-trip via settersWriter invokes setX for each name")
    void settersRoundTrip() {
      final var writer = Beans.settersWriter(NoArgSetters.class);
      final var values = Map.<String, Object>of("name", "bob", "score", 5);
      final var pojo = writer.construct(new String[] { "name", "score" }, values::get);
      assertEquals("bob", pojo.getName());
      assertEquals(5, pojo.getScore());
    }

    @Test
    @DisplayName("settersWriter throws when no no-arg constructor exists")
    void settersRequiresNoArgCtor() {
      assertThrows(IllegalStateException.class, () -> Beans.settersWriter(ImmutableCtor.class));
    }

    @Test
    @DisplayName("construct throws IllegalArgumentException for a name without a matching setter")
    void settersMissingSetterThrows() {
      final var writer = Beans.settersWriter(NoArgFields.class); // has fields but no setters
      assertThrows(RuntimeException.class, () -> writer.construct(new String[] { "name" }, n -> "x"));
    }
  }

  // ----- BuilderWriter -----

  @Nested
  @DisplayName("BuilderWriter — static builder() + named/setX/withX setters + build()")
  class Builders {

    @Test
    @DisplayName("round-trip via builderWriter resolves bare-name + setX setters and calls build()")
    void builderRoundTripMixedSetterStyles() {
      final var writer = Beans.builderWriter(WithBuilder.class);
      final var values = Map.<String, Object>of("name", "carol", "score", 7);
      final var pojo = writer.construct(new String[] { "name", "score" }, values::get);
      assertEquals("carol", pojo.getName());
      assertEquals(7, pojo.getScore());
    }

    @Test
    @DisplayName("builderWriter resolves the withX setter convention as the last fallback")
    void builderWithXFallback() {
      final var writer = Beans.builderWriter(WithBuilderWithSetter.class);
      final var pojo = writer.construct(new String[] { "name" }, n -> "dora");
      assertEquals("dora", pojo.getName());
    }

    @Test
    @DisplayName("builderWriter throws if class has no static builder() factory")
    void builderRequiresStaticBuilder() {
      assertThrows(IllegalStateException.class, () -> Beans.builderWriter(NoArgFields.class));
    }

    @Test
    @DisplayName("builderWriter throws if the builder type has no build() method")
    void builderRequiresBuildMethod() {
      assertThrows(IllegalStateException.class, () -> Beans.builderWriter(BuilderWithoutBuild.class));
    }

    @Test
    @DisplayName("construct throws if no setter on the builder matches a given name")
    void builderUnknownSetterThrows() {
      final var writer = Beans.builderWriter(WithBuilder.class);
      assertThrows(RuntimeException.class, () -> writer.construct(new String[] { "ghost" }, n -> "x"));
    }
  }

  // ----- ConstructorWriter -----

  @Nested
  @DisplayName("ConstructorWriter — all-args ctor (named with -parameters, positional fallback otherwise)")
  class Constructors {

    @Test
    @DisplayName("round-trip via constructorWriter populates each ctor arg")
    void constructorRoundTrip() {
      final var writer = Beans.constructorWriter(ImmutableCtor.class, 2);
      final var values = Map.<String, Object>of("label", "x", "count", 11);
      final var pojo = writer.construct(new String[] { "label", "count" }, values::get);
      assertEquals("x", pojo.getLabel());
      assertEquals(11, pojo.getCount());
    }

    @Test
    @DisplayName("constructorWriter throws when no constructor of the requested arity exists")
    void constructorMissingArityThrows() {
      assertThrows(IllegalStateException.class, () -> Beans.constructorWriter(ImmutableCtor.class, 99));
    }

    @Test
    @DisplayName("constructorWriter throws when more than one constructor matches the arity")
    void constructorAmbiguousArityThrows() {
      assertThrows(IllegalStateException.class, () -> Beans.constructorWriter(TwoCtorsSameArity.class, 1));
    }
  }

  // ----- autoWriter ladder -----

  @Nested
  @DisplayName("autoWriter — strategy ladder + cache + 4th-rung constructor fallback")
  class AutoWriter {

    @Test
    @DisplayName("autoWriter picks BuilderWriter when a static builder() is present")
    void autoPicksBuilder() {
      final var writer = Beans.autoWriter(WithBuilder.class);
      assertEquals("BuilderWriter", writer.getClass().getSimpleName());
      // Functional confirmation: produces a working instance.
      final var pojo = writer.construct(new String[] { "name", "score" }, n -> Objects.equals(n, "name") ? "x" : 1);
      assertNotNull(pojo);
    }

    @Test
    @DisplayName("autoWriter picks SettersWriter when no-arg ctor + setters exist (no builder)")
    void autoPicksSetters() {
      final var writer = Beans.autoWriter(NoArgSetters.class);
      final var pojo = writer.construct(new String[] { "name" }, n -> "e");
      assertEquals("e", pojo.getName());
    }

    @Test
    @DisplayName("autoWriter falls back to FieldsWriter when no-arg ctor but no setters")
    void autoPicksFields() {
      final var writer = Beans.autoWriter(NoArgFields.class);
      final var pojo = writer.construct(new String[] { "name" }, n -> "f");
      assertEquals("f", pojo.getName());
    }

    @Test
    @DisplayName("autoWriter 4th-rung: a single public all-args ctor compiled with -parameters works")
    void autoFourthRungConstructorFallback() {
      // ImmutableCtor has no builder, no no-arg ctor, exactly one public 2-arg ctor.
      final var writer = Beans.autoWriter(ImmutableCtor.class);
      final var values = Map.<String, Object>of("label", "z", "count", 3);
      final var pojo = writer.construct(new String[] { "label", "count" }, values::get);
      assertEquals("z", pojo.getLabel());
      assertEquals(3, pojo.getCount());
    }

    @Test
    @DisplayName("autoWriter refuses ambiguous multi-ctor POJO with a descriptive message")
    void autoRefusesAmbiguousCtor() {
      final var ex = assertThrows(IllegalStateException.class, () -> Beans.autoWriter(TwoCtorsSameArity.class));
      assertTrue(ex.getMessage().contains("writeBean") || ex.getMessage().contains("name-based"), ex.getMessage());
    }

    @Test
    @DisplayName("autoWriter refuses class with no builder, no no-arg ctor, only a non-public ctor")
    void autoRefusesUnreachable() {
      // NothingBuildable has a private ctor only — solePublicConstructor returns null → throws.
      assertThrows(IllegalStateException.class, () -> Beans.autoWriter(NothingBuildable.class));
    }

    @Test
    @DisplayName("autoWriter cache returns the same writer instance on repeated calls")
    void autoCacheReusesWriter() {
      final var a = Beans.autoWriter(NoArgSetters.class);
      final var b = Beans.autoWriter(NoArgSetters.class);
      assertSame(a, b);
    }

    @Test
    @DisplayName("autoWriter ignores a non-static builder() method (uses fields-or-setters instead)")
    void autoIgnoresNonStaticBuilder() {
      // NoStaticBuilder declares an INSTANCE builder() (not static). hasStaticBuilder rejects it,
      // so autoWriter falls through to setters/fields. NoStaticBuilder has a no-arg ctor and no
      // setters, so it lands on FieldsWriter.
      final var writer = Beans.autoWriter(NoStaticBuilder.class);
      assertNotNull(writer);
      final var pojo = writer.construct(new String[] { "id" }, n -> "ok");
      assertEquals("ok", pojo.getId());
    }
  }

  // ----- lens (single-property Lens) -----

  @Nested
  @DisplayName("Beans.lens — Lens<P, A> over a single bean property")
  class LensFactory {

    @Test
    @DisplayName("get reads via the getter, set rebuilds with the new value, other props carry over")
    void lensReadWriteRebuild() {
      final io.github.eschizoid.telescope.internal.optics.Lens<NoArgSetters, String> lens = Beans.lens(
        NoArgSetters.class,
        "name",
        Beans.autoWriter(NoArgSetters.class)
      );
      final var base = new NoArgSetters();
      base.setName("old");
      base.setScore(42);
      final var updated = lens.set(base, "new");
      assertEquals("new", updated.getName());
      assertEquals(42, updated.getScore()); // off-path value preserved through the rebuild
      assertEquals("old", base.getName()); // base was not mutated
    }

    @Test
    @DisplayName("modify applies a function over the read value")
    void lensModify() {
      final io.github.eschizoid.telescope.internal.optics.Lens<NoArgSetters, String> lens = Beans.lens(
        NoArgSetters.class,
        "name",
        Beans.autoWriter(NoArgSetters.class)
      );
      final var base = new NoArgSetters();
      base.setName("alice");
      base.setScore(1);
      final var upper = lens.modify(base, String::toUpperCase);
      assertEquals("ALICE", upper.getName());
    }
  }
}
