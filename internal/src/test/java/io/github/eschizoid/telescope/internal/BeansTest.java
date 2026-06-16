package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
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

  /**
   * Primitive-return getter fixture for the LambdaMetafactory auto-boxing path. {@code int
   * getAge()} crosses the {@code Function<Object, Object>::apply} SAM as a boxed {@link Integer}
   * thanks to the bridge the metafactory generates from the {@code instantiatedMethodType} passed
   * to {@link Beans} on cache build.
   */
  static final class WithPrimitives {

    private final int age;
    private final long count;
    private final boolean flagged;

    WithPrimitives(final int age, final long count, final boolean flagged) {
      this.age = age;
      this.count = count;
      this.flagged = flagged;
    }

    public int getAge() {
      return age;
    }

    public long getCount() {
      return count;
    }

    public boolean isFlagged() {
      return flagged;
    }
  }

  // Fixture pair for the inherited-accessor LambdaMetafactory path. ChildBean inherits `getId()`
  // and `setId(String)` from ParentBean; the LMF cache build must use the setter / getter's
  // declaring class (ParentBean) for `privateLookupIn` and the instantiated receiver type — using
  // ChildBean (the inheritor) would still happen to work in the same package but is semantically
  // wrong, and breaks when the parent lives in a separate module whose package is opened to
  // telescope (and the child's isn't, or vice-versa).
  static class ParentBean {

    private String id;

    public String getId() {
      return id;
    }

    public void setId(final String id) {
      this.id = id;
    }
  }

  static final class ChildBean extends ParentBean {

    private String name;

    public String getName() {
      return name;
    }

    public void setName(final String name) {
      this.name = name;
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

  // The Lombok `@Data @Builder` shape: a public no-arg ctor + setters AND a static builder().
  // Real-world enterprise codebases are dominated by this combination, and SETTERS is the
  // user-expected default — exposed publicly by @Setter, idiomatic across web tier and JPA. Pins
  // the autoWriter probe order's preference for SETTERS over BUILDER when both apply.
  static final class DataAndBuilder {

    private String name;
    private int score;

    public DataAndBuilder() {}

    DataAndBuilder(final String name, final int score) {
      this.name = name;
      this.score = score;
    }

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

    public static Builder builder() {
      return new Builder();
    }

    static final class Builder {

      private String name;
      private int score;

      public Builder name(final String n) {
        this.name = n;
        return this;
      }

      public Builder score(final int s) {
        this.score = s;
        return this;
      }

      public DataAndBuilder build() {
        return new DataAndBuilder(name, score);
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

  // Multi-primitive fixture pinning the spread-MethodHandle auto-unboxing path used by
  // ConstructorWriter (and by Records.RecordInfo.ctorFn — see PrimitiveRecord). LMF rejects the
  // asSpreader adapter ("not direct or cannot be cracked"), so the ctor invoker is a cached
  // MethodHandle invoked via `invokeExact` rather than an LMF-synthesized Function. Boxed
  // `int`/`long`/`double`/`boolean` values flowing in via the Object[] get unboxed by the
  // implicit conversions the spread handle applies, the same way a direct constructor call would.
  static final class PrimitiveCtor {

    private final int i;
    private final long l;
    private final double d;
    private final boolean b;

    public PrimitiveCtor(final int i, final long l, final double d, final boolean b) {
      this.i = i;
      this.l = l;
      this.d = d;
      this.b = b;
    }

    public int getI() {
      return i;
    }

    public long getL() {
      return l;
    }

    public double getD() {
      return d;
    }

    public boolean isB() {
      return b;
    }
  }

  // Multi-primitive FIELDS fixture pinning the cached-setter unbox path: each declared field is
  // written through the MethodHandle setter built once at construction time.
  static final class PrimitiveFields {

    private int i;
    private long l;
    private double d;
    private boolean b;

    public PrimitiveFields() {}

    public int getI() {
      return i;
    }

    public long getL() {
      return l;
    }

    public double getD() {
      return d;
    }

    public boolean isB() {
      return b;
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

  // Fixture pinning support for void-returning builder setters (classic JavaBean-style builders,
  // where setters mutate the builder in place rather than returning `this`). BuilderWriter binds
  // such setters as a BiConsumer<Object, Object> through LambdaMetafactory and dispatches them
  // alongside fluent BiFunction-shaped setters; build() works the same regardless of setter
  // return type.
  static final class BuilderWithVoidSetter {

    private final String id;

    private BuilderWithVoidSetter(final String id) {
      this.id = id;
    }

    public String getId() {
      return id;
    }

    public static Builder builder() {
      return new Builder();
    }

    static final class Builder {

      private String id;

      // void-returning setter — supported via the BiConsumer binding path.
      public void id(final String id) {
        this.id = id;
      }

      public BuilderWithVoidSetter build() {
        return new BuilderWithVoidSetter(id);
      }
    }
  }

  // ----- Getter scan / property metadata -----

  @Nested
  @DisplayName("Getter scanning and property metadata")
  class GetterScan {

    @Test
    @DisplayName("getX / isX / boolean and Boolean isX / URL two-caps rule all resolve to expected property names")
    void propertyNamesCoverConventions() {
      final var names = Arrays.asList(Beans.propertyNames(WithGetters.class));
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

    @Test
    @DisplayName("readProperty(null, name) returns null instead of NPEing on persistentClassOf")
    void readPropertyNullPojoIsNullSafe() {
      // Bug 4: when a multi-hop telescope path reads through a null intermediate, the next lens
      // hop calls Beans.readProperty(null, "..."). persistentClassOf(null) returns null, then
      // ClassValue.get(null) NPEs. Short-circuit at the readProperty entry so the optic pipeline
      // propagates the null gracefully through intermediate hops.
      assertNull(Beans.readProperty(null, "anything"));
    }

    @Test
    @DisplayName("propertyOf(null) returns null instead of throwing NPE")
    void propertyOfNullIsNullSafe() {
      // Belt-and-suspenders guard. The structural fix for Bug 2 lives in DeepMap.populateIso —
      // the row-loop now peels nested-telescope sub-shapes (FromTelescopeTo / TelescopeToTelescope
      // whose sourceField() returns null by design) before normalising. This guard ensures the
      // public Beans surface stays null-safe in case any future caller forgets to peel.
      assertNull(Beans.propertyOf(null));
    }

    @Test
    @DisplayName("readProperty auto-boxes primitive getter returns (int → Integer, long → Long, boolean → Boolean)")
    void readPropertyAutoBoxesPrimitives() {
      // Pins the LambdaMetafactory instantiatedMethodType bridge: the (P -> int) MethodHandle
      // surfaces through Function<Object, Object>::apply as a boxed Integer without a per-call
      // reflective boxing dance.
      final var pojo = new WithPrimitives(42, 1_000_000_000_000L, true);
      final Object age = Beans.readProperty(pojo, "age");
      assertEquals(Integer.class, age.getClass());
      assertEquals(42, age);
      final Object count = Beans.readProperty(pojo, "count");
      assertEquals(Long.class, count.getClass());
      assertEquals(1_000_000_000_000L, count);
      final Object flagged = Beans.readProperty(pojo, "flagged");
      assertEquals(Boolean.class, flagged.getClass());
      assertEquals(Boolean.TRUE, flagged);
    }

    @Test
    @DisplayName("getter Getter<P, Object> also auto-boxes primitive returns (lattice-shape path)")
    void getterAutoBoxesPrimitives() {
      // Same auto-boxing guarantee through the lattice-primitive Getter<P, Object>, so the
      // capturing-lambda path stays equivalent to the direct readProperty path.
      final var pojo = new WithPrimitives(7, 99L, false);
      final var ageGetter = Beans.getter(WithPrimitives.class, "age");
      final Object age = ageGetter.get(pojo);
      assertEquals(Integer.class, age.getClass());
      assertEquals(7, age);
      final var flaggedGetter = Beans.getter(WithPrimitives.class, "flagged");
      final Object flagged = flaggedGetter.get(pojo);
      assertEquals(Boolean.class, flagged.getClass());
      assertEquals(Boolean.FALSE, flagged);
    }

    @Test
    @DisplayName("inherited getter resolves through the parent's declaring class (no cross-class lookup error)")
    void getterOnInheritedAccessor() {
      // ChildBean inherits getId() from ParentBean. The LMF cache must build the invoker via a
      // lookup pinned to ParentBean (the declaring class), not ChildBean — otherwise an
      // inheritor whose own package isn't open to telescope would fail to bind an accessor whose
      // declaring package IS open. This pins both the readProperty hot-path and the
      // lattice-primitive Getter.
      final var child = new ChildBean();
      child.setId("inherited-id");
      child.setName("only-on-child");
      assertEquals("inherited-id", Beans.readProperty(child, "id"));
      assertEquals("only-on-child", Beans.readProperty(child, "name"));
      assertEquals("inherited-id", Beans.<ChildBean>getter(ChildBean.class, "id").get(child));
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

    @Test
    @DisplayName("primitive-typed fields auto-unbox through the cached MethodHandle setter")
    void fieldsAutoUnboxesPrimitives() {
      final var writer = Beans.fieldsWriter(PrimitiveFields.class);
      // Boxed wrappers arrive via valueByName; the cached setter MH unboxes them per the same
      // implicit conversions a direct Field.set call would apply (the setter handle was bound
      // with field-typed signature at cache-warm time).
      final var values = Map.<String, Object>of(
        "i",
        Integer.valueOf(7),
        "l",
        Long.valueOf(42L),
        "d",
        Double.valueOf(3.14),
        "b",
        Boolean.TRUE
      );
      final var pojo = writer.construct(new String[] { "i", "l", "d", "b" }, values::get);
      assertEquals(7, pojo.getI());
      assertEquals(42L, pojo.getL());
      assertEquals(3.14, pojo.getD());
      assertEquals(true, pojo.isB());
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
      assertThrows(IllegalArgumentException.class, () -> writer.construct(new String[] { "name" }, n -> "x"));
    }

    @Test
    @DisplayName("settersWriter round-trips a value through an inherited setter (declared on a parent class)")
    void settersOnInheritedSetter() {
      // ChildBean inherits setId(String) from ParentBean. The LMF builder must use the setter's
      // declaring class (ParentBean) for privateLookupIn and the instantiated receiver — using
      // ChildBean's class would fail when the parent and child live in modules with different
      // opens directives. This test pins the inheritance-correctness contract.
      final var writer = Beans.settersWriter(ChildBean.class);
      final var values = Map.<String, Object>of("id", "parent-id", "name", "child-name");
      final var pojo = writer.construct(new String[] { "id", "name" }, values::get);
      assertEquals("parent-id", pojo.getId());
      assertEquals("child-name", pojo.getName());
    }

    @Test
    @DisplayName("LambdaMetafactory invoker auto-unboxes a boxed Integer source value into a setX(int) setter")
    void settersAutoUnboxesPrimitiveArg() {
      // NoArgSetters has setScore(int). The source map carries an Object value (boxed Integer);
      // the LambdaMetafactory-built BiConsumer<Object, Object> must auto-unbox to int. This pins
      // the primitive bridge that LambdaMetafactory's instantiatedMethodType (cls, Integer) ->
      // void generates — a Method.invoke regression would still work here, so the test value is
      // in exercising the unboxing bridge end-to-end.
      final var writer = Beans.settersWriter(NoArgSetters.class);
      final Map<String, Object> values = Map.of("name", "evan", "score", Integer.valueOf(42));
      final var pojo = writer.construct(new String[] { "name", "score" }, values::get);
      assertEquals("evan", pojo.getName());
      assertEquals(42, pojo.getScore());
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
      assertThrows(IllegalArgumentException.class, () -> writer.construct(new String[] { "ghost" }, n -> "x"));
    }

    @Test
    @DisplayName("primitive-arg setter (setScore(int)) auto-unboxes the boxed Integer value")
    void builderPrimitiveSetterUnboxes() {
      // Pin the LMF auto-unboxing path: the setter signature is `setScore(int)` but the value
      // flows through valueByName as a boxed `Integer`. The LMF-built BiFunction must unbox it
      // before dispatching to the primitive-int setter.
      final var writer = Beans.builderWriter(WithBuilder.class);
      final var pojo = writer.construct(new String[] { "name", "score" }, n ->
        n.equals("name") ? "x" : Integer.valueOf(42)
      );
      assertEquals("x", pojo.getName());
      assertEquals(42, pojo.getScore());
    }

    @Test
    @DisplayName("builderWriter supports a void-returning setter via the BiConsumer LMF binding")
    void builderSupportsVoidSetter() {
      // Classic JavaBean-style builder: setter mutates the builder in place and returns void.
      // BuilderWriter binds it as a BiConsumer<Object, Object> through LambdaMetafactory and
      // dispatches it alongside fluent BiFunction setters. build() doesn't care about the setter
      // return type.
      final var writer = Beans.builderWriter(BuilderWithVoidSetter.class);
      final var pojo = writer.construct(new String[] { "id" }, n -> "x");
      assertEquals("x", pojo.getId());
    }

    @Test
    @DisplayName(
      "construct propagates dispatch-time failures raw (matching the other writer strategies and pre-LMF BuilderWriter)"
    )
    void builderConstructPropagatesDispatchFailures() {
      // A String value flows into a setter expecting an int — the LMF auto-unbox bridge throws
      // ClassCastException at setter-dispatch time. Dispatch-time exceptions propagate raw,
      // matching the FIELDS / SETTERS / CONSTRUCTOR strategies and the pre-LMF BuilderWriter
      // (which only wrapped `ReflectiveOperationException`, a class that doesn't exist on the
      // LMF hot path). Class-context information lives in the bind-time failure messages, not
      // the dispatch path.
      final var writer = Beans.builderWriter(WithBuilder.class);
      assertThrows(ClassCastException.class, () ->
        writer.construct(new String[] { "name", "score" }, n -> n.equals("score") ? "not-an-int" : "x")
      );
    }

    @Test
    @DisplayName("repeated construct calls reuse the same writer (LMF setter invokers cached per name)")
    void builderReusesAcrossConstructs() {
      // The setter invokers map is populated lazily on first use of each name and reused on
      // subsequent calls. A second construct on the same writer must not rebuild the LMF call site.
      final var writer = Beans.builderWriter(WithBuilder.class);
      final var first = writer.construct(new String[] { "name", "score" }, n -> n.equals("name") ? "a" : 1);
      final var second = writer.construct(new String[] { "name", "score" }, n -> n.equals("name") ? "b" : 2);
      assertEquals("a", first.getName());
      assertEquals(1, first.getScore());
      assertEquals("b", second.getName());
      assertEquals(2, second.getScore());
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

    @Test
    @DisplayName("primitive-typed ctor args auto-unbox through the cached spread MethodHandle")
    void constructorAutoUnboxesPrimitives() {
      final var writer = Beans.constructorWriter(PrimitiveCtor.class, 4);
      // Boxed wrappers arrive via valueByName; the spread MH unboxes them per the implicit
      // conversions a direct constructor call would apply. Order matches the constructor
      // parameter order (positional fallback when -parameters is not present at test compile
      // time, or by-name when it is — both produce the same result here).
      final var values = Map.<String, Object>of(
        "i",
        Integer.valueOf(5),
        "l",
        Long.valueOf(99L),
        "d",
        Double.valueOf(2.5),
        "b",
        Boolean.TRUE
      );
      final var pojo = writer.construct(new String[] { "i", "l", "d", "b" }, values::get);
      assertEquals(5, pojo.getI());
      assertEquals(99L, pojo.getL());
      assertEquals(2.5, pojo.getD());
      assertEquals(true, pojo.isB());
    }
  }

  // ----- autoWriter ladder -----

  @Nested
  @DisplayName("autoWriter — strategy ladder + cache + 4th-rung constructor fallback")
  class AutoWriter {

    @Test
    @DisplayName("autoWriter picks BuilderWriter when a static builder() is present (no setters)")
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
    @DisplayName("autoWriter prefers SettersWriter over BuilderWriter when both apply (@Data @Builder shape)")
    void autoPrefersSettersOverBuilder() {
      final var writer = Beans.autoWriter(DataAndBuilder.class);
      assertEquals(
        "SettersWriter",
        writer.getClass().getSimpleName(),
        "for Lombok @Data @Builder POJOs, SETTERS is the user-expected default — the public setters " +
          "and the builder both apply, and SETTERS wins so no explicit writeBean hint is required"
      );
      final var pojo = writer.construct(new String[] { "name", "score" }, n -> Objects.equals(n, "name") ? "y" : 42);
      assertEquals("y", pojo.getName());
      assertEquals(42, pojo.getScore());
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

    @Test
    @DisplayName("unknown property fails at construction time, not at first read")
    void lensRejectsUnknownPropertyEagerly() {
      // The reader is resolved at construction time; an unknown property must surface immediately
      // (rather than on the first get/set), so a misconfigured lens fails at build time.
      final var ex = assertThrows(IllegalArgumentException.class, () ->
        Beans.lens(NoArgSetters.class, "doesNotExist", Beans.autoWriter(NoArgSetters.class))
      );
      assertTrue(ex.getMessage().contains("doesNotExist"), ex.getMessage());
      assertTrue(ex.getMessage().contains(NoArgSetters.class.getName()), ex.getMessage());
    }

    @Test
    @DisplayName("subclass polymorphism: lens applied to a subclass instance reads via the slow path")
    void lensWorksOnSubclassInstance() {
      // The fast path is the monomorphic case (source.getClass() == pojoClass). A Lens<Parent, ?>
      // applied to a ChildBean instance must fall through to readProperty so subclass polymorphism
      // is preserved — the captured reader was built for ParentBean but ChildBean inherits the
      // getter and readProperty resolves the GETTER_INVOKERS entry for the runtime class.
      final io.github.eschizoid.telescope.internal.optics.Lens<ParentBean, String> lens = Beans.lens(
        ParentBean.class,
        "id",
        Beans.autoWriter(ParentBean.class)
      );
      final var child = new ChildBean();
      child.setId("p1");
      child.setName("alice");
      assertEquals("p1", lens.get(child));
    }
  }

  @Nested
  @DisplayName("persistentClassOf — HibernateProxy-aware cache key unwrap")
  class PersistentClass {

    static class PlainBean {

      private String name;

      public PlainBean() {}

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }
    }

    // Subclass that pretends to be a Hibernate-style proxy. Doesn't implement HibernateProxy
    // (Hibernate isn't on the test classpath), so persistentClassOf takes the fall-through path
    // and returns the runtime class. This pins the no-Hibernate-classpath behaviour: zero-cost
    // when the framework isn't there.
    static final class ProxyShape extends PlainBean {}

    @Test
    @DisplayName("falls through to getClass() when the value is null")
    void nullSafe() {
      assertEquals(null, Beans.persistentClassOf(null));
    }

    @Test
    @DisplayName("plain POJO: returns getClass() unchanged")
    void plainPojoReturnsItsOwnClass() {
      final var bean = new PlainBean();
      assertEquals(PlainBean.class, Beans.persistentClassOf(bean));
    }

    @Test
    @DisplayName("subclass without HibernateProxy interface: returns the subclass (fall-through)")
    void noHibernateOnClasspath() {
      // ProxyShape doesn't implement HibernateProxy — there's no Hibernate dep in :core's tests.
      // The unwrap helper must return the runtime class, not crash, not call any Hibernate API.
      final var proxy = new ProxyShape();
      assertEquals(ProxyShape.class, Beans.persistentClassOf(proxy));
    }

    @Test
    @DisplayName("readProperty routes through persistentClassOf so a subclass shares the parent's cache entry")
    void readPropertyUnwrapsToParentCache() {
      // Even without a real HibernateProxy, prove readProperty works on a subclass instance —
      // the cache lookup uses the runtime class (or the unwrapped persistent class). This is the
      // regression for the cache-key shape: a single instance should read correctly via either
      // its own class or a parent class's cached getter.
      final var proxy = new ProxyShape();
      proxy.setName("alice");
      assertEquals("alice", Beans.readProperty(proxy, "name"));
    }
  }
}
