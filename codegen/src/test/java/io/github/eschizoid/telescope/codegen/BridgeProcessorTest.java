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
        compilation.hasError("@Bridge is only supported on records, classes, or sealed interfaces"),
        () -> "expected records/classes/sealed-interfaces diagnostic; saw " + compilation.errorMessages()
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

  @Nested
  @DisplayName("Sealed roots — pattern-match dispatch over per-case bridges")
  class SealedRoots {

    @Test
    @DisplayName("sealed interface ↔ sealed interface emits a switch over permits that delegates to per-case bridges")
    void sealedToSealed() {
      final var compilation = compile(
        source(
          "demo.payment.Payment",
          """
          package demo.payment;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import demo.bean.PaymentEntity;
          @Bridge(PaymentEntity.class)
          public sealed interface Payment permits CreditCard, PayPal {}
          """
        ),
        source(
          "demo.payment.CreditCard",
          """
          package demo.payment;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import demo.bean.CreditCardEntity;
          @Bridge(CreditCardEntity.class)
          public record CreditCard(String number, String holder) implements Payment {}
          """
        ),
        source(
          "demo.payment.PayPal",
          """
          package demo.payment;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import demo.bean.PayPalEntity;
          @Bridge(PayPalEntity.class)
          public record PayPal(String email, String token) implements Payment {}
          """
        ),
        source(
          "demo.bean.PaymentEntity",
          """
          package demo.bean;
          public sealed interface PaymentEntity permits CreditCardEntity, PayPalEntity {}
          """
        ),
        source(
          "demo.bean.CreditCardEntity",
          """
          package demo.bean;
          public final class CreditCardEntity implements PaymentEntity {
            private String number; private String holder;
            public CreditCardEntity() {}
            public String getNumber() { return number; }
            public void setNumber(String n) { this.number = n; }
            public String getHolder() { return holder; }
            public void setHolder(String h) { this.holder = h; }
          }
          """
        ),
        source(
          "demo.bean.PayPalEntity",
          """
          package demo.bean;
          public final class PayPalEntity implements PaymentEntity {
            private String email; private String token;
            public PayPalEntity() {}
            public String getEmail() { return email; }
            public void setEmail(String e) { this.email = e; }
            public String getToken() { return token; }
            public void setToken(String t) { this.token = t; }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());

      final var sealedBridge = compilation.generated().get("demo.payment.PaymentBridge");
      assertNotNull(sealedBridge, () -> "PaymentBridge not generated; saw " + compilation.generated().keySet());
      // Forward fans out to per-case bridges via a static Match dispatcher routed through the
      // internal lattice's Prism substrate. Exhaustiveness is verified at class-load by
      // .exhaustive() reading getPermittedSubclasses().
      assertTrue(
        sealedBridge.contains(".when(demo.payment.CreditCard.class, demo.payment.CreditCardBridge::forward)"),
        sealedBridge
      );
      assertTrue(
        sealedBridge.contains(".when(demo.payment.PayPal.class, demo.payment.PayPalBridge::forward)"),
        sealedBridge
      );
      // Backward dispatches on the bean side's permits via the same Match shape.
      assertTrue(
        sealedBridge.contains(".when(demo.bean.CreditCardEntity.class, demo.payment.CreditCardBridge::backward)"),
        sealedBridge
      );
      assertTrue(
        sealedBridge.contains(".when(demo.bean.PayPalEntity.class, demo.payment.PayPalBridge::backward)"),
        sealedBridge
      );
      // .exhaustive() terminal verifies the sealed-permits coverage at class-init time.
      assertTrue(sealedBridge.contains(".exhaustive();"), sealedBridge);
      // The Function<S, T> static fields are what the forward/backward methods delegate to.
      assertTrue(
        sealedBridge.contains("private static final Function<demo.payment.Payment, demo.bean.PaymentEntity> FORWARD ="),
        sealedBridge
      );
      // The umbrella BRIDGE constant exposes the composed Telescope at the sealed-root level.
      assertTrue(
        sealedBridge.contains(
          "public static final Telescope<demo.payment.Payment, demo.bean.PaymentEntity> BRIDGE = Telescope.bridge(new Fn());"
        ),
        sealedBridge
      );

      // Per-case bridges should also be emitted (drive by their own @Bridge annotations).
      assertNotNull(compilation.generated().get("demo.payment.CreditCardBridge"), "CreditCardBridge missing");
      assertNotNull(compilation.generated().get("demo.payment.PayPalBridge"), "PayPalBridge missing");
    }

    @Test
    @DisplayName("sealed source with non-sealed target is an error")
    void nonSealedTargetIsRejected() {
      final var compilation = compile(
        source(
          "demo.A",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.B.class)
          public sealed interface A permits Aa {}
          """
        ),
        source(
          "demo.Aa",
          """
          package demo;
          public record Aa(String x) implements A {}
          """
        ),
        source(
          "demo.B",
          """
          package demo;
          public record B(String x) {}
          """
        )
      );

      assertFalse(compilation.success(), "sealed-source + non-sealed-target should fail");
      assertTrue(
        compilation.hasError("requires the target to also be a sealed interface"),
        () -> "expected sealed-target diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("permit case missing @Bridge is an error")
    void caseMissingBridgeIsRejected() {
      final var compilation = compile(
        source(
          "demo.A",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.B.class)
          public sealed interface A permits Aa {}
          """
        ),
        source(
          "demo.Aa",
          """
          package demo;
          public record Aa(String x) implements A {}
          """
        ),
        source(
          "demo.B",
          """
          package demo;
          public sealed interface B permits Bb {}
          """
        ),
        source(
          "demo.Bb",
          """
          package demo;
          public record Bb(String x) implements B {}
          """
        )
      );

      assertFalse(compilation.success(), "missing per-case @Bridge should fail");
      assertTrue(
        compilation.hasError("must itself be @Bridge-annotated"),
        () -> "expected per-case-required diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("permit case's @Bridge target is not a permit of the sealed target — error")
    void caseTargetNotInPermitsIsRejected() {
      final var compilation = compile(
        source(
          "demo.A",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.B.class)
          public sealed interface A permits Aa {}
          """
        ),
        source(
          "demo.Aa",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.Stranger.class)
          public record Aa(String x) implements A {}
          """
        ),
        source(
          "demo.B",
          """
          package demo;
          public sealed interface B permits Bb {}
          """
        ),
        source(
          "demo.Bb",
          """
          package demo;
          public record Bb(String x) implements B {}
          """
        ),
        source(
          "demo.Stranger",
          """
          package demo;
          public record Stranger(String x) {}
          """
        )
      );

      assertFalse(compilation.success(), "off-permits target should fail");
      assertTrue(
        compilation.hasError("is not a permits of sealed target"),
        () -> "expected off-permits diagnostic; saw " + compilation.errorMessages()
      );
    }
  }

  @Nested
  @DisplayName("Repeatable — multiple @Bridge on one source")
  class Repeatable {

    @Test
    @DisplayName("two @Bridge targets on one record emit two bridges with the long-form naming")
    void twoBridgesOnOneRecord() {
      final var compilation = compile(
        source(
          "demo.Product",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.ProductEntity.class)
          @Bridge(demo.ProductDto.class)
          public record Product(String id, String name) {}
          """
        ),
        source(
          "demo.ProductEntity",
          """
          package demo;
          public class ProductEntity {
            private String id; private String name;
            public ProductEntity() {}
            public String getId() { return id; }
            public void setId(String id) { this.id = id; }
            public String getName() { return name; }
            public void setName(String name) { this.name = name; }
          }
          """
        ),
        source(
          "demo.ProductDto",
          """
          package demo;
          public record ProductDto(String id, String name) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());

      // Multi-target → long-form naming for BOTH bridges, no short-form ProductBridge.
      assertNull(compilation.generated().get("demo.ProductBridge"), "no short-form ProductBridge when multi-target");
      final var entityBridge = compilation.generated().get("demo.ProductToProductEntityBridge");
      final var dtoBridge = compilation.generated().get("demo.ProductToProductDtoBridge");
      assertNotNull(
        entityBridge,
        () -> "ProductToProductEntityBridge missing; saw " + compilation.generated().keySet()
      );
      assertNotNull(dtoBridge, () -> "ProductToProductDtoBridge missing; saw " + compilation.generated().keySet());

      assertTrue(
        entityBridge.contains(
          "public static final Telescope<demo.Product, demo.ProductEntity> BRIDGE = Telescope.bridge(new Fn());"
        ),
        entityBridge
      );
      assertTrue(
        dtoBridge.contains(
          "public static final Telescope<demo.Product, demo.ProductDto> BRIDGE = Telescope.bridge(new Fn());"
        ),
        dtoBridge
      );
    }

    @Test
    @DisplayName("single @Bridge still uses the short-form <Source>Bridge naming (backwards-compatible)")
    void singleBridgeUsesShortName() {
      final var compilation = compile(
        source(
          "demo.Plain",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.Other.class)
          public record Plain(String id) {}
          """
        ),
        source(
          "demo.Other",
          """
          package demo;
          public record Other(String id) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      assertNotNull(compilation.generated().get("demo.PlainBridge"), "short-form PlainBridge expected");
    }
  }

  @Nested
  @DisplayName("Drops — source field excluded from a bridge")
  class Drops {

    @Test
    @DisplayName("drop on a source-only field: forward skips, backward fills null")
    void dropFillsNullOnBackward() {
      final var compilation = compile(
        source(
          "demo.Order",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(value = demo.OrderEntity.class, drops = {"payment"})
          public record Order(String id, String customer, String payment) {}
          """
        ),
        source(
          "demo.OrderEntity",
          """
          package demo;
          public record OrderEntity(String id, String customer) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.OrderBridge");
      assertNotNull(bridge, () -> "OrderBridge missing; saw " + compilation.generated().keySet());

      // Forward: build OrderEntity from non-dropped source fields only — `payment` never appears.
      assertTrue(bridge.contains("new demo.OrderEntity(s.id(), s.customer())"), bridge);
      // Backward: build Order with `null` for the dropped `payment` slot.
      assertTrue(bridge.contains("new demo.Order(t.id(), t.customer(), null)"), bridge);
    }

    @Test
    @DisplayName("drop on a primitive field fills the type's zero on backward")
    void dropPrimitiveFillsZero() {
      final var compilation = compile(
        source(
          "demo.A",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(value = demo.B.class, drops = {"age", "active"})
          public record A(String id, int age, boolean active) {}
          """
        ),
        source(
          "demo.B",
          """
          package demo;
          public record B(String id) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.ABridge");
      assertNotNull(bridge, () -> "ABridge missing; saw " + compilation.generated().keySet());
      // age (int) → 0, active (boolean) → false in the backward rebuild.
      assertTrue(bridge.contains("new demo.A(t.id(), 0, false)"), bridge);
    }

    @Test
    @DisplayName("misspelled drop name is a compile error pointing at the source")
    void misspelledDropIsRejected() {
      final var compilation = compile(
        source(
          "demo.A",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(value = demo.B.class, drops = {"payement"})
          public record A(String id, String payment) {}
          """
        ),
        source(
          "demo.B",
          """
          package demo;
          public record B(String id) {}
          """
        )
      );

      assertFalse(compilation.success(), "misspelled drop name should fail");
      assertTrue(
        compilation.hasError("not a field of A"),
        () -> "expected misspelled-drop diagnostic; saw " + compilation.errorMessages()
      );
    }
  }

  @Nested
  @DisplayName("Renames — source/target field name remapping")
  class Renames {

    @Test
    @DisplayName("@Rename matches source.orderNumber to target.referenceCode in both directions")
    void renameMapsBothDirections() {
      final var compilation = compile(
        source(
          "demo.Order",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Rename;
          @Bridge(value = demo.OrderEntity.class, renames = {
            @Rename(source = "orderNumber", target = "referenceCode")
          })
          public record Order(Long id, String orderNumber) {}
          """
        ),
        source(
          "demo.OrderEntity",
          """
          package demo;
          public record OrderEntity(Long id, String referenceCode) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.OrderBridge");
      assertNotNull(bridge, () -> "OrderBridge missing; saw " + compilation.generated().keySet());

      // Forward: build OrderEntity reading source.orderNumber() into the referenceCode slot.
      assertTrue(bridge.contains("new demo.OrderEntity(s.id(), s.orderNumber())"), bridge);
      // Backward: build Order reading target.referenceCode() into the orderNumber slot.
      assertTrue(bridge.contains("new demo.Order(t.id(), t.referenceCode())"), bridge);
    }

    @Test
    @DisplayName("misspelled rename source is a compile error pointing at the source")
    void misspelledRenameSourceIsRejected() {
      final var compilation = compile(
        source(
          "demo.A",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Rename;
          @Bridge(value = demo.B.class, renames = {@Rename(source = "ordreNumber", target = "referenceCode")})
          public record A(Long id, String orderNumber) {}
          """
        ),
        source(
          "demo.B",
          """
          package demo;
          public record B(Long id, String referenceCode) {}
          """
        )
      );

      assertFalse(compilation.success(), "misspelled rename source should fail");
      assertTrue(
        compilation.hasError("source=\"ordreNumber\" is not a field of A"),
        () -> "expected misspelled-source diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("misspelled rename target is a compile error pointing at the source")
    void misspelledRenameTargetIsRejected() {
      final var compilation = compile(
        source(
          "demo.A",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Rename;
          @Bridge(value = demo.B.class, renames = {@Rename(source = "orderNumber", target = "refrenceCode")})
          public record A(Long id, String orderNumber) {}
          """
        ),
        source(
          "demo.B",
          """
          package demo;
          public record B(Long id, String referenceCode) {}
          """
        )
      );

      assertFalse(compilation.success(), "misspelled rename target should fail");
      assertTrue(
        compilation.hasError("target=\"refrenceCode\" is not a field of B"),
        () -> "expected misspelled-target diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("a field listed in BOTH renames and drops is a compile error")
    void renameAndDropOnSameFieldIsRejected() {
      final var compilation = compile(
        source(
          "demo.A",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Rename;
          @Bridge(
            value = demo.B.class,
            drops = {"orderNumber"},
            renames = {@Rename(source = "orderNumber", target = "referenceCode")}
          )
          public record A(Long id, String orderNumber) {}
          """
        ),
        source(
          "demo.B",
          """
          package demo;
          public record B(Long id, String referenceCode) {}
          """
        )
      );

      assertFalse(compilation.success(), "rename + drop on the same field should fail");
      assertTrue(
        compilation.hasError("appears in both renames and drops"),
        () -> "expected dual-membership diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("@Transform per-field conversion routes through the BridgeFn class in both directions")
    void transformRoutesThroughBridgeFn() {
      final var compilation = compile(
        source(
          "demo.CentsConverter",
          """
          package demo;
          import io.github.eschizoid.telescope.conversion.BridgeFn;
          import java.math.BigDecimal;
          public final class CentsConverter implements BridgeFn<BigDecimal, Long> {
            public CentsConverter() {}
            @Override public Long forward(BigDecimal x) { return x.movePointRight(2).longValueExact(); }
            @Override public BigDecimal backward(Long c) { return BigDecimal.valueOf(c).movePointLeft(2); }
          }
          """
        ),
        source(
          "demo.LineItem",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Transform;
          import java.math.BigDecimal;
          @Bridge(value = demo.LineItemEntity.class, transforms = {
            @Transform(field = "unitPrice", using = demo.CentsConverter.class)
          })
          public record LineItem(String id, BigDecimal unitPrice) {}
          """
        ),
        source(
          "demo.LineItemEntity",
          """
          package demo;
          public record LineItemEntity(String id, Long unitPrice) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.LineItemBridge");
      assertNotNull(bridge, () -> "LineItemBridge missing; saw " + compilation.generated().keySet());

      // Static instance of the user's BridgeFn.
      assertTrue(
        bridge.contains("private static final demo.CentsConverter __tx_unitPrice = new demo.CentsConverter();"),
        bridge
      );
      // Forward routes the source field through .forward(...).
      assertTrue(bridge.contains("new demo.LineItemEntity(s.id(), __tx_unitPrice.forward(s.unitPrice()))"), bridge);
      // Backward routes the target field through .backward(...).
      assertTrue(bridge.contains("new demo.LineItem(t.id(), __tx_unitPrice.backward(t.unitPrice()))"), bridge);
    }

    @Test
    @DisplayName("@Transform(forwardOnly=true) — backward zero-fills the slot, BridgeFn.backward is never invoked")
    void transformForwardOnlyEmitsZeroFill() {
      final var compilation = compile(
        source(
          "demo.AuditTimestampFn",
          """
          package demo;
          import io.github.eschizoid.telescope.conversion.BridgeFn;
          import java.time.Instant;
          public final class AuditTimestampFn implements BridgeFn<Instant, String> {
            public AuditTimestampFn() {}
            @Override public String forward(Instant x) { return x.toString(); }
            // Backward stubbed — never invoked when forwardOnly=true. The generated bridge
            // emits a zero-fill instead of calling this method.
            @Override public Instant backward(String s) { throw new UnsupportedOperationException(); }
          }
          """
        ),
        source(
          "demo.Audit",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Transform;
          import java.time.Instant;
          @Bridge(value = demo.AuditEntity.class, transforms = {
            @Transform(field = "createdAt", using = demo.AuditTimestampFn.class, forwardOnly = true)
          })
          public record Audit(String id, Instant createdAt) {}
          """
        ),
        source(
          "demo.AuditEntity",
          """
          package demo;
          public record AuditEntity(String id, String createdAt) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.AuditBridge");
      assertNotNull(bridge, () -> "AuditBridge missing; saw " + compilation.generated().keySet());

      // Forward routes through the BridgeFn unchanged.
      assertTrue(bridge.contains("new demo.AuditEntity(s.id(), __tx_createdAt.forward(s.createdAt()))"), bridge);
      // Backward emits null (the reference-type zero-fill) for the forward-only slot — does NOT
      // call __tx_createdAt.backward(t.createdAt()).
      assertTrue(
        bridge.contains("new demo.Audit(t.id(), null)"),
        () -> "expected backward zero-fill for forwardOnly transform slot, saw: " + bridge
      );
      assertTrue(
        !bridge.contains("__tx_createdAt.backward"),
        () -> "backward must NOT invoke BridgeFn.backward for forwardOnly transform, saw: " + bridge
      );
    }

    @Test
    @DisplayName("misspelled transform field is a compile error pointing at the source")
    void misspelledTransformFieldIsRejected() {
      final var compilation = compile(
        source(
          "demo.Conv",
          """
          package demo;
          import io.github.eschizoid.telescope.conversion.BridgeFn;
          public final class Conv implements BridgeFn<String, String> {
            public Conv() {}
            @Override public String forward(String x) { return x; }
            @Override public String backward(String x) { return x; }
          }
          """
        ),
        source(
          "demo.A",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Transform;
          @Bridge(value = demo.B.class, transforms = {
            @Transform(field = "nmae", using = demo.Conv.class)
          })
          public record A(String name) {}
          """
        ),
        source(
          "demo.B",
          """
          package demo;
          public record B(String name) {}
          """
        )
      );

      assertFalse(compilation.success(), "misspelled transform field should fail");
      assertTrue(
        compilation.hasError("transforms field=\"nmae\" is not a field of A"),
        () -> "expected misspelled-transform diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("@Constant injects a String literal at a target-only field on forward")
    void constantStringInjectsForwardOnly() {
      final var compilation = compile(
        source(
          "demo.LineItem",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Constant;
          @Bridge(value = demo.LineItemEntity.class, constants = {
            @Constant(field = "source", value = "API")
          })
          public record LineItem(String id) {}
          """
        ),
        source(
          "demo.LineItemEntity",
          """
          package demo;
          public record LineItemEntity(String id, String source) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.LineItemBridge");
      assertNotNull(bridge, () -> "LineItemBridge missing; saw " + compilation.generated().keySet());

      // Forward fills the target's `source` slot with the literal.
      assertTrue(bridge.contains("new demo.LineItemEntity(s.id(), \"API\")"), bridge);
      // Backward rebuilds LineItem from t.id() only — source is target-only, dropped on backward.
      assertTrue(bridge.contains("new demo.LineItem(t.id())"), bridge);
    }

    @Test
    @DisplayName("@Constant supports primitive types — int parses and emits as a literal")
    void constantIntPrimitiveEmitsAsLiteral() {
      final var compilation = compile(
        source(
          "demo.A",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Constant;
          @Bridge(value = demo.B.class, constants = {@Constant(field = "version", value = "1")})
          public record A(String id) {}
          """
        ),
        source(
          "demo.B",
          """
          package demo;
          public record B(String id, int version) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.ABridge");
      assertTrue(bridge.contains("new demo.B(s.id(), 1)"), bridge);
    }

    @Test
    @DisplayName("@Constant value=\"null\" is accepted for reference-typed target fields")
    void constantNullForReferenceType() {
      final var compilation = compile(
        source(
          "demo.A",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Constant;
          @Bridge(value = demo.B.class, constants = {@Constant(field = "note", value = "null")})
          public record A(String id) {}
          """
        ),
        source(
          "demo.B",
          """
          package demo;
          public record B(String id, String note) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.ABridge");
      assertTrue(bridge.contains("new demo.B(s.id(), null)"), bridge);
    }

    @Test
    @DisplayName("@Constant value that does not parse against the target type is a compile error")
    void constantValueMismatchIsRejected() {
      final var compilation = compile(
        source(
          "demo.A",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Constant;
          @Bridge(value = demo.B.class, constants = {@Constant(field = "version", value = "v2")})
          public record A(String id) {}
          """
        ),
        source(
          "demo.B",
          """
          package demo;
          public record B(String id, int version) {}
          """
        )
      );

      assertFalse(compilation.success(), "non-parseable constant value should fail");
      assertTrue(
        compilation.hasError("is not a valid int literal"),
        () -> "expected parse-fail diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("@Constant for a non-existent target field is a compile error")
    void constantWithMissingTargetFieldIsRejected() {
      final var compilation = compile(
        source(
          "demo.A",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Constant;
          @Bridge(value = demo.B.class, constants = {@Constant(field = "ghost", value = "x")})
          public record A(String id) {}
          """
        ),
        source(
          "demo.B",
          """
          package demo;
          public record B(String id) {}
          """
        )
      );

      assertFalse(compilation.success(), "constant against a missing target field should fail");
      assertTrue(
        compilation.hasError("constants field=\"ghost\" is not a field of B"),
        () -> "expected missing-field diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("@Compute calls Supplier.get() at the forward target slot")
    void computeCallsSupplierGet() {
      final var compilation = compile(
        source(
          "demo.NowSupplier",
          """
          package demo;
          import java.util.function.Supplier;
          public final class NowSupplier implements Supplier<String> {
            public NowSupplier() {}
            @Override public String get() { return "now"; }
          }
          """
        ),
        source(
          "demo.A",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Compute;
          @Bridge(value = demo.B.class, computes = {@Compute(field = "createdAt", using = demo.NowSupplier.class)})
          public record A(String id) {}
          """
        ),
        source(
          "demo.B",
          """
          package demo;
          public record B(String id, String createdAt) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.ABridge");
      assertNotNull(bridge, () -> "ABridge missing; saw " + compilation.generated().keySet());

      assertTrue(
        bridge.contains("private static final demo.NowSupplier __cp_createdAt = new demo.NowSupplier();"),
        bridge
      );
      assertTrue(bridge.contains("new demo.B(s.id(), __cp_createdAt.get())"), bridge);
      // Backward — A has no createdAt to recover, so the source rebuild only reads t.id().
      assertTrue(bridge.contains("new demo.A(t.id())"), bridge);
    }

    @Test
    @DisplayName("two renames cannot share the same source or the same target")
    void duplicateRenameSourceIsRejected() {
      final var compilation = compile(
        source(
          "demo.A",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Rename;
          @Bridge(value = demo.B.class, renames = {
            @Rename(source = "name", target = "a"),
            @Rename(source = "name", target = "b")
          })
          public record A(String name) {}
          """
        ),
        source(
          "demo.B",
          """
          package demo;
          public record B(String a, String b) {}
          """
        )
      );

      assertFalse(compilation.success(), "duplicate rename source should fail");
      assertTrue(
        compilation.hasError("source \"name\" appears twice"),
        () -> "expected duplicate-source diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("@Rename(forwardOnly=true) fan-out: one source feeds multiple targets; backward reads primary only")
    void forwardOnlyFanoutEmitsMultiTargetWrite() {
      final var compilation = compile(
        source(
          "demo.Audit",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Rename;
          @Bridge(value = demo.AuditEntity.class, renames = {
            @Rename(source = "businessUnit", target = "cretnUserId",  forwardOnly = true),
            @Rename(source = "businessUnit", target = "lastUpdtdUserId", forwardOnly = true)
          })
          public record Audit(String businessUnit) {}
          """
        ),
        source(
          "demo.AuditEntity",
          """
          package demo;
          public record AuditEntity(String cretnUserId, String lastUpdtdUserId) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.AuditBridge");
      assertNotNull(bridge, () -> "AuditBridge missing; saw " + compilation.generated().keySet());

      // Forward: source.businessUnit() flows into BOTH target columns positionally.
      assertTrue(
        bridge.contains("new demo.AuditEntity(s.businessUnit(), s.businessUnit())"),
        () -> "forward must fan source into every target; saw: " + bridge
      );
      // Backward: only the FIRST declared fan-out target (cretnUserId) reconstructs the source.
      assertTrue(
        bridge.contains("new demo.Audit(t.cretnUserId())"),
        () -> "backward must read the primary fan-out target only; saw: " + bridge
      );
    }

    @Test
    @DisplayName("@Rename source-collision is still rejected if any conflicting entry omits forwardOnly")
    void forwardOnlyMustBeSetOnEveryConflictingRename() {
      final var compilation = compile(
        source(
          "demo.A",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Rename;
          @Bridge(value = demo.B.class, renames = {
            @Rename(source = "name", target = "a",  forwardOnly = true),
            @Rename(source = "name", target = "b") // forwardOnly defaults to false — opt-in required
          })
          public record A(String name) {}
          """
        ),
        source(
          "demo.B",
          """
          package demo;
          public record B(String a, String b) {}
          """
        )
      );

      assertFalse(compilation.success(), "partial forwardOnly opt-in should fail");
      assertTrue(
        compilation.hasError("set forwardOnly = true on every conflicting"),
        () -> "expected partial-opt-in diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("@Rename forwardOnly fan-out rejects targets with mismatched types")
    void forwardOnlyFanoutRejectsTypeMismatch() {
      final var compilation = compile(
        source(
          "demo.A",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Rename;
          @Bridge(value = demo.B.class, renames = {
            @Rename(source = "value", target = "asString", forwardOnly = true),
            @Rename(source = "value", target = "asInt",    forwardOnly = true)
          })
          public record A(String value) {}
          """
        ),
        source(
          "demo.B",
          """
          package demo;
          public record B(String asString, int asInt) {}
          """
        )
      );

      assertFalse(compilation.success(), "type-mismatched fan-out should fail");
      assertTrue(
        compilation.hasError("fans out to targets with different types"),
        () -> "expected type-mismatch diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("@Rename forwardOnly fan-out rejects an extra target that doesn't exist on the target side")
    void forwardOnlyFanoutRejectsMissingExtraTarget() {
      final var compilation = compile(
        source(
          "demo.A",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Rename;
          @Bridge(value = demo.B.class, renames = {
            @Rename(source = "name", target = "a",        forwardOnly = true),
            @Rename(source = "name", target = "missingX", forwardOnly = true)
          })
          public record A(String name) {}
          """
        ),
        source(
          "demo.B",
          """
          package demo;
          public record B(String a, String b) {}
          """
        )
      );

      assertFalse(compilation.success(), "missing extra target should fail");
      assertTrue(
        compilation.hasError("target=\"missingX\""),
        () -> "expected missing-extra-target diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("@Default(field, value) — forward null-coalesces, backward is identity")
    void defaultsNullCoalescing() {
      final var compilation = compile(
        source(
          "demo.User",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Default;
          @Bridge(value = demo.UserEntity.class, defaults = {
            @Default(field = "region", value = "EMEA")
          })
          public record User(String id, String region) {}
          """
        ),
        source(
          "demo.UserEntity",
          """
          package demo;
          public record UserEntity(String id, String region) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.UserBridge");
      assertNotNull(bridge, () -> "UserBridge missing; saw " + compilation.generated().keySet());

      // Forward null-coalesces: source `region` null → "EMEA"; otherwise pass-through.
      assertTrue(
        bridge.contains("(s.region() == null ? \"EMEA\" : s.region())"),
        () -> "expected null-coalesce on region forward, saw: " + bridge
      );
      // Backward is identity — the default doesn't appear in backward expression.
      assertTrue(bridge.contains("new demo.User(t.id(), t.region())"), bridge);
    }

    @Test
    @DisplayName("@Default on a primitive-typed source field is a compile error")
    void defaultsOnPrimitiveRejected() {
      final var compilation = compile(
        source(
          "demo.A",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Default;
          @Bridge(value = demo.B.class, defaults = {
            @Default(field = "count", value = "0")
          })
          public record A(String name, int count) {}
          """
        ),
        source(
          "demo.B",
          """
          package demo;
          public record B(String name, int count) {}
          """
        )
      );

      assertFalse(compilation.success(), "@Default on primitive should fail");
      assertTrue(
        compilation.hasError("primitives cannot be null"),
        () -> "expected primitive-rejection diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("FD-1: @Default + @Transform on the same field rejected at build")
    void defaultsAndTransformsOnSameFieldRejected() {
      final var compilation = compile(
        source(
          "demo.NoopFn",
          """
          package demo;
          import io.github.eschizoid.telescope.conversion.BridgeFn;
          public final class NoopFn implements BridgeFn<String, String> {
            public NoopFn() {}
            @Override public String forward(String x) { return x; }
            @Override public String backward(String x) { return x; }
          }
          """
        ),
        source(
          "demo.A",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Default;
          import io.github.eschizoid.telescope.annotations.Transform;
          @Bridge(
            value = demo.B.class,
            transforms = { @Transform(field = "name", using = demo.NoopFn.class) },
            defaults   = { @Default(field   = "name", value = "anonymous") }
          )
          public record A(String name) {}
          """
        ),
        source(
          "demo.B",
          """
          package demo;
          public record B(String name) {}
          """
        )
      );

      assertFalse(compilation.success(), "defaults + transforms overlap should fail");
      assertTrue(
        compilation.hasError("appears in both defaults and transforms"),
        () -> "expected overlap diagnostic; saw " + compilation.errorMessages()
      );
    }
  }
}
