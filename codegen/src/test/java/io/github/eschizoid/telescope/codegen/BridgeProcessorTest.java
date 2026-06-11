package io.github.eschizoid.telescope.codegen;

import static io.github.eschizoid.telescope.codegen.ProcessorHarness.source;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link BridgeProcessor} through the shared {@link ProcessorHarness}. Covers every
 * type-pair combination (record&rarr;POJO, record&harr;record, POJO&harr;POJO), the construction
 * strategies, and the guards. The annotated type is the source: {@code @Bridge(Target.class)} on
 * {@code Source} generates {@code SourceBridge.BRIDGE : Telescope<Source, Target>}.
 */
class BridgeProcessorTest {

  private static Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compile(new BridgeProcessor(), sources);
  }

  @Nested
  @DisplayName("Happy path — type-pair combinations")
  class HappyPath {

    @Test
    @DisplayName("record -> POJO: forward builds the POJO via its name-matched constructor; backward the record")
    void recordToPojo() {
      final var compilation = compile(
        source(
          "demo.Rec",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.Pojo.class)
          public record Rec(String id, String email) {}
          """
        ),
        source(
          "demo.Pojo",
          """
          package demo;
          public class Pojo {
            private final String id;
            private final String email;
            public Pojo(String id, String email) { this.id = id; this.email = email; }
            public String getId() { return id; }
            public String getEmail() { return email; }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.RecBridge");
      assertNotNull(generated, () -> "RecBridge not generated; saw " + compilation.generated().keySet());

      assertTrue(
        generated.contains("public static final Telescope<demo.Rec, demo.Pojo> BRIDGE = Telescope.bridge(new Fn());"),
        generated
      );
      assertTrue(generated.contains("import io.github.eschizoid.telescope.conversion.BridgeFn;"), generated);
      assertTrue(
        generated.contains("private static final class Fn implements BridgeFn<demo.Rec, demo.Pojo>"),
        generated
      );
      assertTrue(generated.contains("return RecBridge.forward(s);"), generated);
      assertTrue(generated.contains("return RecBridge.backward(t);"), generated);
      assertTrue(generated.contains("public static demo.Pojo forward(final demo.Rec s)"), generated);
      assertTrue(generated.contains("public static demo.Rec backward(final demo.Pojo t)"), generated);
      assertTrue(generated.contains("new demo.Pojo(s.id(), s.email())"), generated);
      assertTrue(generated.contains("new demo.Rec(t.getId(), t.getEmail())"), generated);
    }

    @Test
    @DisplayName("record <-> record: both sides via canonical constructor")
    void recordToRecord() {
      final var compilation = compile(
        source(
          "demo.A",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.B.class)
          public record A(String id, int score) {}
          """
        ),
        source(
          "demo.B",
          """
          package demo;
          public record B(String id, int score) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.ABridge");
      assertNotNull(generated, () -> "ABridge not generated; saw " + compilation.generated().keySet());

      assertTrue(
        generated.contains("public static final Telescope<demo.A, demo.B> BRIDGE = Telescope.bridge(new Fn());"),
        generated
      );
      assertTrue(generated.contains("private static final class Fn implements BridgeFn<demo.A, demo.B>"), generated);
      assertTrue(generated.contains("return ABridge.forward(s);"), generated);
      assertTrue(generated.contains("return ABridge.backward(t);"), generated);
      assertTrue(generated.contains("new demo.B(s.id(), s.score())"), generated);
      assertTrue(generated.contains("new demo.A(t.id(), t.score())"), generated);
    }

    @Test
    @DisplayName("POJO <-> POJO: both sides via no-arg constructor + setters")
    void pojoToPojo() {
      final var compilation = compile(
        source(
          "demo.PA",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.PB.class)
          public class PA {
            private String id;
            public PA() {}
            public String getId() { return id; }
            public void setId(String id) { this.id = id; }
          }
          """
        ),
        source(
          "demo.PB",
          """
          package demo;
          public class PB {
            private String id;
            public PB() {}
            public String getId() { return id; }
            public void setId(String id) { this.id = id; }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.PABridge");
      assertNotNull(generated, () -> "PABridge not generated; saw " + compilation.generated().keySet());

      assertTrue(
        generated.contains("public static final Telescope<demo.PA, demo.PB> BRIDGE = Telescope.bridge(new Fn());"),
        generated
      );
      assertTrue(generated.contains("private static final class Fn implements BridgeFn<demo.PA, demo.PB>"), generated);
      assertTrue(generated.contains("return PABridge.forward(s);"), generated);
      assertTrue(generated.contains("return PABridge.backward(t);"), generated);
      assertTrue(generated.contains("new demo.PB()"), generated);
      assertTrue(generated.contains("out.setId(s.getId())"), generated);
      assertTrue(generated.contains("new demo.PA()"), generated);
      assertTrue(generated.contains("out.setId(t.getId())"), generated);
    }
  }

  @Nested
  @DisplayName("Deep recursion — sub-pair bridges auto-generated for nested type mismatches")
  class DeepRecursion {

    @Test
    @DisplayName("nested record↔record: parent @Bridge auto-emits a sub-bridge for the nested pair")
    void nestedRecordPairAutoBridge() {
      final var compilation = compile(
        source(
          "demo.Order",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.OrderDto.class)
          public record Order(String id, demo.Customer customer) {}
          """
        ),
        source(
          "demo.Customer",
          """
          package demo;
          public record Customer(String name, String email) {}
          """
        ),
        source(
          "demo.OrderDto",
          """
          package demo;
          public record OrderDto(String id, demo.CustomerDto customer) {}
          """
        ),
        source(
          "demo.CustomerDto",
          """
          package demo;
          public record CustomerDto(String name, String email) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      // The user-declared @Bridge: keeps the simple name.
      final var orderBridge = compilation.generated().get("demo.OrderBridge");
      assertNotNull(orderBridge, () -> "OrderBridge not generated; saw " + compilation.generated().keySet());
      // The auto-generated sub-bridge for Customer↔CustomerDto: uses the disambiguating naming.
      final var subBridge = compilation.generated().get("demo.CustomerToCustomerDtoBridge");
      assertNotNull(
        subBridge,
        () -> "CustomerToCustomerDtoBridge not generated; saw " + compilation.generated().keySet()
      );

      // OrderBridge.forward calls CustomerToCustomerDtoBridge.forward to convert the nested field.
      assertTrue(orderBridge.contains("CustomerToCustomerDtoBridge.forward(s.customer())"), orderBridge);
      assertTrue(orderBridge.contains("CustomerToCustomerDtoBridge.backward(t.customer())"), orderBridge);
      // The sub-bridge itself uses identity links for its same-typed name/email fields.
      assertTrue(subBridge.contains("new demo.CustomerDto(s.name(), s.email())"), subBridge);
      assertTrue(subBridge.contains("new demo.Customer(t.name(), t.email())"), subBridge);
    }

    @Test
    @DisplayName("List<X> ↔ List<Y> auto-lifts element-wise via a for-loop helper")
    void listContainerAutoLifts() {
      final var compilation = compile(
        source(
          "demo.Order",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import java.util.List;
          @Bridge(demo.OrderDto.class)
          public record Order(String id, List<demo.LineItem> items) {}
          """
        ),
        source(
          "demo.LineItem",
          """
          package demo;
          public record LineItem(String sku, int qty) {}
          """
        ),
        source(
          "demo.OrderDto",
          """
          package demo;
          import java.util.List;
          public record OrderDto(String id, List<demo.LineItemDto> items) {}
          """
        ),
        source(
          "demo.LineItemDto",
          """
          package demo;
          public record LineItemDto(String sku, int qty) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var orderBridge = compilation.generated().get("demo.OrderBridge");
      assertNotNull(orderBridge);
      assertNotNull(compilation.generated().get("demo.LineItemToLineItemDtoBridge"));
      assertTrue(orderBridge.contains("__fwd_items(s.items())"), orderBridge);
      assertTrue(orderBridge.contains("__bwd_items(t.items())"), orderBridge);
      assertTrue(orderBridge.contains("import java.util.ArrayList;"), orderBridge);
      assertTrue(orderBridge.contains("import java.util.List;"), orderBridge);
      assertTrue(
        orderBridge.contains("private static List<demo.LineItemDto> __fwd_items(final List<demo.LineItem> src)"),
        orderBridge
      );
      assertTrue(orderBridge.contains("new ArrayList<demo.LineItemDto>(src.size())"), orderBridge);
      assertTrue(orderBridge.contains("LineItemToLineItemDtoBridge.forward(x)"), orderBridge);
      assertTrue(orderBridge.contains("LineItemToLineItemDtoBridge.backward(x)"), orderBridge);
    }

    @Test
    @DisplayName("Set<X> ↔ Set<Y> auto-lifts via a for-loop helper into a pre-sized LinkedHashSet")
    void setContainerAutoLifts() {
      final var compilation = compile(
        source(
          "demo.Catalog",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import java.util.Set;
          @Bridge(demo.CatalogDto.class)
          public record Catalog(String name, Set<demo.Tag> tags) {}
          """
        ),
        source(
          "demo.Tag",
          """
          package demo;
          public record Tag(String label) {}
          """
        ),
        source(
          "demo.CatalogDto",
          """
          package demo;
          import java.util.Set;
          public record CatalogDto(String name, Set<demo.TagDto> tags) {}
          """
        ),
        source(
          "demo.TagDto",
          """
          package demo;
          public record TagDto(String label) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var catalog = compilation.generated().get("demo.CatalogBridge");
      assertNotNull(catalog);
      assertTrue(catalog.contains("__fwd_tags(s.tags())"), catalog);
      assertTrue(catalog.contains("__bwd_tags(t.tags())"), catalog);
      assertTrue(catalog.contains("import java.util.LinkedHashSet;"), catalog);
      assertTrue(catalog.contains("import java.util.Set;"), catalog);
      assertTrue(catalog.contains("new LinkedHashSet<demo.TagDto>(src.size())"), catalog);
      assertTrue(catalog.contains("TagToTagDtoBridge.forward(x)"), catalog);
      assertTrue(catalog.contains("TagToTagDtoBridge.backward(x)"), catalog);
    }

    @Test
    @DisplayName("Optional<X> ↔ Optional<Y> auto-lifts via .map(SubBridge::forward)")
    void optionalContainerAutoLifts() {
      final var compilation = compile(
        source(
          "demo.User",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import java.util.Optional;
          @Bridge(demo.UserDto.class)
          public record User(String id, Optional<demo.Profile> profile) {}
          """
        ),
        source(
          "demo.Profile",
          """
          package demo;
          public record Profile(String bio) {}
          """
        ),
        source(
          "demo.UserDto",
          """
          package demo;
          import java.util.Optional;
          public record UserDto(String id, Optional<demo.ProfileDto> profile) {}
          """
        ),
        source(
          "demo.ProfileDto",
          """
          package demo;
          public record ProfileDto(String bio) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var user = compilation.generated().get("demo.UserBridge");
      assertNotNull(user);
      assertTrue(user.contains("s.profile().map(ProfileToProfileDtoBridge::forward)"), user);
      assertTrue(user.contains("t.profile().map(ProfileToProfileDtoBridge::backward)"), user);
    }

    @Test
    @DisplayName("Map<K, V> ↔ Map<K, V'> auto-lifts values, preserves keys")
    void mapValuesAutoLift() {
      final var compilation = compile(
        source(
          "demo.Cart",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import java.util.Map;
          @Bridge(demo.CartDto.class)
          public record Cart(String id, Map<String, demo.LineItem> items) {}
          """
        ),
        source(
          "demo.LineItem",
          """
          package demo;
          public record LineItem(String sku, int qty) {}
          """
        ),
        source(
          "demo.CartDto",
          """
          package demo;
          import java.util.Map;
          public record CartDto(String id, Map<String, demo.LineItemDto> items) {}
          """
        ),
        source(
          "demo.LineItemDto",
          """
          package demo;
          public record LineItemDto(String sku, int qty) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var cart = compilation.generated().get("demo.CartBridge");
      assertNotNull(cart);
      assertTrue(cart.contains("__fwd_items(s.items())"), cart);
      assertTrue(cart.contains("__bwd_items(t.items())"), cart);
      assertTrue(cart.contains("import java.util.LinkedHashMap;"), cart);
      assertTrue(cart.contains("import java.util.Map;"), cart);
      assertTrue(cart.contains("new LinkedHashMap<java.lang.String, demo.LineItemDto>(src.size())"), cart);
      assertTrue(cart.contains("LineItemToLineItemDtoBridge.forward(e.getValue())"), cart);
      assertTrue(cart.contains("LineItemToLineItemDtoBridge.backward(e.getValue())"), cart);
    }

    @Test
    @DisplayName("Optional<X> ↔ nullable Y cross-paradigm bridge")
    void optionalToNullableCrossParadigm() {
      final var compilation = compile(
        source(
          "demo.Order",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import java.util.Optional;
          @Bridge(demo.OrderEntity.class)
          public record Order(String id, Optional<demo.Address> giftWrap) {}
          """
        ),
        source(
          "demo.Address",
          """
          package demo;
          public record Address(String street, String city) {}
          """
        ),
        source(
          "demo.OrderEntity",
          """
          package demo;
          public record OrderEntity(String id, demo.AddressEntity giftWrap) {}
          """
        ),
        source(
          "demo.AddressEntity",
          """
          package demo;
          public record AddressEntity(String street, String city) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var order = compilation.generated().get("demo.OrderBridge");
      assertNotNull(order);
      // Forward: Optional<Address>.map(AddressBridge::forward).orElse(null)
      assertTrue(order.contains("s.giftWrap().map(AddressToAddressEntityBridge::forward).orElse(null)"), order);
      // Backward: Optional.ofNullable(...).map(AddressBridge::backward)
      assertTrue(order.contains("import java.util.Optional;"), order);
      assertTrue(
        order.contains("Optional.ofNullable(t.giftWrap()).map(AddressToAddressEntityBridge::backward)"),
        order
      );
    }

    @Test
    @DisplayName("Self-referencing types compile-recurse exactly once via the seen-set; runtime recursion is fine")
    void cyclicTypeEmitsOnce() {
      final var compilation = compile(
        source(
          "demo.Node",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import java.util.Optional;
          @Bridge(demo.NodeDto.class)
          public record Node(String label, Optional<demo.Node> child) {}
          """
        ),
        source(
          "demo.NodeDto",
          """
          package demo;
          import java.util.Optional;
          public record NodeDto(String label, Optional<demo.NodeDto> child) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      // Only one bridge emits: NodeBridge. The cycle is at runtime, not compile time.
      final var node = compilation.generated().get("demo.NodeBridge");
      assertNotNull(node);
      assertNull(compilation.generated().get("demo.NodeToNodeDtoBridge")); // No auto-named dup.
      // Path through the Optional<Node> field references the same NodeBridge — runtime recursion
      // terminates on Optional.empty().
      assertTrue(node.contains("s.child().map(NodeBridge::forward)"), node);
      assertTrue(node.contains("t.child().map(NodeBridge::backward)"), node);
    }

    @Test
    @DisplayName("a user-declared @Bridge on the sub-pair is honoured — no duplicate emission, simple-name reference")
    void userDeclaredSubBridgeWins() {
      final var compilation = compile(
        source(
          "demo.Order",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.OrderDto.class)
          public record Order(String id, demo.Customer customer) {}
          """
        ),
        source(
          "demo.Customer",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.CustomerDto.class)
          public record Customer(String name, String email) {}
          """
        ),
        source(
          "demo.OrderDto",
          """
          package demo;
          public record OrderDto(String id, demo.CustomerDto customer) {}
          """
        ),
        source(
          "demo.CustomerDto",
          """
          package demo;
          public record CustomerDto(String name, String email) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var orderBridge = compilation.generated().get("demo.OrderBridge");
      assertNotNull(orderBridge);
      // User-declared sub @Bridge — keep the simple naming convention.
      final var customerBridge = compilation.generated().get("demo.CustomerBridge");
      assertNotNull(customerBridge, () -> "CustomerBridge not generated; saw " + compilation.generated().keySet());
      // No duplicate auto-generated sub-bridge.
      assertNull(compilation.generated().get("demo.CustomerToCustomerDtoBridge"));
      // OrderBridge references the user-declared CustomerBridge by simple name.
      assertTrue(orderBridge.contains("CustomerBridge.forward(s.customer())"), orderBridge);
      assertTrue(orderBridge.contains("CustomerBridge.backward(t.customer())"), orderBridge);
    }
  }

  @Nested
  @DisplayName("Rejections — guards raise compile errors")
  class Rejections {

    @Test
    @DisplayName("@Bridge on an enum (neither record nor class) is an error")
    void enumIsRejected() {
      final var compilation = compile(
        source(
          "demo.Color",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.Color.class)
          public enum Color { RED }
          """
        )
      );

      assertFalse(compilation.success(), "an enum @Bridge should fail");
      assertTrue(
        compilation.hasError("@Bridge is only supported on records and classes"),
        () -> "expected records-and-classes diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("@Bridge on a nested type is an error")
    void nestedIsRejected() {
      final var compilation = compile(
        source(
          "demo.Outer",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          public class Outer {
            @Bridge(demo.Outer.class)
            public record Inner(String a) {}
          }
          """
        )
      );

      assertFalse(compilation.success(), "a nested @Bridge should fail");
      assertTrue(
        compilation.hasError("@Bridge is only supported on top-level types"),
        () -> "expected top-level diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("mismatched field names (not a bijection) is an error")
    void fieldMismatchIsRejected() {
      final var compilation = compile(
        source(
          "demo.Src",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.Dst.class)
          public record Src(String id, String extra) {}
          """
        ),
        source(
          "demo.Dst",
          """
          package demo;
          public record Dst(String id) {}
          """
        )
      );

      assertFalse(compilation.success(), "a non-bijection @Bridge should fail");
      assertTrue(
        compilation.hasError("must expose the same field names"),
        () -> "expected bijection diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("a target with no usable construction strategy is an error")
    void noStrategyIsRejected() {
      final var compilation = compile(
        source(
          "demo.R",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.Immutable.class)
          public record R(String id) {}
          """
        ),
        source(
          "demo.Immutable",
          """
          package demo;
          public class Immutable {
            private final String id;
            // arity-2 ctor only: no arity-1 match, no builder(), no no-arg ctor.
            public Immutable(String id, String extra) { this.id = id; }
            public String getId() { return id; }
          }
          """
        )
      );

      assertFalse(compilation.success(), "a target with no construction strategy should fail");
      assertTrue(
        compilation.hasError("no usable construction strategy"),
        () -> "expected no-strategy diagnostic; saw " + compilation.errorMessages()
      );
    }
  }
}
