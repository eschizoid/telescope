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
 * Drives {@link FromMapProcessor} through the shared {@link ProcessorHarness}. Asserts on the shape
 * of the generated reflection-free {@code <X>FromMap} converter: a {@code fromMap(Map)} static
 * method that rebuilds the target directly and a {@code FROM_MAP} ForwardMapper constant.
 */
class FromMapProcessorTest {

  private static Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compile(new FromMapProcessor(), sources);
  }

  @Nested
  @DisplayName("Happy path — record target")
  class RecordTarget {

    @Test
    @DisplayName("generates <X>FromMap with a fromMap(Map) method and a FROM_MAP ForwardMapper constant")
    void generatesConverterClass() {
      final var compilation = compile(
        source(
          "demo.User",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.FromMap;
          @FromMap
          public record User(String name, int age) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.UserFromMap");
      assertNotNull(generated, () -> "UserFromMap not generated; saw " + compilation.generated().keySet());

      assertTrue(generated.contains("public final class UserFromMap"), generated);
      assertTrue(generated.contains("public static User fromMap(final Map<String, Object> map)"), generated);
      assertTrue(
        generated.contains("public static final ForwardMapper<Map<String, Object>, User> FROM_MAP"),
        generated
      );
      // Direct canonical-constructor rebuild — no reflection.
      assertTrue(generated.contains("new User("), generated);
      // String field: read the key by name.
      assertTrue(generated.contains("map.get(\"name\")"), generated);
    }

    @Test
    @DisplayName("enum field coerces a String name via Enum.valueOf (taking an existing enum value directly)")
    void enumFieldCoercesViaValueOf() {
      final var compilation = compile(
        source(
          "demo.Account",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.FromMap;
          @FromMap
          public record Account(demo.Role role) {}
          """
        ),
        source("demo.Role", "package demo; public enum Role { ADMIN, USER }")
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.AccountFromMap");
      assertNotNull(generated, () -> "AccountFromMap not generated; saw " + compilation.generated().keySet());
      assertTrue(generated.contains("demo.Role.valueOf(String.valueOf(map.get(\"role\")))"), generated);
      assertTrue(generated.contains("instanceof demo.Role"), generated);
    }

    @Test
    @DisplayName("nested @FromMap field recurses through the nested type's generated converter")
    void nestedFromMapFieldRecurses() {
      final var compilation = compile(
        source(
          "demo.Profile",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.FromMap;
          @FromMap
          public record Profile(String handle, demo.Address address) {}
          """
        ),
        source(
          "demo.Address",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.FromMap;
          @FromMap
          public record Address(String city) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.ProfileFromMap");
      assertNotNull(generated, () -> "ProfileFromMap not generated; saw " + compilation.generated().keySet());
      // The nested input object recurses through the nested type's own generated converter.
      assertTrue(generated.contains("demo.AddressFromMap.fromMap("), generated);
    }

    @Test
    @DisplayName("List<@FromMap> field element-maps each entry through the element's generated converter")
    void listOfNestedElementMaps() {
      final var compilation = compile(
        source(
          "demo.Team",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.FromMap;
          import java.util.List;
          @FromMap
          public record Team(List<demo.Member> members) {}
          """
        ),
        source(
          "demo.Member",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.FromMap;
          @FromMap
          public record Member(String name) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.TeamFromMap");
      assertNotNull(generated, () -> "TeamFromMap not generated; saw " + compilation.generated().keySet());
      // Each element streamed through the element type's own generated converter.
      assertTrue(generated.contains(".stream()"), generated);
      assertTrue(generated.contains("demo.MemberFromMap.fromMap("), generated);
    }

    @Test
    @DisplayName("Set<E> field collects elements into a fresh set")
    void setFieldCollectsToSet() {
      final var compilation = compile(
        source(
          "demo.Labels",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.FromMap;
          import java.util.Set;
          @FromMap
          public record Labels(Set<String> tags) {}
          """
        )
      );
      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.LabelsFromMap");
      assertNotNull(generated, () -> "LabelsFromMap not generated; saw " + compilation.generated().keySet());
      assertTrue(generated.contains("instanceof java.util.Set<?>"), generated);
      assertTrue(generated.contains("toSet()"), generated);
    }

    @Test
    @DisplayName("Map<K, @FromMap> field coerces values through their converter, preserving keys")
    void mapFieldCoercesValues() {
      final var compilation = compile(
        source(
          "demo.Registry",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.FromMap;
          import java.util.Map;
          @FromMap
          public record Registry(Map<String, demo.Address> byCity) {}
          """
        ),
        source(
          "demo.Address",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.FromMap;
          @FromMap
          public record Address(String city) {}
          """
        )
      );
      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.RegistryFromMap");
      assertNotNull(generated, () -> "RegistryFromMap not generated; saw " + compilation.generated().keySet());
      assertTrue(generated.contains("entrySet()"), generated);
      assertTrue(generated.contains("demo.AddressFromMap.fromMap("), generated);
    }
  }

  @Nested
  @DisplayName("Rejections — fail at compile, not at runtime")
  class Rejections {

    @Test
    @DisplayName("a nested object field whose type isn't @FromMap is a compile error, not a runtime CCE")
    void nonFromMapNestedFieldRejected() {
      final var compilation = compile(
        source(
          "demo.Order",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.FromMap;
          @FromMap
          public record Order(demo.Customer customer) {}
          """
        ),
        source("demo.Customer", "package demo; public record Customer(String name) {}")
      );
      assertFalse(compilation.success(), "a non-@FromMap nested object must be rejected");
      assertTrue(compilation.hasError("isn't @FromMap"), () -> compilation.errorMessages().toString());
    }

    @Test
    @DisplayName("an unrecognized JDK type with no String factory is rejected with guidance")
    void unknownJdkTypeRejected() {
      final var compilation = compile(
        source(
          "demo.Doc",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.FromMap;
          @FromMap
          public record Doc(java.io.File path) {}
          """
        )
      );
      assertFalse(compilation.success(), "a JDK type with no String factory must be rejected, not cast");
      assertTrue(compilation.hasError("can't be built from a Map value"), () -> compilation.errorMessages().toString());
    }

    @Test
    @DisplayName("a collection subtype field (ArrayList<X>) is rejected — declare the interface")
    void collectionSubtypeRejected() {
      final var compilation = compile(
        source(
          "demo.Holder",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.FromMap;
          @FromMap
          public record Holder(java.util.ArrayList<String> items) {}
          """
        )
      );
      assertFalse(compilation.success(), "a collection subtype must be rejected");
      assertTrue(compilation.hasError("collection subtype"), () -> compilation.errorMessages().toString());
    }
  }

  @Nested
  @DisplayName("Bean target")
  class BeanTarget {

    @Test
    @DisplayName("POJO with no-arg constructor + setters rebuilds via new + setX")
    void pojoViaSetters() {
      final var compilation = compile(
        source(
          "demo.UserBean",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.FromMap;
          @FromMap
          public class UserBean {
            private String name;
            private int age;
            public String getName() { return name; }
            public void setName(final String name) { this.name = name; }
            public int getAge() { return age; }
            public void setAge(final int age) { this.age = age; }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.UserBeanFromMap");
      assertNotNull(generated, () -> "UserBeanFromMap not generated; saw " + compilation.generated().keySet());
      assertTrue(generated.contains("new UserBean()"), generated);
      assertTrue(generated.contains(".setName("), generated);
      assertTrue(generated.contains(".setAge("), generated);
    }
  }
}
