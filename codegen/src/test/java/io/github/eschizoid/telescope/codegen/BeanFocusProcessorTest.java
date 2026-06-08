package io.github.eschizoid.telescope.codegen;

import static io.github.eschizoid.telescope.codegen.ProcessorHarness.source;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link BeanFocusProcessor} through the shared {@link ProcessorHarness}. Asserts on the
 * shape of the generated fluent navigator: {@code <Pojo>Path<R>} with {@code start()}, {@code
 * get()}, and per-property methods (scalar terminal / sub-bean-Path / container step), for both
 * rebuild strategies (static {@code builder()} and no-arg constructor + setters), plus the guards.
 */
class BeanFocusProcessorTest {

  private static Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compile(new BeanFocusProcessor(), sources);
  }

  @Nested
  @DisplayName("Happy path — navigator shape across rebuild strategies")
  class HappyPath {

    @Test
    @DisplayName("builder() POJO: navigator method rebuilds via builder(), swapping only the focused property")
    void builderStrategy() {
      final var compilation = compile(
        source(
          "demo.BuilderPojo",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.BeanFocus;
          @BeanFocus
          public class BuilderPojo {
            private final String id;
            private final String email;
            private BuilderPojo(String id, String email) { this.id = id; this.email = email; }
            public String getId() { return id; }
            public String getEmail() { return email; }
            public static Builder builder() { return new Builder(); }
            public static final class Builder {
              private String id;
              private String email;
              public Builder id(String id) { this.id = id; return this; }
              public Builder email(String email) { this.email = email; return this; }
              public BuilderPojo build() { return new BuilderPojo(id, email); }
            }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.BuilderPojoPath");
      assertNotNull(generated, () -> "BuilderPojoPath not generated; saw " + compilation.generated().keySet());

      assertTrue(generated.contains("public final class BuilderPojoPath<R>"), generated);
      assertTrue(generated.contains("public static BuilderPojoPath<BuilderPojo> start()"), generated);
      assertTrue(generated.contains("public Telescope<R, String> id()"), generated);
      assertTrue(generated.contains("Telescope.lens(BuilderPojo::getId,"), generated);
      assertTrue(generated.contains("BuilderPojo.builder()"), generated);
      assertTrue(generated.contains(".build()"), generated);
      // The focused property takes v; siblings are read back from p.
      assertTrue(generated.contains("id(v)"), generated);
      assertTrue(generated.contains("email(v)"), generated);
      assertTrue(generated.contains("email(p.getEmail())"), generated);
    }

    @Test
    @DisplayName("setter POJO: navigator method rebuilds via no-arg ctor + setX")
    void setterStrategy() {
      final var compilation = compile(
        source(
          "demo.SetterPojo",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.BeanFocus;
          @BeanFocus
          public class SetterPojo {
            private String id;
            private String email;
            public SetterPojo() {}
            public String getId() { return id; }
            public String getEmail() { return email; }
            public void setId(String id) { this.id = id; }
            public void setEmail(String email) { this.email = email; }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.SetterPojoPath");
      assertNotNull(generated, () -> "SetterPojoPath not generated; saw " + compilation.generated().keySet());

      assertTrue(generated.contains("public final class SetterPojoPath<R>"), generated);
      assertTrue(generated.contains("new SetterPojo()"), generated);
      assertTrue(generated.contains("c.setId(v)"), generated);
      assertTrue(generated.contains("c.setEmail(v)"), generated);
      assertTrue(generated.contains("return c;"), generated);
    }

    @Test
    @DisplayName("int property surfaces as Telescope<R, Integer> on the navigator method")
    void primitiveIsBoxed() {
      final var compilation = compile(
        source(
          "demo.Counter",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.BeanFocus;
          @BeanFocus
          public class Counter {
            private int count;
            public Counter() {}
            public int getCount() { return count; }
            public void setCount(int count) { this.count = count; }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.CounterPath");
      assertNotNull(generated, () -> "CounterPath not generated; saw " + compilation.generated().keySet());

      assertTrue(generated.contains("public Telescope<R, Integer> count()"), generated);
      assertFalse(generated.contains("Telescope<R, int>"), generated);
    }

    @Test
    @DisplayName("a List property emits a Step whose each() returns a terminal Telescope")
    void listOfScalarsEachReturnsTerminal() {
      final var compilation = compile(
        source(
          "demo.Roster",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.BeanFocus;
          @BeanFocus
          public class Roster {
            private java.util.List<String> names;
            public Roster() {}
            public java.util.List<String> getNames() { return names; }
            public void setNames(java.util.List<String> names) { this.names = names; }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var step = compilation.generated().get("demo.RosterNamesStep");
      assertNotNull(step, () -> "RosterNamesStep not generated; saw " + compilation.generated().keySet());
      assertTrue(step.contains("public final class RosterNamesStep<R>"), step);
      assertTrue(step.contains("public Telescope<R, String> each()"), step);

      final var path = compilation.generated().get("demo.RosterPath");
      assertNotNull(path);
      assertTrue(path.contains("public RosterNamesStep<R> names()"), path);
    }

    @Test
    @DisplayName("Bridge hop: a POJO with @BeanFocus + @Bridge gets as<Target>() chaining the bridge")
    void bridgeHop() {
      final var compilation = compile(
        source(
          "demo.UserBean",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.BeanFocus;
          @BeanFocus
          @Bridge(demo.UserDto.class)
          public class UserBean {
            private String id;
            public UserBean() {}
            public String getId() { return id; }
            public void setId(String id) { this.id = id; }
          }
          """
        ),
        source(
          "demo.UserDto",
          """
          package demo;
          public record UserDto(String id) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.UserBeanPath");
      assertNotNull(generated, () -> "UserBeanPath not generated; saw " + compilation.generated().keySet());

      // Target is not @Focus'd (just a record) → terminal Telescope.
      assertTrue(generated.contains("public Telescope<R, demo.UserDto> asUserDto()"), generated);
      assertTrue(generated.contains("return path.then(UserBeanBridge.BRIDGE);"), generated);
    }

    @Test
    @DisplayName("a Map property's step exposes eachValue(); an Optional property's step exposes whenPresent()")
    void mapAndOptionalUseDistinctStepMethods() {
      final var compilation = compile(
        source(
          "demo.Store",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.BeanFocus;
          @BeanFocus
          public class Store {
            private java.util.Map<String, String> labels;
            private java.util.Optional<String> note;
            public Store() {}
            public java.util.Map<String, String> getLabels() { return labels; }
            public java.util.Optional<String> getNote() { return note; }
            public void setLabels(java.util.Map<String, String> labels) { this.labels = labels; }
            public void setNote(java.util.Optional<String> note) { this.note = note; }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());

      final var labelsStep = compilation.generated().get("demo.StoreLabelsStep");
      assertNotNull(labelsStep, () -> "StoreLabelsStep not generated; saw " + compilation.generated().keySet());
      assertTrue(labelsStep.contains("public Telescope<R, String> eachValue()"), labelsStep);

      final var noteStep = compilation.generated().get("demo.StoreNoteStep");
      assertNotNull(noteStep, () -> "StoreNoteStep not generated; saw " + compilation.generated().keySet());
      assertTrue(noteStep.contains("public Telescope<R, String> whenPresent()"), noteStep);
    }
  }

  @Nested
  @DisplayName("Rejections — guards raise compile errors")
  class Rejections {

    @Test
    @DisplayName("@BeanFocus on a record is an error (records use @Focus)")
    void recordIsRejected() {
      final var compilation = compile(
        source(
          "demo.RecPojo",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.BeanFocus;
          @BeanFocus
          public record RecPojo(String a) {}
          """
        )
      );

      assertFalse(compilation.success(), "a record @BeanFocus should fail");
      assertTrue(
        compilation.hasError("@BeanFocus is only supported on classes"),
        () -> "expected records-use-@Focus diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("@BeanFocus on a nested class is an error")
    void nestedClassIsRejected() {
      final var compilation = compile(
        source(
          "demo.Outer",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.BeanFocus;
          public class Outer {
            @BeanFocus
            public static class Inner {
              public Inner() {}
              public String getA() { return null; }
              public void setA(String a) {}
            }
          }
          """
        )
      );

      assertFalse(compilation.success(), "a nested @BeanFocus class should fail");
      assertTrue(
        compilation.hasError("@BeanFocus is only supported on top-level classes"),
        () -> "expected top-level diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("no readable properties is an error")
    void noPropertiesIsRejected() {
      final var compilation = compile(
        source(
          "demo.Empty",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.BeanFocus;
          @BeanFocus
          public class Empty {
            public Empty() {}
            public void run() {}
          }
          """
        )
      );

      assertFalse(compilation.success(), "a property-less @BeanFocus class should fail");
      assertTrue(
        compilation.hasError("has no readable properties"),
        () -> "expected no-properties diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("no builder and no no-arg constructor is an error (field injection unavailable to codegen)")
    void noStrategyIsRejected() {
      final var compilation = compile(
        source(
          "demo.Immutable",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.BeanFocus;
          @BeanFocus
          public class Immutable {
            private final String a;
            public Immutable(String a) { this.a = a; }
            public String getA() { return a; }
          }
          """
        )
      );

      assertFalse(compilation.success(), "an all-args-only @BeanFocus class should fail");
      assertTrue(
        compilation.hasError("needs a static builder() or a public no-arg constructor with setters"),
        () -> "expected no-strategy diagnostic; saw " + compilation.errorMessages()
      );
    }
  }

  @Nested
  @DisplayName("Metadata holder emission — sibling <X>Telescope")
  class MetadataHolder {

    @Test
    @DisplayName("emits a sibling <X>Telescope holder with one typed Telescope constant per property")
    void generatesTelescopeHolderForBean() {
      final var compilation = compile(
        source(
          "demo.Person",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.BeanFocus;
          @BeanFocus
          public class Person {
            private String name;
            private int age;
            public Person() {}
            public String getName() { return name; }
            public int getAge() { return age; }
            public void setName(String name) { this.name = name; }
            public void setAge(int age) { this.age = age; }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var holder = compilation.generated().get("demo.PersonTelescope");
      assertNotNull(holder, () -> "PersonTelescope not generated; saw " + compilation.generated().keySet());

      // Holder is a top-level public final class in the user's package, no instances permitted.
      assertTrue(holder.contains("public final class PersonTelescope"), holder);
      assertTrue(holder.contains("private PersonTelescope() {}"), holder);

      // One static-final constant per property, with the property type as the Telescope's second
      // type parameter (primitive `int` is boxed to Integer).
      assertTrue(holder.contains("public static final Telescope<Person, String> name"), holder);
      assertTrue(holder.contains("public static final Telescope<Person, Integer> age"), holder);

      // Each constant uses Telescope.lens(...) with the same no-arg-ctor + setX rebuild expression
      // the Path navigator would emit.
      assertTrue(holder.contains("Telescope.lens(Person::getName,"), holder);
      assertTrue(holder.contains("Telescope.lens(Person::getAge,"), holder);
      assertTrue(holder.contains("new Person()"), holder);
      assertTrue(holder.contains("c.setName("), holder);
      assertTrue(holder.contains("c.setAge("), holder);

      // Standard javadoc and Telescope import.
      assertTrue(holder.contains("import io.github.eschizoid.telescope.Telescope;"), holder);
      assertTrue(holder.contains("Per-property Telescope constants for runtime hybrid dispatch"), holder);
    }

    @Test
    @DisplayName("builder() POJO: constants rebuild via the builder chain (same as the Path navigator)")
    void telescopeHolderForBuilderBean() {
      final var compilation = compile(
        source(
          "demo.BuilderPojo",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.BeanFocus;
          @BeanFocus
          public class BuilderPojo {
            private final String id;
            private final String email;
            private BuilderPojo(String id, String email) { this.id = id; this.email = email; }
            public String getId() { return id; }
            public String getEmail() { return email; }
            public static Builder builder() { return new Builder(); }
            public static final class Builder {
              private String id;
              private String email;
              public Builder id(String id) { this.id = id; return this; }
              public Builder email(String email) { this.email = email; return this; }
              public BuilderPojo build() { return new BuilderPojo(id, email); }
            }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var holder = compilation.generated().get("demo.BuilderPojoTelescope");
      assertNotNull(holder, () -> "BuilderPojoTelescope not generated; saw " + compilation.generated().keySet());

      assertTrue(holder.contains("public static final Telescope<BuilderPojo, String> id"), holder);
      assertTrue(holder.contains("public static final Telescope<BuilderPojo, String> email"), holder);
      assertTrue(holder.contains("BuilderPojo.builder()"), holder);
      assertTrue(holder.contains(".build()"), holder);
    }

    @Test
    @DisplayName("a container-shaped property surfaces as a raw Telescope<X, Container<E>> constant (not lifted)")
    void telescopeHolderForBeanContainerProperty() {
      final var compilation = compile(
        source(
          "demo.Bag",
          """
          package demo;
          import java.util.List;
          import io.github.eschizoid.telescope.annotations.BeanFocus;
          @BeanFocus
          public class Bag {
            private List<String> tags;
            public Bag() {}
            public List<String> getTags() { return tags; }
            public void setTags(List<String> tags) { this.tags = tags; }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var holder = compilation.generated().get("demo.BagTelescope");
      assertNotNull(holder, () -> "BagTelescope not generated; saw " + compilation.generated().keySet());

      // Raw container lens on the holder — the Path's container step lifts; the holder does not.
      // Consumers compose via .then(...) if they want element-level navigation.
      assertTrue(holder.contains("public static final Telescope<Bag, List<String>> tags"), holder);
      assertTrue(holder.contains("import java.util.List;"), holder);
    }

    @Test
    @DisplayName("a sub-@BeanFocus property surfaces as Telescope<X, SubBean> — terminal-to-sub-bean, not composed")
    void telescopeHolderForBeanSubBeanProperty() {
      final var compilation = compile(
        source(
          "demo.UserA",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.BeanFocus;
          @BeanFocus
          public class UserA {
            private String name;
            private demo.AddressB address;
            public UserA() {}
            public String getName() { return name; }
            public demo.AddressB getAddress() { return address; }
            public void setName(String name) { this.name = name; }
            public void setAddress(demo.AddressB address) { this.address = address; }
          }
          """
        ),
        source(
          "demo.AddressB",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.BeanFocus;
          @BeanFocus
          public class AddressB {
            private String city;
            public AddressB() {}
            public String getCity() { return city; }
            public void setCity(String city) { this.city = city; }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var holder = compilation.generated().get("demo.UserATelescope");
      assertNotNull(holder, () -> "UserATelescope not generated; saw " + compilation.generated().keySet());

      // Sub-bean property is just a typed lens to the sub-value; no composition with the
      // sub-bean's own holder (consumers compose via .then(...) themselves).
      assertTrue(holder.contains("public static final Telescope<UserA, demo.AddressB> address"), holder);
      assertTrue(holder.contains("Telescope.lens(UserA::getAddress,"), holder);
    }

    @Test
    @DisplayName("a property with wildcard-bound generics is rejected with a precise diagnostic")
    void wildcardBeanGenericsRejected() {
      final var compilation = compile(
        source(
          "demo.Wild",
          """
          package demo;
          import java.util.List;
          import io.github.eschizoid.telescope.annotations.BeanFocus;
          @BeanFocus
          public class Wild {
            private List<? extends Comparable<?>> values;
            public Wild() {}
            public List<? extends Comparable<?>> getValues() { return values; }
            public void setValues(List<? extends Comparable<?>> values) { this.values = values; }
          }
          """
        )
      );

      assertFalse(compilation.success(), "compilation should have failed for wildcard-bound generics");
      assertTrue(
        compilation.hasError("cannot emit metadata constant"),
        () -> "expected wildcard diagnostic; saw " + compilation.errorMessages()
      );
      assertTrue(
        compilation.hasError("wildcard or self-referential bounds"),
        () -> "expected wildcard diagnostic; saw " + compilation.errorMessages()
      );
      assertFalse(
        compilation.generated().containsKey("demo.WildTelescope"),
        "no Telescope holder should be generated for a rejected type"
      );
    }
  }

  @Nested
  @DisplayName("Metadata holder construct(...) emission")
  class MetadataHolderConstruct {

    @Test
    @DisplayName("setter POJO: construct calls the no-arg ctor then setX per property")
    void setterPojoConstruct() {
      final var compilation = compile(
        source(
          "demo.Person",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.BeanFocus;
          @BeanFocus
          public class Person {
            private String name;
            private int age;
            public Person() {}
            public String getName() { return name; }
            public int getAge() { return age; }
            public void setName(String name) { this.name = name; }
            public void setAge(int age) { this.age = age; }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var holder = compilation.generated().get("demo.PersonTelescope");
      assertNotNull(holder, () -> "PersonTelescope not generated; saw " + compilation.generated().keySet());

      // construct() signature.
      assertTrue(holder.contains("public static Person construct(final Function<String, Object> values)"), holder);
      // No-arg ctor + setX per property. The setter strategy chosen by emitBeanNavigator is
      // mirrored here verbatim.
      assertTrue(holder.contains("final var c = new Person();"), holder);
      assertTrue(holder.contains("c.setName((String) values.apply(\"name\"));"), holder);
      assertTrue(holder.contains("c.setAge((Integer) values.apply(\"age\"));"), holder);
      assertTrue(holder.contains("return c;"), holder);
      // Imports + @SuppressWarnings present.
      assertTrue(holder.contains("import java.util.function.Function;"), holder);
      assertTrue(holder.contains("@SuppressWarnings(\"unchecked\")"), holder);
    }

    @Test
    @DisplayName("builder() POJO: construct chains builder().setX(...).setY(...).build()")
    void builderPojoConstruct() {
      final var compilation = compile(
        source(
          "demo.BuilderPojo",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.BeanFocus;
          @BeanFocus
          public class BuilderPojo {
            private final String id;
            private final String email;
            private BuilderPojo(String id, String email) { this.id = id; this.email = email; }
            public String getId() { return id; }
            public String getEmail() { return email; }
            public static Builder builder() { return new Builder(); }
            public static final class Builder {
              private String id;
              private String email;
              public Builder id(String id) { this.id = id; return this; }
              public Builder email(String email) { this.email = email; return this; }
              public BuilderPojo build() { return new BuilderPojo(id, email); }
            }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var holder = compilation.generated().get("demo.BuilderPojoTelescope");
      assertNotNull(holder, () -> "BuilderPojoTelescope not generated; saw " + compilation.generated().keySet());

      // Builder chain mirrors the per-property lens setter strategy.
      assertTrue(holder.contains("public static BuilderPojo construct(final Function<String, Object> values)"), holder);
      assertTrue(holder.contains("return BuilderPojo.builder()"), holder);
      assertTrue(holder.contains(".id((String) values.apply(\"id\"))"), holder);
      assertTrue(holder.contains(".email((String) values.apply(\"email\"))"), holder);
      assertTrue(holder.contains(".build();"), holder);
    }
  }

  @Nested
  @DisplayName("Metadata holder constants() emission")
  class MetadataHolderConstantsMap {

    @Test
    @DisplayName("multi-property bean: emits Map.ofEntries with one entry per property")
    void multiPropertyConstantsMap() {
      final var compilation = compile(
        source(
          "demo.Person",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.BeanFocus;
          @BeanFocus
          public class Person {
            private String name;
            private int age;
            public Person() {}
            public String getName() { return name; }
            public int getAge() { return age; }
            public void setName(String name) { this.name = name; }
            public void setAge(int age) { this.age = age; }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var holder = compilation.generated().get("demo.PersonTelescope");
      assertNotNull(holder, () -> "PersonTelescope not generated; saw " + compilation.generated().keySet());

      assertTrue(holder.contains("public static Map<String, Telescope<?, ?>> constants()"), holder);
      assertTrue(holder.contains("Map.ofEntries("), holder);
      assertTrue(holder.contains("Map.entry(\"name\", name)"), holder);
      assertTrue(holder.contains("Map.entry(\"age\", age)"), holder);
      assertTrue(holder.contains("import java.util.Map;"), holder);
    }

    @Test
    @DisplayName("single-property bean: emits Map.of(...) instead of Map.ofEntries")
    void singlePropertyConstantsMap() {
      final var compilation = compile(
        source(
          "demo.Solo",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.BeanFocus;
          @BeanFocus
          public class Solo {
            private String only;
            public Solo() {}
            public String getOnly() { return only; }
            public void setOnly(String only) { this.only = only; }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var holder = compilation.generated().get("demo.SoloTelescope");
      assertNotNull(holder, () -> "SoloTelescope not generated; saw " + compilation.generated().keySet());

      assertTrue(holder.contains("public static Map<String, Telescope<?, ?>> constants()"), holder);
      assertTrue(holder.contains("return Map.of(\"only\", only);"), holder);
      assertFalse(holder.contains("Map.ofEntries"), "single-entry holders should use Map.of, not Map.ofEntries");
    }
  }
}
