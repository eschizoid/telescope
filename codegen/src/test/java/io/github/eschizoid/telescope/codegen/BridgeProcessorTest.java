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
        generated.contains("public static final BridgeFn<demo.Rec, demo.Pojo> BRIDGE_FN = new Fn();"),
        () -> "expected a directly-callable BRIDGE_FN constant; saw " + generated
      );
      assertTrue(
        generated.contains("public static final Telescope<demo.Rec, demo.Pojo> BRIDGE = Telescope.bridge(BRIDGE_FN);"),
        () -> "BRIDGE should wrap the shared BRIDGE_FN constant; saw " + generated
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
        generated.contains("public static final Telescope<demo.A, demo.B> BRIDGE = Telescope.bridge(BRIDGE_FN);"),
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
        generated.contains("public static final Telescope<demo.PA, demo.PB> BRIDGE = Telescope.bridge(BRIDGE_FN);"),
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
    @DisplayName(
      "a field declared as a concrete List subtype (LinkedList<Y>) auto-lifts and the helper allocates that concrete class"
    )
    void concreteListSubtypeFieldAllocatesTargetClass() {
      // The runtime ContainerShape accepts any List subtype via isAssignableFrom and allocates the
      // target's concrete class; codegen must match — a field typed LinkedList<Y> should auto-lift
      // (not error) and the forward helper should new LinkedList<Y>, not the default ArrayList.
      final var compilation = compile(
        source(
          "demo.LLOrder",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import java.util.List;
          @Bridge(demo.LLOrderDto.class)
          public record LLOrder(List<demo.LLItem> items) {}
          """
        ),
        source(
          "demo.LLItem",
          """
          package demo;
          public record LLItem(String sku) {}
          """
        ),
        source(
          "demo.LLOrderDto",
          """
          package demo;
          import java.util.LinkedList;
          public record LLOrderDto(LinkedList<demo.LLItemDto> items) {}
          """
        ),
        source(
          "demo.LLItemDto",
          """
          package demo;
          public record LLItemDto(String sku) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.LLOrderBridge");
      assertNotNull(bridge);
      // The forward helper writes the target's concrete class (LinkedList), not the default
      // ArrayList, and its return type is the target's declared concrete type.
      assertTrue(bridge.contains("new LinkedList<demo.LLItemDto>"), bridge);
    }

    @Test
    @DisplayName(
      "a user-defined raw collection-subtype field (class Wrap extends ArrayList<Elem>) element-bridges into a fresh target wrapper"
    )
    void rawCollectionSubtypeFieldElementBridges() {
      // The adopter shape: a custom collection wrapper `class Wrap extends ArrayList<Elem>` whose
      // own
      // type-argument list is empty (the element lives in the supertype). The element pair is a
      // distinct, bridgeable record pair (SrcElem -> DstElem), so the field must element-bridge
      // into
      // a fresh target wrapper allocated via its no-arg constructor (subclasses don't inherit
      // ArrayList's copy constructor), not be bean-introspected.
      final var compilation = compile(
        source(
          "demo.RawOrder",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.RawOrderDto.class)
          public record RawOrder(demo.SrcWrap items) {}
          """
        ),
        source(
          "demo.SrcWrap",
          """
          package demo;
          import java.util.ArrayList;
          public class SrcWrap extends ArrayList<demo.SrcElem> {}
          """
        ),
        source(
          "demo.SrcElem",
          """
          package demo;
          public record SrcElem(String sku) {}
          """
        ),
        source(
          "demo.RawOrderDto",
          """
          package demo;
          public record RawOrderDto(demo.DstWrap items) {}
          """
        ),
        source(
          "demo.DstWrap",
          """
          package demo;
          import java.util.ArrayList;
          public class DstWrap extends ArrayList<demo.DstElem> {}
          """
        ),
        source(
          "demo.DstElem",
          """
          package demo;
          public record DstElem(String sku) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.RawOrderBridge");
      assertNotNull(bridge);
      // A sub-bridge is generated for the distinct element pair, and the forward copy allocates a
      // fresh target wrapper via its no-arg ctor (raw type, no diamond — the subtype is
      // non-generic).
      assertNotNull(compilation.generated().get("demo.SrcElemToDstElemBridge"));
      assertTrue(bridge.contains("new demo.DstWrap()"), bridge);
      assertTrue(bridge.contains("SrcElemToDstElemBridge.forward(x)"), bridge);
      // backward allocates a fresh source wrapper the same way.
      assertTrue(bridge.contains("new demo.SrcWrap()"), bridge);
      assertTrue(bridge.contains("SrcElemToDstElemBridge.backward(x)"), bridge);
    }

    @Test
    @DisplayName("raw collection-subtype with identity elements copies via addAll into a fresh wrapper (no sub-bridge)")
    void rawCollectionSubtypeIdentityElementCopiesViaAddAll() {
      final var compilation = compile(
        source(
          "demo.IdOrder",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.IdOrderDto.class)
          public record IdOrder(demo.SrcTags tags) {}
          """
        ),
        source(
          "demo.SrcTags",
          """
          package demo;
          import java.util.ArrayList;
          public class SrcTags extends ArrayList<String> {}
          """
        ),
        source(
          "demo.IdOrderDto",
          """
          package demo;
          public record IdOrderDto(demo.DstTags tags) {}
          """
        ),
        source(
          "demo.DstTags",
          """
          package demo;
          import java.util.ArrayList;
          public class DstTags extends ArrayList<String> {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.IdOrderBridge");
      assertNotNull(bridge);
      // Same element type → no sub-bridge, a verbatim addAll into a fresh target wrapper.
      assertNull(compilation.generated().get("demo.StringToStringBridge"));
      assertTrue(bridge.contains("new demo.DstTags()"), bridge);
      assertTrue(bridge.contains("out.addAll(src)"), bridge);
    }

    @Test
    @DisplayName("raw Set-subtype element-bridges into a fresh target set")
    void rawSetSubtypeElementBridges() {
      final var compilation = compile(
        source(
          "demo.SetOrder",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.SetOrderDto.class)
          public record SetOrder(demo.SrcSet items) {}
          """
        ),
        source(
          "demo.SrcSet",
          """
          package demo;
          import java.util.HashSet;
          public class SrcSet extends HashSet<demo.SElem> {}
          """
        ),
        source("demo.SElem", "package demo; public record SElem(String v) {}"),
        source(
          "demo.SetOrderDto",
          """
          package demo;
          public record SetOrderDto(demo.DstSet items) {}
          """
        ),
        source(
          "demo.DstSet",
          """
          package demo;
          import java.util.HashSet;
          public class DstSet extends HashSet<demo.DElem> {}
          """
        ),
        source("demo.DElem", "package demo; public record DElem(String v) {}")
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.SetOrderBridge");
      assertNotNull(bridge);
      assertNotNull(compilation.generated().get("demo.SElemToDElemBridge"));
      assertTrue(bridge.contains("new demo.DstSet()"), bridge);
      assertTrue(bridge.contains("SElemToDElemBridge.forward(x)"), bridge);
    }

    @Test
    @DisplayName("raw Map-subtype element-bridges values, preserves keys, into a fresh target map")
    void rawMapSubtypeElementBridgesValues() {
      final var compilation = compile(
        source(
          "demo.MapOrder",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.MapOrderDto.class)
          public record MapOrder(demo.SrcMeta meta) {}
          """
        ),
        source(
          "demo.SrcMeta",
          """
          package demo;
          import java.util.HashMap;
          public class SrcMeta extends HashMap<String, demo.MSElem> {}
          """
        ),
        source("demo.MSElem", "package demo; public record MSElem(String v) {}"),
        source(
          "demo.MapOrderDto",
          """
          package demo;
          public record MapOrderDto(demo.DstMeta meta) {}
          """
        ),
        source(
          "demo.DstMeta",
          """
          package demo;
          import java.util.HashMap;
          public class DstMeta extends HashMap<String, demo.MDElem> {}
          """
        ),
        source("demo.MDElem", "package demo; public record MDElem(String v) {}")
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.MapOrderBridge");
      assertNotNull(bridge);
      assertNotNull(compilation.generated().get("demo.MSElemToMDElemBridge"));
      assertTrue(bridge.contains("new demo.DstMeta()"), bridge);
      assertTrue(bridge.contains("out.put(e.getKey(), MSElemToMDElemBridge.forward(e.getValue()))"), bridge);
    }

    @Test
    @DisplayName("lenient propagates through the raw container branch to a non-bijection element pair")
    void rawCollectionSubtypeLenientPropagatesToElement() {
      // The adopter's reported shape carried lenient = true. Leniency must thread through the
      // raw-container branch into the element sub-pair, so a non-bijection element pair (the target
      // element has an extra field) compiles instead of failing the bijection check.
      final var compilation = compile(
        source(
          "demo.LenOrder",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(value = demo.LenOrderDto.class, lenient = true)
          public record LenOrder(demo.LenSrcWrap items) {}
          """
        ),
        source(
          "demo.LenSrcWrap",
          """
          package demo;
          import java.util.ArrayList;
          public class LenSrcWrap extends ArrayList<demo.LenSrcElem> {}
          """
        ),
        source("demo.LenSrcElem", "package demo; public record LenSrcElem(String a) {}"),
        source(
          "demo.LenOrderDto",
          """
          package demo;
          public record LenOrderDto(demo.LenDstWrap items) {}
          """
        ),
        source(
          "demo.LenDstWrap",
          """
          package demo;
          import java.util.ArrayList;
          public class LenDstWrap extends ArrayList<demo.LenDstElem> {}
          """
        ),
        source("demo.LenDstElem", "package demo; public record LenDstElem(String a, String extra) {}")
      );

      assertTrue(
        compilation.success(),
        () -> "lenient should propagate into the element pair: " + compilation.errorMessages()
      );
      assertNotNull(compilation.generated().get("demo.LenSrcElemToLenDstElemBridge"));
    }

    @Test
    @DisplayName(
      "mixed generic↔raw: List<X> ↔ raw ArrayList subtype element-bridges, backward allocates the interface default"
    )
    void mixedGenericAndRawCollectionElementBridges() {
      final var compilation = compile(
        source(
          "demo.MixOrder",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import java.util.List;
          @Bridge(demo.MixOrderDto.class)
          public record MixOrder(List<demo.MixSrc> items) {}
          """
        ),
        source("demo.MixSrc", "package demo; public record MixSrc(String v) {}"),
        source(
          "demo.MixOrderDto",
          """
          package demo;
          public record MixOrderDto(demo.MixWrap items) {}
          """
        ),
        source(
          "demo.MixWrap",
          """
          package demo;
          import java.util.ArrayList;
          public class MixWrap extends ArrayList<demo.MixDst> {}
          """
        ),
        source("demo.MixDst", "package demo; public record MixDst(String v) {}")
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.MixOrderBridge");
      assertNotNull(bridge);
      assertNotNull(compilation.generated().get("demo.MixSrcToMixDstBridge"));
      // forward allocates the concrete subtype; backward allocates the interface's default impl.
      assertTrue(bridge.contains("new demo.MixWrap()"), bridge);
      assertTrue(bridge.contains("new java.util.ArrayList<demo.MixSrc>()"), bridge);
    }

    @Test
    @DisplayName("mixed generic↔raw Map: Map<K, V> ↔ raw HashMap subtype, backward allocates the two-arg default impl")
    void mixedGenericAndRawMapElementBridges() {
      final var compilation = compile(
        source(
          "demo.MMixOrder",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import java.util.Map;
          @Bridge(demo.MMixOrderDto.class)
          public record MMixOrder(Map<String, demo.MMixSrc> meta) {}
          """
        ),
        source("demo.MMixSrc", "package demo; public record MMixSrc(String v) {}"),
        source(
          "demo.MMixOrderDto",
          """
          package demo;
          public record MMixOrderDto(demo.MMixWrap meta) {}
          """
        ),
        source(
          "demo.MMixWrap",
          """
          package demo;
          import java.util.HashMap;
          public class MMixWrap extends HashMap<String, demo.MMixDst> {}
          """
        ),
        source("demo.MMixDst", "package demo; public record MMixDst(String v) {}")
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.MMixOrderBridge");
      assertNotNull(bridge);
      assertNotNull(compilation.generated().get("demo.MMixSrcToMMixDstBridge"));
      // forward allocates the concrete subtype; backward allocates the Map interface's two-arg
      // default.
      assertTrue(bridge.contains("new demo.MMixWrap()"), bridge);
      assertTrue(bridge.contains("new java.util.HashMap<java.lang.String, demo.MMixSrc>()"), bridge);
    }

    @Test
    @DisplayName("a raw collection subtype with no public no-arg constructor is rejected with a telescope diagnostic")
    void rawCollectionSubtypeWithoutNoArgCtorIsRejected() {
      // The raw helper allocates `new Wrap()`; a subtype that hides the no-arg ctor would fail in
      // the
      // consumer's build with a raw javac error. The processor rejects it up front instead.
      final var compilation = compile(
        source(
          "demo.CtorSrc",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.CtorDst.class)
          public record CtorSrc(demo.CtorSrcWrap items) {}
          """
        ),
        source(
          "demo.CtorSrcWrap",
          """
          package demo;
          import java.util.ArrayList;
          public class CtorSrcWrap extends ArrayList<String> {}
          """
        ),
        source(
          "demo.CtorDst",
          """
          package demo;
          public record CtorDst(demo.NoCtorWrap items) {}
          """
        ),
        source(
          "demo.NoCtorWrap",
          """
          package demo;
          import java.util.ArrayList;
          public class NoCtorWrap extends ArrayList<String> {
            public NoCtorWrap(final int capacity) {
              super(capacity);
            }
          }
          """
        )
      );

      assertFalse(compilation.success(), "a ctor-less container subtype should be rejected");
      assertTrue(
        compilation.hasError("no public no-arg constructor"),
        () -> "expected the no-arg-ctor diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("raw Map-subtype pair with mismatched key types is an error")
    void rawMapSubtypeKeyMismatchIsRejected() {
      final var compilation = compile(
        source(
          "demo.KSrc",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.KDst.class)
          public record KSrc(demo.KSrcMap meta) {}
          """
        ),
        source(
          "demo.KSrcMap",
          """
          package demo;
          import java.util.HashMap;
          public class KSrcMap extends HashMap<String, String> {}
          """
        ),
        source(
          "demo.KDst",
          """
          package demo;
          public record KDst(demo.KDstMap meta) {}
          """
        ),
        source(
          "demo.KDstMap",
          """
          package demo;
          import java.util.HashMap;
          public class KDstMap extends HashMap<Integer, String> {}
          """
        )
      );

      assertFalse(compilation.success(), "a raw Map-subtype pair with mismatched keys should fail");
      assertTrue(
        compilation.hasError("Map key types must match exactly"),
        () -> "expected the key-type diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName(
      "identity element, concrete List subtype: List<String> ↔ LinkedList<String> emits an inline copy into each side's concrete class"
    )
    void identityElementConcreteListEmitsInlineConcreteCopy() {
      final var compilation = compile(
        source(
          "demo.ILOrder",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import java.util.List;
          @Bridge(demo.ILOrderDto.class)
          public record ILOrder(List<String> tags) {}
          """
        ),
        source(
          "demo.ILOrderDto",
          """
          package demo;
          import java.util.LinkedList;
          public record ILOrderDto(LinkedList<String> tags) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.ILOrderBridge");
      assertNotNull(bridge);
      // Identity element → no helper; an inline copy into the target's LinkedList (forward) and the
      // source's default ArrayList (backward).
      assertTrue(bridge.contains("new LinkedList<>("), bridge);
      assertTrue(bridge.contains("new ArrayList<>("), bridge);
    }

    @Test
    @DisplayName(
      "identity element, concrete Set subtype: Set<String> ↔ TreeSet<String> emits an inline copy into each side's concrete class"
    )
    void identityElementConcreteSetEmitsInlineConcreteCopy() {
      final var compilation = compile(
        source(
          "demo.ISOrder",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import java.util.Set;
          @Bridge(demo.ISOrderDto.class)
          public record ISOrder(Set<String> tags) {}
          """
        ),
        source(
          "demo.ISOrderDto",
          """
          package demo;
          import java.util.TreeSet;
          public record ISOrderDto(TreeSet<String> tags) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.ISOrderBridge");
      assertNotNull(bridge);
      assertTrue(bridge.contains("new TreeSet<>("), bridge);
      assertTrue(bridge.contains("new LinkedHashSet<>("), bridge);
    }

    @Test
    @DisplayName(
      "identity value, concrete Map subtype: Map<String, String> ↔ TreeMap<String, String> emits an inline copy into each side's concrete class"
    )
    void identityValueConcreteMapEmitsInlineConcreteCopy() {
      final var compilation = compile(
        source(
          "demo.IMOrder",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import java.util.Map;
          @Bridge(demo.IMOrderDto.class)
          public record IMOrder(Map<String, String> byKey) {}
          """
        ),
        source(
          "demo.IMOrderDto",
          """
          package demo;
          import java.util.TreeMap;
          public record IMOrderDto(TreeMap<String, String> byKey) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.IMOrderBridge");
      assertNotNull(bridge);
      // Forward into the target TreeMap; backward into the source's default HashMap.
      assertTrue(bridge.contains("new TreeMap<>("), bridge);
      assertTrue(bridge.contains("new HashMap<>("), bridge);
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
      // Both directions null-guard the Optional reference before .map(...), matching the runtime
      // Iso.liftOptional (ox == null ? null : ox.map(...)).
      assertTrue(
        user.contains("(s.profile() == null ? null : s.profile().map(ProfileToProfileDtoBridge::forward))"),
        user
      );
      assertTrue(
        user.contains("(t.profile() == null ? null : t.profile().map(ProfileToProfileDtoBridge::backward))"),
        user
      );
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
      assertTrue(cart.contains("import java.util.HashMap;"), cart);
      assertTrue(cart.contains("import java.util.Map;"), cart);
      assertTrue(cart.contains("new HashMap<java.lang.String, demo.LineItemDto>(src.size())"), cart);
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
      // Forward (OPTIONAL_TO_NULLABLE) reads the source Optional, so it null-guards the reference
      // before .map(...), matching the runtime Iso.liftOptionalToNullable (ox == null ? null :
      // ...).
      assertTrue(
        order.contains(
          "(s.giftWrap() == null ? null : s.giftWrap().map(AddressToAddressEntityBridge::forward).orElse(null))"
        ),
        order
      );
      // Backward: Optional.ofNullable(...) is already null-safe on the plain target value.
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
    @DisplayName(
      "a kind-mismatched Collection-subtype pair (List subtype vs Set subtype) is not bean-introspected — clean diagnostic, not 'no setter for empty'"
    )
    void kindMismatchedCollectionSubtypesAreNotBeanIntrospected() {
      // Same-kind Collection/Map subtype pairs are element-bridged (see DeepRecursion). A
      // kind-mismatched pair (List subtype vs Set subtype) cannot be — but both still clear the
      // qualified-name prefix filter, so without the guard the planner would recurse into one as a
      // bean and surface ArrayList's synthesized `isEmpty()` → property `empty` → no `setEmpty` →
      // the
      // misleading "no setter for 'empty'" error. The isReflectableDeclared exclusion makes it fall
      // to the accurate "no auto-bridge" diagnostic instead.
      final var compilation = compile(
        source(
          "demo.Src",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.Dst.class)
          public record Src(demo.SrcList items) {}
          """
        ),
        source(
          "demo.SrcList",
          """
          package demo;
          import java.util.ArrayList;
          public class SrcList extends ArrayList<String> {}
          """
        ),
        source(
          "demo.Dst",
          """
          package demo;
          public record Dst(demo.DstSet items) {}
          """
        ),
        source(
          "demo.DstSet",
          """
          package demo;
          import java.util.HashSet;
          public class DstSet extends HashSet<String> {}
          """
        )
      );

      assertFalse(compilation.success(), "a kind-mismatched Collection-subtype pair should fail");
      assertFalse(
        compilation.hasError("no setter for 'empty'"),
        () -> "must not leak the bean-introspection error; saw " + compilation.errorMessages()
      );
      assertTrue(
        compilation.hasError("no auto-bridge could be derived"),
        () -> "expected the clean no-auto-bridge diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("a kind-mismatched Map-subtype pair (Map subtype vs List subtype) is not bean-introspected either")
    void kindMismatchedMapSubtypeIsNotBeanIntrospected() {
      // Map subtype vs List subtype — kind mismatch, not element-bridgeable. Pins the
      // `java.util.Map` clause of the isReflectableDeclared exclusion guarding the HashMap `empty`
      // crash.
      final var compilation = compile(
        source(
          "demo.MSrc",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.MDst.class)
          public record MSrc(demo.SrcMap meta) {}
          """
        ),
        source(
          "demo.SrcMap",
          """
          package demo;
          import java.util.HashMap;
          public class SrcMap extends HashMap<String, String> {}
          """
        ),
        source(
          "demo.MDst",
          """
          package demo;
          public record MDst(demo.DstList meta) {}
          """
        ),
        source(
          "demo.DstList",
          """
          package demo;
          import java.util.ArrayList;
          public class DstList extends ArrayList<String> {}
          """
        )
      );

      assertFalse(compilation.success(), "a kind-mismatched Map-subtype pair should fail");
      assertFalse(
        compilation.hasError("no setter for 'empty'"),
        () -> "must not leak the bean-introspection error; saw " + compilation.errorMessages()
      );
      assertTrue(
        compilation.hasError("no auto-bridge could be derived"),
        () -> "expected the clean no-auto-bridge diagnostic; saw " + compilation.errorMessages()
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

    @Test
    @DisplayName("@Bridge with an unresolvable target class emits a precise diagnostic instead of a ClassCastException")
    void unresolvableTargetEmitsPreciseDiagnostic() {
      // When the @Bridge target lives in a module the annotated compilation unit can't see,
      // javac's AnnotationValue.getValue() returns the target FQN as a String rather than a
      // TypeMirror. A naive `(TypeMirror) cast` would blow up the build with an unhelpful
      // ClassCastException; the processor recognises the String fallback shape and surfaces a
      // guidance message that names the missing target instead.
      final var compilation = compile(
        source(
          "demo.Src",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(value = demo.does.not.Exist.class)
          public record Src(String id) {}
          """
        )
      );

      assertFalse(compilation.success(), "an unresolvable @Bridge target should fail");
      assertTrue(
        compilation.hasError("not resolvable from this compilation unit"),
        () -> "expected unresolvable-target diagnostic; saw " + compilation.errorMessages()
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
          "public static final Telescope<demo.payment.Payment, demo.bean.PaymentEntity> BRIDGE = Telescope.bridge(BRIDGE_FN);"
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
          "public static final Telescope<demo.Product, demo.ProductEntity> BRIDGE = Telescope.bridge(BRIDGE_FN);"
        ),
        entityBridge
      );
      assertTrue(
        dtoBridge.contains(
          "public static final Telescope<demo.Product, demo.ProductDto> BRIDGE = Telescope.bridge(BRIDGE_FN);"
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
      assertFalse(
        bridge.contains("__tx_createdAt.backward"),
        () -> "backward must NOT invoke BridgeFn.backward for forwardOnly transform, saw: " + bridge
      );
    }

    @Test
    @DisplayName(
      "@Transform(method = \"...\") — qualifier dispatch emits direct static-method call, no BridgeFn instance"
    )
    void transformQualifierDispatchEmitsDirectStaticCall() {
      final var compilation = compile(
        source(
          "demo.DateHelpers",
          """
          package demo;
          import java.time.Instant;
          import java.time.ZoneOffset;
          import java.time.format.DateTimeFormatter;
          public final class DateHelpers {
            private DateHelpers() {}
            public static String expiry(Instant i)    { return DateTimeFormatter.ISO_INSTANT.format(i); }
            public static String createdAt(Instant i) { return i.atZone(ZoneOffset.UTC).toString(); }
          }
          """
        ),
        source(
          "demo.UserEntity",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Transform;
          import java.time.Instant;
          @Bridge(value = demo.UserDto.class, transforms = {
            @Transform(field = "expiresAt", using = demo.DateHelpers.class, method = "expiry"),
            @Transform(field = "registeredAt", using = demo.DateHelpers.class, method = "createdAt")
          })
          public record UserEntity(String id, Instant expiresAt, Instant registeredAt) {}
          """
        ),
        source(
          "demo.UserDto",
          """
          package demo;
          public record UserDto(String id, String expiresAt, String registeredAt) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.UserEntityBridge");
      assertNotNull(bridge, () -> "UserEntityBridge missing; saw " + compilation.generated().keySet());

      // NO __tx_ singleton — qualifier dispatch calls the static method directly.
      assertFalse(
        bridge.contains("__tx_expiresAt"),
        () -> "qualifier dispatch must NOT emit __tx_ field; saw: " + bridge
      );
      assertFalse(
        bridge.contains("new demo.DateHelpers()"),
        () -> "qualifier dispatch must NOT instantiate the helper class; saw: " + bridge
      );

      // Forward emits direct UsingClass.methodName(value) calls per qualified field.
      assertTrue(
        bridge.contains("demo.DateHelpers.expiry(s.expiresAt())"),
        () -> "expected demo.DateHelpers.expiry(...) call; saw: " + bridge
      );
      assertTrue(
        bridge.contains("demo.DateHelpers.createdAt(s.registeredAt())"),
        () -> "expected demo.DateHelpers.createdAt(...) call; saw: " + bridge
      );

      // Qualifier dispatch is implicitly forward-only — backward zero-fills.
      assertTrue(
        bridge.contains("new demo.UserEntity(t.id(), null, null)"),
        () -> "expected backward zero-fill on qualifier-dispatch slots; saw: " + bridge
      );
    }

    @Test
    @DisplayName("@Transform(using=BridgeFn) and @Transform(method=...) coexist on the same bridge")
    void transformBridgeFnAndQualifierCoexist() {
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
          "demo.Helpers",
          """
          package demo;
          import java.time.Instant;
          public final class Helpers {
            private Helpers() {}
            public static String asIso(Instant i) { return i.toString(); }
          }
          """
        ),
        source(
          "demo.Order",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Transform;
          import java.math.BigDecimal;
          import java.time.Instant;
          @Bridge(value = demo.OrderEntity.class, transforms = {
            @Transform(field = "price", using = demo.CentsConverter.class),
            @Transform(field = "createdAt", using = demo.Helpers.class, method = "asIso")
          })
          public record Order(String id, BigDecimal price, Instant createdAt) {}
          """
        ),
        source(
          "demo.OrderEntity",
          """
          package demo;
          public record OrderEntity(String id, Long price, String createdAt) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.OrderBridge");
      assertNotNull(bridge, () -> "OrderBridge missing; saw " + compilation.generated().keySet());

      // BridgeFn-shape: __tx_price singleton + .forward/.backward calls
      assertTrue(
        bridge.contains("private static final demo.CentsConverter __tx_price = new demo.CentsConverter();"),
        bridge
      );
      assertTrue(bridge.contains("__tx_price.forward(s.price())"), bridge);
      assertTrue(bridge.contains("__tx_price.backward(t.price())"), bridge);

      // Qualifier dispatch: no __tx_createdAt, direct method call
      assertFalse(
        bridge.contains("__tx_createdAt"),
        () -> "qualifier field MUST NOT have a __tx_ singleton; saw: " + bridge
      );
      assertTrue(bridge.contains("demo.Helpers.asIso(s.createdAt())"), bridge);
      // Forward-only on qualifier slot — backward zero-fills the createdAt slot
      assertFalse(
        bridge.contains("Helpers.asIso(t.createdAt())"),
        () -> "backward must NOT call qualifier method; saw: " + bridge
      );
    }

    @Test
    @DisplayName("@Transform(method = \"  \") — whitespace method name rejected at processor time")
    void transformBlankMethodRejected() {
      final var compilation = compile(
        source(
          "demo.Helpers",
          """
          package demo;
          public final class Helpers {
            public static String identity(String s) { return s; }
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
            @Transform(field = "name", using = demo.Helpers.class, method = "  ")
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
      assertTrue(
        !compilation.success() && compilation.hasError("must not be blank"),
        () -> "expected 'must not be blank' diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("@Transform(method = \"missing\") — non-existent method rejected at processor time")
    void transformMissingMethodRejected() {
      final var compilation = compile(
        source(
          "demo.Helpers",
          """
          package demo;
          public final class Helpers {
            public static String identity(String s) { return s; }
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
            @Transform(field = "name", using = demo.Helpers.class, method = "nonexistent")
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
      assertTrue(
        !compilation.success() && compilation.hasError("method` not found"),
        () -> "expected 'method not found' diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("@Transform(method = \"instanceMethod\") — non-static method rejected at processor time")
    void transformNonStaticMethodRejected() {
      final var compilation = compile(
        source(
          "demo.Helpers",
          """
          package demo;
          public final class Helpers {
            public Helpers() {}
            // instance method — qualifier dispatch needs static
            public String mutate(String s) { return s; }
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
            @Transform(field = "name", using = demo.Helpers.class, method = "mutate")
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
      assertTrue(
        !compilation.success() && compilation.hasError("is not static"),
        () -> "expected 'is not static' diagnostic; saw " + compilation.errorMessages()
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
    @DisplayName(
      "GAP-1: @Default + @Rename on the same source field compose — null-coalesce feeds the renamed target slot"
    )
    void defaultAndRenameOnSameFieldCompose() {
      // The two modifiers operate on different axes: @Default null-coalesces the SOURCE read,
      // @Rename relocates the TARGET slot. They are not mutually exclusive (unlike @Default +
      // @Drop or @Default + @Transform), so the processor accepts the combination and the
      // generated forward expression is the source-side null-coalesce written into the renamed
      // target slot. This test pins that contract — if a future change starts rejecting the
      // composition, the failure here surfaces the regression immediately.
      final var compilation = compile(
        source(
          "demo.User",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Default;
          import io.github.eschizoid.telescope.annotations.Rename;
          @Bridge(
            value = demo.UserDto.class,
            renames = { @Rename(source = "region", target = "area") },
            defaults = { @Default(field = "region", value = "EMEA") }
          )
          public record User(String id, String region) {}
          """
        ),
        source("demo.UserDto", "package demo; public record UserDto(String id, String area) {}")
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.UserBridge");
      assertNotNull(bridge, () -> "UserBridge missing; saw " + compilation.generated().keySet());

      // Forward: source.region null-coalesces to "EMEA", then writes into target.area (renamed).
      assertTrue(
        bridge.contains("new demo.UserDto(s.id(), (s.region() == null ? \"EMEA\" : s.region()))"),
        () -> "expected null-coalesce on region feeding renamed `area` slot, saw: " + bridge
      );
      // Backward: target.area reads back into source.region (no default on backward).
      assertTrue(
        bridge.contains("new demo.User(t.id(), t.area())"),
        () -> "expected backward writes target.area into source.region, saw: " + bridge
      );
    }

    @Test
    @DisplayName("@ViaMapper(field, using) — codegen delegates the field to the named bridge class")
    void viaMapperRoutesToNamedBridge() {
      final var compilation = compile(
        source(
          "demo.AddressBridge",
          """
          package demo;
          public final class AddressBridge {
            public static demo.AddressDto forward(demo.Address a) {
              return new demo.AddressDto(a.line() + " (forwarded)");
            }
            public static demo.Address backward(demo.AddressDto a) {
              return new demo.Address(a.line() + " (backwarded)");
            }
          }
          """
        ),
        source(
          "demo.Address",
          """
          package demo;
          public record Address(String line) {}
          """
        ),
        source(
          "demo.AddressDto",
          """
          package demo;
          public record AddressDto(String line) {}
          """
        ),
        source(
          "demo.Order",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.ViaMapper;
          @Bridge(value = demo.OrderDto.class, viaMappers = {
            @ViaMapper(field = "address", using = demo.AddressBridge.class)
          })
          public record Order(String id, demo.Address address) {}
          """
        ),
        source(
          "demo.OrderDto",
          """
          package demo;
          public record OrderDto(String id, demo.AddressDto address) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.OrderBridge");
      assertNotNull(bridge, () -> "OrderBridge missing; saw " + compilation.generated().keySet());

      // Forward routes through the user-named bridge.
      assertTrue(
        bridge.contains("new demo.OrderDto(s.id(), demo.AddressBridge.forward(s.address()))"),
        () -> "expected forward via AddressBridge, saw: " + bridge
      );
      // Backward routes through the user-named bridge.
      assertTrue(
        bridge.contains("new demo.Order(t.id(), demo.AddressBridge.backward(t.address()))"),
        () -> "expected backward via AddressBridge, saw: " + bridge
      );
      // No auto-sub-bridge AddressBridge2 / AddressToAddressDtoBridge was generated for this pair.
      assertNull(compilation.generated().get("demo.AddressToAddressDtoBridge"));
    }

    @Test
    @DisplayName("GAP-3: @ViaMapper patch() delegates to user bridge's patch(base, partial) — not backward(partial)")
    void viaMapperPatchDelegatesToUserBridgePatch() {
      final var compilation = compile(
        source(
          "demo.AddressBridge",
          """
          package demo;
          public final class AddressBridge {
            public static demo.AddressDto forward(demo.Address a) { return new demo.AddressDto(a.line()); }
            public static demo.Address backward(demo.AddressDto a) { return new demo.Address(a.line()); }
            public static demo.Address patch(demo.Address base, demo.AddressDto partial) {
              if (base == null || partial == null) return base;
              return new demo.Address(partial.line() != null ? partial.line() : base.line());
            }
          }
          """
        ),
        source("demo.Address", "package demo; public record Address(String line) {}"),
        source("demo.AddressDto", "package demo; public record AddressDto(String line) {}"),
        source(
          "demo.Order",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.ViaMapper;
          @Bridge(value = demo.OrderDto.class, viaMappers = {
            @ViaMapper(field = "address", using = demo.AddressBridge.class)
          })
          public record Order(String id, demo.Address address) {}
          """
        ),
        source("demo.OrderDto", "package demo; public record OrderDto(String id, demo.AddressDto address) {}")
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.OrderBridge");
      assertNotNull(bridge, () -> "OrderBridge missing; saw " + compilation.generated().keySet());

      // The patch body must recursively patch through the user-named AddressBridge — not
      // call .backward(__pp_address) which would discard `base.address()` state. Locks in
      // the P5 sparse-overlay semantics for @ViaMapper-governed fields, parallel to the
      // auto-sub-bridge RECURSE path covered by patchRecursesIntoNestedSubBridges.
      assertTrue(
        bridge.contains("demo.AddressBridge.patch(base.address(), __pp_address)"),
        () -> "expected AddressBridge.patch(base.address(), __pp_address), saw: " + bridge
      );
      // Negative: backward(__pp_address) would mean the base sub-component state is lost.
      assertFalse(
        bridge.contains("demo.AddressBridge.backward(__pp_address)"),
        () -> "patch must not fall through to backward(...) on @ViaMapper fields; saw: " + bridge
      );
    }

    @Test
    @DisplayName("VM1: @ViaMapper + @Rename on the same field rejected at build")
    void viaMapperAndRenameOnSameFieldRejected() {
      final var compilation = compile(
        source(
          "demo.AddressBridge",
          """
          package demo;
          public final class AddressBridge {
            public static demo.AddressDto forward(demo.Address a) { return new demo.AddressDto(a.line()); }
            public static demo.Address backward(demo.AddressDto a) { return new demo.Address(a.line()); }
          }
          """
        ),
        source("demo.Address", "package demo; public record Address(String line) {}"),
        source("demo.AddressDto", "package demo; public record AddressDto(String line) {}"),
        source(
          "demo.Order",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Rename;
          import io.github.eschizoid.telescope.annotations.ViaMapper;
          @Bridge(
            value = demo.OrderDto.class,
            renames = { @Rename(source = "address", target = "shipping") },
            viaMappers = { @ViaMapper(field = "address", using = demo.AddressBridge.class) }
          )
          public record Order(String id, demo.Address address) {}
          """
        ),
        source("demo.OrderDto", "package demo; public record OrderDto(String id, demo.AddressDto shipping) {}")
      );

      assertFalse(compilation.success(), "viaMappers + renames overlap should fail");
      assertTrue(
        compilation.hasError("appears in both viaMappers and renames"),
        () -> "expected overlap diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("VM2: @ViaMapper + @Default on the same field rejected at build")
    void viaMapperAndDefaultsOnSameFieldRejected() {
      final var compilation = compile(
        source(
          "demo.AddressBridge",
          """
          package demo;
          public final class AddressBridge {
            public static demo.AddressDto forward(demo.Address a) { return new demo.AddressDto(a.line()); }
            public static demo.Address backward(demo.AddressDto a) { return new demo.Address(a.line()); }
          }
          """
        ),
        source("demo.Address", "package demo; public record Address(String line) {}"),
        source("demo.AddressDto", "package demo; public record AddressDto(String line) {}"),
        source(
          "demo.Order",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Default;
          import io.github.eschizoid.telescope.annotations.ViaMapper;
          @Bridge(
            value = demo.OrderDto.class,
            defaults = { @Default(field = "address", value = "null") },
            viaMappers = { @ViaMapper(field = "address", using = demo.AddressBridge.class) }
          )
          public record Order(String id, demo.Address address) {}
          """
        ),
        source("demo.OrderDto", "package demo; public record OrderDto(String id, demo.AddressDto address) {}")
      );

      assertFalse(compilation.success(), "viaMappers + defaults overlap should fail");
      assertTrue(
        compilation.hasError("appears in both viaMappers and defaults"),
        () -> "expected overlap diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("Generated *Bridge class includes a patch(base, partial) static for sparse overlay")
    void patchEmitsSparseOverlay() {
      final var compilation = compile(
        source(
          "demo.User",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.UserDto.class)
          public record User(String id, String email, int age) {}
          """
        ),
        source(
          "demo.UserDto",
          """
          package demo;
          public record UserDto(String id, String email, int age) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.UserBridge");
      assertNotNull(bridge, () -> "UserBridge missing; saw " + compilation.generated().keySet());

      // Patch method exists with the right signature.
      assertTrue(
        bridge.contains("public static demo.User patch(final demo.User base, final demo.UserDto partial)"),
        () -> "expected patch(base, partial) signature, saw: " + bridge
      );
      // P5-1: null-guard at top of patch body — matches runtime Mapper#patch semantics.
      assertTrue(
        bridge.contains("if (base == null || partial == null) return base"),
        () -> "expected null-guard at top of patch body, saw: " + bridge
      );
      // P5-DBL: reference-type slots are read into __pp_<field> locals so partial.<getter>() is
      // only evaluated once per source slot. String components null-gate via the local to base.
      assertTrue(bridge.contains("__pp_id = partial.id()"), () -> "expected __pp_id local declaration, saw: " + bridge);
      assertTrue(
        bridge.contains("(__pp_id != null ? __pp_id : base.id())"),
        () -> "expected null-gate on __pp_id, saw: " + bridge
      );
      assertTrue(
        bridge.contains("__pp_email = partial.email()"),
        () -> "expected __pp_email local declaration, saw: " + bridge
      );
      assertTrue(
        bridge.contains("(__pp_email != null ? __pp_email : base.email())"),
        () -> "expected null-gate on __pp_email, saw: " + bridge
      );
      // Primitive component (int age): always overlaid from partial (no null gate, no local).
      assertTrue(
        bridge.contains("new demo.User(") && bridge.contains("partial.age()"),
        () -> "expected primitive int age always patched from partial, saw: " + bridge
      );
      assertFalse(
        bridge.contains("partial.age() != null"),
        () -> "primitive int age must NOT be null-gated, saw: " + bridge
      );
      assertFalse(
        bridge.contains("__pp_age"),
        () -> "primitive int age must NOT have a __pp_ local (no double-eval concern), saw: " + bridge
      );
    }

    @Test
    @DisplayName("P5-3/P5-4: nested RECURSE fields recursively patch, not full-backward")
    void patchRecursesIntoNestedSubBridges() {
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
        source("demo.Customer", "package demo; public record Customer(String name, String email) {}"),
        source(
          "demo.OrderDto",
          """
          package demo;
          public record OrderDto(String id, demo.CustomerDto customer) {}
          """
        ),
        source("demo.CustomerDto", "package demo; public record CustomerDto(String name, String email) {}")
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.OrderBridge");
      assertNotNull(bridge, () -> "OrderBridge missing; saw " + compilation.generated().keySet());

      // The nested customer slot should call SubBridge.patch(base.customer(), __pp_customer) —
      // recursive patch — not SubBridge.backward(...) which would full-rebuild the customer and
      // discard any sub-fields the partial doesn't carry. __pp_customer is the P5-DBL local that
      // ensures partial.customer() is only evaluated once per patch call (matters for bean getters
      // with side effects).
      final var subBridge = "CustomerToCustomerDtoBridge";
      assertTrue(
        bridge.contains("__pp_customer = partial.customer()"),
        () -> "expected __pp_customer local declaration, saw: " + bridge
      );
      assertTrue(
        bridge.contains(subBridge + ".patch(base.customer(), __pp_customer)"),
        () -> "expected nested patch delegation via __pp_customer, saw: " + bridge
      );
      // Null-gate references the local on both sides — no re-evaluation of partial.customer().
      assertTrue(
        bridge.contains(
          "(__pp_customer != null ? " + subBridge + ".patch(base.customer(), __pp_customer) : base.customer())"
        ),
        () -> "expected null-gate referencing __pp_customer, saw: " + bridge
      );
    }

    @Test
    @DisplayName("P5-T1: sealed @Bridge umbrella emits patch with case-matching dispatch + no-op mismatch fallback")
    void sealedPatchDispatchesByCase() {
      final var compilation = compile(
        source(
          "demo.Payment",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.PaymentEntity.class)
          public sealed interface Payment permits demo.CreditCard, demo.BankTransfer {}
          """
        ),
        source(
          "demo.CreditCard",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.CreditCardEntity.class)
          public record CreditCard(String pan) implements demo.Payment {}
          """
        ),
        source(
          "demo.BankTransfer",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.BankTransferEntity.class)
          public record BankTransfer(String iban) implements demo.Payment {}
          """
        ),
        source(
          "demo.PaymentEntity",
          """
          package demo;
          public sealed interface PaymentEntity permits demo.CreditCardEntity, demo.BankTransferEntity {}
          """
        ),
        source(
          "demo.CreditCardEntity",
          "package demo; public record CreditCardEntity(String pan) implements demo.PaymentEntity {}"
        ),
        source(
          "demo.BankTransferEntity",
          "package demo; public record BankTransferEntity(String iban) implements demo.PaymentEntity {}"
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.PaymentBridge");
      assertNotNull(bridge, () -> "PaymentBridge missing; saw " + compilation.generated().keySet());

      // Sealed umbrella patch signature.
      assertTrue(
        bridge.contains("public static demo.Payment patch(final demo.Payment base, final demo.PaymentEntity partial)"),
        () -> "expected sealed patch signature, saw: " + bridge
      );
      // Null guard.
      assertTrue(
        bridge.contains("if (base == null || partial == null) return base"),
        () -> "expected null-guard, saw: " + bridge
      );
      // Case-matching dispatch for both permits.
      assertTrue(
        bridge.contains("if (base instanceof demo.CreditCard sb && partial instanceof demo.CreditCardEntity tp)"),
        () -> "expected CreditCard case dispatch, saw: " + bridge
      );
      assertTrue(
        bridge.contains("if (base instanceof demo.BankTransfer sb && partial instanceof demo.BankTransferEntity tp)"),
        () -> "expected BankTransfer case dispatch, saw: " + bridge
      );
      // P5-SLD: case mismatch is a no-op (return base), NOT a backward type-switch.
      assertTrue(bridge.contains("return base; // P5-SLD"), () -> "expected no-op mismatch fallback, saw: " + bridge);
      assertFalse(
        bridge.contains("return BACKWARD.apply(partial)"),
        () -> "case mismatch must NOT silently type-switch via BACKWARD, saw: " + bridge
      );
    }

    @Test
    @DisplayName("P5-T2: @Default + RECURSE patch emits __cond_ local to avoid quadruple-evaluation")
    void patchDefaultAndRecurseUsesCondLocal() {
      final var compilation = compile(
        source(
          "demo.AddressBridge",
          """
          package demo;
          public final class AddressBridge {
            public static demo.AddressDto forward(demo.Address a) { return new demo.AddressDto(a.line()); }
            public static demo.Address backward(demo.AddressDto a) { return new demo.Address(a.line()); }
            public static demo.Address patch(demo.Address base, demo.AddressDto partial) { return base; }
          }
          """
        ),
        source("demo.Address", "package demo; public record Address(String line) {}"),
        source("demo.AddressDto", "package demo; public record AddressDto(String line) {}"),
        source(
          "demo.Order",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Default;
          import io.github.eschizoid.telescope.annotations.ViaMapper;
          @Bridge(
            value = demo.OrderDto.class,
            defaults   = { @Default(field    = "address", value = "null") },
            viaMappers = { @ViaMapper(field  = "address", using = demo.AddressBridge.class) }
          )
          public record Order(String id, demo.Address address) {}
          """
        ),
        source("demo.OrderDto", "package demo; public record OrderDto(String id, demo.AddressDto address) {}")
      );

      // The @Default + @ViaMapper combination is rejected by VM2 — confirms the validation we
      // just added catches this case. The fact that we can't even compile this combination
      // means the quadruple-evaluation bug from the round-3 review is unreachable through
      // valid annotations. The validation IS the fix for P5-T2.
      assertFalse(compilation.success(), "@Default + @ViaMapper should be rejected (VM2)");
      assertTrue(
        compilation.hasError("appears in both viaMappers and defaults"),
        () -> "expected VM2 rejection blocks the @Default + RECURSE patch scenario; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("P5-T4: @Default on a List<X> source field in patch declares __cond_ with the container type")
    void patchDefaultOnContainerField() {
      // @Default on a container source field exercises a different applyBackward arm than the
      // scalar RECURSE path covered by patchDefaultAndRecurseUsesCondLocal. The __cond_ local
      // declaration must use the source field's container type (List<E>), and the conditional
      // must remain type-compatible — applyBackward for an identity-element LIST returns the
      // defensive-copy expression, which is also List<E>-typed. This pins the type-rendering
      // contract for container patches.
      final var compilation = compile(
        source(
          "demo.Cart",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Default;
          import java.util.List;
          @Bridge(
            value = demo.CartDto.class,
            defaults = { @Default(field = "items", value = "null") }
          )
          public record Cart(String id, List<String> items) {}
          """
        ),
        source(
          "demo.CartDto",
          """
          package demo;
          import java.util.List;
          public record CartDto(String id, List<String> items) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.CartBridge");
      assertNotNull(bridge, () -> "CartBridge missing; saw " + compilation.generated().keySet());

      // __pp_items must be typed to the target field's container type.
      assertTrue(
        bridge.contains("__pp_items = partial.items()"),
        () -> "expected __pp_items local from partial.items(), saw: " + bridge
      );
      // __cond_items must be typed to the source field's container type. The bare type token
      // varies by TypeMirror#toString implementation (List vs java.util.List) — accept either
      // shape, but require the variable name and assignment.
      assertTrue(
        bridge.contains("__cond_items =") && bridge.contains("(__pp_items != null"),
        () -> "expected __cond_items conditional bound to __pp_items ternary, saw: " + bridge
      );
      // The null-coalesce against the default must reference __cond_items, not re-evaluate.
      assertTrue(
        bridge.contains("(__cond_items == null ? null : __cond_items)"),
        () -> "expected default null-coalesce through __cond_items, saw: " + bridge
      );
    }

    @Test
    @DisplayName("P5-T3a: forward-only @Transform field reads from base in patch (no backward call)")
    void patchForwardOnlyTransformReadsBase() {
      final var compilation = compile(
        source(
          "demo.TimestampFn",
          """
          package demo;
          import io.github.eschizoid.telescope.conversion.BridgeFn;
          import java.time.Instant;
          public final class TimestampFn implements BridgeFn<Instant, String> {
            public TimestampFn() {}
            @Override public String forward(Instant x) { return x.toString(); }
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
            @Transform(field = "createdAt", using = demo.TimestampFn.class, forwardOnly = true)
          })
          public record Audit(String id, Instant createdAt) {}
          """
        ),
        source("demo.AuditEntity", "package demo; public record AuditEntity(String id, String createdAt) {}")
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.AuditBridge");
      assertNotNull(bridge, () -> "AuditBridge missing; saw " + compilation.generated().keySet());
      // Patch for createdAt must read from base.createdAt() — partial has no meaningful inverse.
      assertTrue(
        bridge.contains("new demo.Audit(") && bridge.contains("base.createdAt()"),
        () -> "expected patch to read createdAt from base (forward-only), saw: " + bridge
      );
      // Must NOT invoke __tx_createdAt.backward(...) anywhere in patch body.
      assertFalse(
        bridge.contains("__tx_createdAt.backward"),
        () -> "patch must NOT call BridgeFn.backward for forward-only transform, saw: " + bridge
      );
    }

    @Test
    @DisplayName("P5-T3b: dropped source field reads from base in patch")
    void patchDroppedFieldReadsBase() {
      final var compilation = compile(
        source(
          "demo.User",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(value = demo.UserDto.class, drops = { "secret" })
          public record User(String id, String secret) {}
          """
        ),
        source("demo.UserDto", "package demo; public record UserDto(String id) {}")
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.UserBridge");
      assertNotNull(bridge, () -> "UserBridge missing; saw " + compilation.generated().keySet());
      // Patch for secret (dropped from target) must read from base.secret() — the partial doesn't
      // carry the dropped slot, so there's no value to overlay.
      assertTrue(
        bridge.contains("public static demo.User patch") && bridge.contains("base.secret()"),
        () -> "expected patch to read dropped 'secret' from base, saw: " + bridge
      );
    }

    @Test
    @DisplayName(
      "@Bridge(writeStrategy = SETTERS) forces the no-arg+setters strategy on a POJO that also has a builder"
    )
    void writeStrategyForcesSetters() {
      final var compilation = compile(
        source(
          "demo.PA",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.WriteStrategy;
          @Bridge(value = demo.PB.class, writeStrategy = WriteStrategy.SETTERS)
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
            // PB also exposes a builder — under AUTO the processor would prefer the name-matched
            // ctor path first; SETTERS should force the no-arg + setters shape regardless.
            public static Builder builder() { return new Builder(); }
            public static class Builder {
              private String id;
              public Builder id(String id) { this.id = id; return this; }
              public PB build() { final var p = new PB(); p.setId(id); return p; }
            }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.PABridge");
      assertNotNull(bridge, () -> "PABridge missing; saw " + compilation.generated().keySet());

      // Forward uses no-arg + setter path, NOT the builder.
      assertTrue(
        bridge.contains("final var out = new demo.PB()") && bridge.contains("out.setId(s.getId())"),
        () -> "expected SETTERS shape on forward, saw: " + bridge
      );
      assertFalse(bridge.contains("demo.PB.builder()"), () -> "should not call builder() when SETTERS forced");
    }

    @Test
    @DisplayName("P5-T5: patch() for SETTERS-strategy POJO source emits { __pp_ locals; new + setters; return }")
    void patchEmitsSparseOverlayForSettersPojo() {
      // Source is a POJO with ONLY a public no-arg ctor + setters — no name-matched public ctor,
      // no builder() — so backward/patch is forced down the SETTERS branch of buildExpr. This
      // exercises the block-wrap branch (line ~1211): the SETTERS body is itself a block, so
      // the patchLocals prelude must inline INSIDE the block, not wrap it twice. P5-BR's trim()
      // brace-detection guard is the safety net here; the assertion below pins the inline shape.
      final var compilation = compile(
        source(
          "demo.PA",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.PB.class)
          public class PA {
            private String id;
            private String email;
            public PA() {}
            public String getId() { return id; }
            public String getEmail() { return email; }
            public void setId(String id) { this.id = id; }
            public void setEmail(String email) { this.email = email; }
          }
          """
        ),
        source(
          "demo.PB",
          """
          package demo;
          public class PB {
            private String id;
            private String email;
            public PB() {}
            public String getId() { return id; }
            public String getEmail() { return email; }
            public void setId(String id) { this.id = id; }
            public void setEmail(String email) { this.email = email; }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.PABridge");
      assertNotNull(bridge, () -> "PABridge missing; saw " + compilation.generated().keySet());

      // Scope the index check to the patch method body, otherwise `new demo.PA()` resolves to
      // backward()'s allocation which sits earlier in the file.
      final var patchStart = bridge.indexOf("public static demo.PA patch(");
      assertTrue(patchStart > 0, () -> "patch method missing from bridge; saw: " + bridge);
      final var patchBody = bridge.substring(patchStart);
      // __pp_ locals MUST appear before `new demo.PA()` — the block-wrap branch (P5-BR) inlines
      // the locals into the existing SETTERS block rather than wrapping it twice.
      final var ppIdIdx = patchBody.indexOf("__pp_id = partial.getId()");
      final var ppEmailIdx = patchBody.indexOf("__pp_email = partial.getEmail()");
      final var newPaIdx = patchBody.indexOf("new demo.PA()");
      assertTrue(
        ppIdIdx > 0 && ppEmailIdx > 0 && newPaIdx > 0,
        () -> "missing locals or ctor in patch body: " + patchBody
      );
      assertTrue(ppIdIdx < newPaIdx, () -> "__pp_id must precede the constructor allocation; saw: " + patchBody);
      assertTrue(ppEmailIdx < newPaIdx, () -> "__pp_email must precede the constructor allocation; saw: " + patchBody);
      // Setter calls thread the null-gate ternary against base getters.
      assertTrue(
        bridge.contains("out.setId((__pp_id != null ? __pp_id : base.getId()))"),
        () -> "expected setId with null-gate against base.getId(), saw: " + bridge
      );
      assertTrue(
        bridge.contains("out.setEmail((__pp_email != null ? __pp_email : base.getEmail()))"),
        () -> "expected setEmail with null-gate against base.getEmail(), saw: " + bridge
      );
      // The body must NOT contain `}}` — that's the double-close hazard P5-DBL guarded against.
      assertFalse(
        bridge.contains("return out; } }") || bridge.contains("}}"),
        () -> "patch body must not double-close the SETTERS block; saw: " + bridge
      );
    }

    @Test
    @DisplayName("GAP-2: patch() for BUILDER-strategy POJO source chains builder().field(__pp_? : base).build()")
    void patchEmitsSparseOverlayForBuilderPojo() {
      // Source is a POJO with ONLY a static builder() — no name-matched public ctor — so the
      // backward/patch direction is forced down the BUILDER branch of buildExpr. This pins the
      // null-gated builder-chain shape that the patch path must emit for builder-shaped sources
      // (parallel to patchEmitsSparseOverlay which covers the record canonical-ctor shape).
      final var compilation = compile(
        source(
          "demo.PA",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.PB.class)
          public class PA {
            private String id;
            private String email;
            // Intentionally private — no name-matched public ctor for CONSTRUCTOR path.
            private PA() {}
            public String getId() { return id; }
            public String getEmail() { return email; }
            public static Builder builder() { return new Builder(); }
            public static class Builder {
              private final PA p = new PA();
              public Builder id(String v) { p.id = v; return this; }
              public Builder email(String v) { p.email = v; return this; }
              public PA build() { return p; }
            }
          }
          """
        ),
        source(
          "demo.PB",
          """
          package demo;
          public class PB {
            private String id;
            private String email;
            private PB() {}
            public String getId() { return id; }
            public String getEmail() { return email; }
            public static Builder builder() { return new Builder(); }
            public static class Builder {
              private final PB p = new PB();
              public Builder id(String v) { p.id = v; return this; }
              public Builder email(String v) { p.email = v; return this; }
              public PB build() { return p; }
            }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.PABridge");
      assertNotNull(bridge, () -> "PABridge missing; saw " + compilation.generated().keySet());

      // Patch body must precompute __pp_ locals (P5-DBL) and then thread them through the
      // builder chain with null-gates against base.getX().
      assertTrue(
        bridge.contains("final java.lang.String __pp_id = partial.getId()"),
        () -> "expected __pp_id local from partial.getId(), saw: " + bridge
      );
      assertTrue(
        bridge.contains("final java.lang.String __pp_email = partial.getEmail()"),
        () -> "expected __pp_email local from partial.getEmail(), saw: " + bridge
      );
      // The builder chain must use the conditional ternary for each setter.
      assertTrue(
        bridge.contains("demo.PA.builder().id((__pp_id != null ? __pp_id : base.getId()))"),
        () -> "expected builder().id(__pp_id != null ? __pp_id : base.getId()), saw: " + bridge
      );
      assertTrue(
        bridge.contains(".email((__pp_email != null ? __pp_email : base.getEmail())).build()"),
        () -> "expected .email(...).build() chain, saw: " + bridge
      );
      // Negative: SETTERS-style { out.setX(...) } shape must not appear for the builder path.
      assertFalse(
        bridge.contains("final var out = new demo.PA()"),
        () -> "BUILDER strategy must not fall through to SETTERS shape; saw: " + bridge
      );
    }

    @Test
    @DisplayName("@Bridge(writeStrategy = CONSTRUCTOR) on a POJO without name-matched ctor is a precise error")
    void writeStrategyConstructorMismatchRejected() {
      final var compilation = compile(
        source(
          "demo.PA",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.WriteStrategy;
          @Bridge(value = demo.PB.class, writeStrategy = WriteStrategy.CONSTRUCTOR)
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
          // No public name-matched constructor — no-arg only.
          public class PB {
            private String id;
            public PB() {}
            public String getId() { return id; }
            public void setId(String id) { this.id = id; }
          }
          """
        )
      );

      assertFalse(compilation.success(), "@Bridge(writeStrategy=CONSTRUCTOR) without a matching ctor should fail");
      assertTrue(
        compilation.hasError("writeStrategy = CONSTRUCTOR"),
        () -> "expected CONSTRUCTOR-strategy diagnostic; saw " + compilation.errorMessages()
      );
      // WS1-TEST: tighten — the error message must name BOTH the source class (annotation site)
      // AND the target class. Without this, a regression reverting error(annotationSite, …) back
      // to error(to, …) would still pass the substring check above. This pin catches it.
      assertTrue(
        compilation.errorMessages().contains("demo.PA") && compilation.errorMessages().contains("demo.PB"),
        () ->
          "WS1: error must name both source (PA — annotation site) and target (PB) classes; saw " +
          compilation.errorMessages()
      );
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
    @DisplayName("OL-1: @Rename + @Transform on the same source field rejected at build")
    void renamesAndTransformsOnSameFieldRejected() {
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
          import io.github.eschizoid.telescope.annotations.Rename;
          import io.github.eschizoid.telescope.annotations.Transform;
          @Bridge(
            value = demo.B.class,
            renames    = { @Rename(source    = "name", target = "label") },
            transforms = { @Transform(field  = "name", using  = demo.NoopFn.class) }
          )
          public record A(String name) {}
          """
        ),
        source("demo.B", "package demo; public record B(String label) {}")
      );

      assertFalse(compilation.success(), "renames + transforms overlap should fail");
      assertTrue(
        compilation.hasError("appears in both renames and transforms"),
        () -> "expected overlap diagnostic; saw " + compilation.errorMessages()
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

    @Test
    @DisplayName(
      "KITCHEN: drops + @Rename + @Default + @Transform + @ViaMapper + @Compute compose end-to-end with patch"
    )
    void kitchenSinkAllModifiersCompose() {
      // Exercises every modifier on a single @Bridge declaration. The goal isn't coverage of
      // any one mechanism — each is pinned by its own dedicated test elsewhere — but rather to
      // catch silent interaction bugs that only show up when multiple modifiers are stacked on
      // the same generation pass. If any pair regresses in a way the unit tests miss, this is
      // the test that shouts.
      final var compilation = compile(
        source(
          "demo.QtyFn",
          """
          package demo;
          import io.github.eschizoid.telescope.conversion.BridgeFn;
          public final class QtyFn implements BridgeFn<String, String> {
            public QtyFn() {}
            @Override public String forward(String x) { return x == null ? "0" : x.trim(); }
            @Override public String backward(String x) { return x; }
          }
          """
        ),
        source(
          "demo.EnvSupplier",
          """
          package demo;
          import java.util.function.Supplier;
          public final class EnvSupplier implements Supplier<String> {
            public EnvSupplier() {}
            @Override public String get() { return "prod"; }
          }
          """
        ),
        source(
          "demo.AddressBridge",
          """
          package demo;
          public final class AddressBridge {
            public static demo.AddressDto forward(demo.Address a) { return new demo.AddressDto(a.line()); }
            public static demo.Address backward(demo.AddressDto a) { return new demo.Address(a.line()); }
            public static demo.Address patch(demo.Address base, demo.AddressDto partial) {
              if (base == null || partial == null) return base;
              return new demo.Address(partial.line() != null ? partial.line() : base.line());
            }
          }
          """
        ),
        source("demo.Address", "package demo; public record Address(String line) {}"),
        source("demo.AddressDto", "package demo; public record AddressDto(String line) {}"),
        source(
          "demo.Order",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Compute;
          import io.github.eschizoid.telescope.annotations.Default;
          import io.github.eschizoid.telescope.annotations.Rename;
          import io.github.eschizoid.telescope.annotations.Transform;
          import io.github.eschizoid.telescope.annotations.ViaMapper;
          @Bridge(
            value      = demo.OrderDto.class,
            drops      = { "id" },
            renames    = { @Rename(source    = "name", target = "renamed") },
            defaults   = { @Default(field    = "tag",  value  = "X") },
            transforms = { @Transform(field  = "qty",  using  = demo.QtyFn.class) },
            viaMappers = { @ViaMapper(field  = "addr", using  = demo.AddressBridge.class) },
            computes   = { @Compute(field    = "env",  using  = demo.EnvSupplier.class) }
          )
          public record Order(String id, String name, String tag, String qty, demo.Address addr) {}
          """
        ),
        source(
          "demo.OrderDto",
          """
          package demo;
          public record OrderDto(String renamed, String tag, String qty, demo.AddressDto addr, String env) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.OrderBridge");
      assertNotNull(bridge, () -> "OrderBridge missing; saw " + compilation.generated().keySet());

      // FORWARD ────────────────────────────────────────────────────────────────────────────
      // drops: source.id never read; @Rename: source.name → target.renamed; @Default:
      // null-coalesce on source.tag; @Transform: source.qty through __tx_qty.forward;
      // @ViaMapper: source.addr through AddressBridge.forward; @Compute: target.env from
      // EnvSupplier with no source involvement.
      assertTrue(
        bridge.contains("s.name()") &&
          bridge.contains("(s.tag() == null ? \"X\" : s.tag())") &&
          bridge.contains("__tx_qty.forward(s.qty())") &&
          bridge.contains("demo.AddressBridge.forward(s.addr())") &&
          bridge.contains("__cp_env.get()"),
        () -> "forward composition incomplete; saw: " + bridge
      );
      assertFalse(bridge.contains("s.id()"), () -> "dropped source.id must not appear in forward; saw: " + bridge);

      // BACKWARD ───────────────────────────────────────────────────────────────────────────
      // drops fill source.id with null; @Rename reads target.renamed into source.name;
      // @Transform routes target.qty through __tx_qty.backward; @ViaMapper routes
      // target.addr through AddressBridge.backward; @Default does NOT apply on backward.
      assertTrue(
        bridge.contains(
          "new demo.Order(null, t.renamed(), t.tag(), __tx_qty.backward(t.qty()), demo.AddressBridge.backward(t.addr()))"
        ),
        () -> "backward composition off; saw: " + bridge
      );

      // PATCH ──────────────────────────────────────────────────────────────────────────────
      // Dropped + computed slots read from base; renamed source uses __pp_name from
      // partial.renamed(); @Transform threads through __tx_qty.backward(__pp_qty); @ViaMapper
      // delegates to AddressBridge.patch(base.addr(), __pp_addr); @Default emits __cond_tag.
      assertTrue(
        bridge.contains("__pp_name = partial.renamed()"),
        () -> "expected __pp_name from partial.renamed(); saw: " + bridge
      );
      assertTrue(bridge.contains("__pp_qty = partial.qty()"), () -> "expected __pp_qty local; saw: " + bridge);
      assertTrue(bridge.contains("__pp_addr = partial.addr()"), () -> "expected __pp_addr local; saw: " + bridge);
      assertTrue(bridge.contains("__pp_tag = partial.tag()"), () -> "expected __pp_tag local; saw: " + bridge);
      assertTrue(
        bridge.contains("__cond_tag =") && bridge.contains("(__cond_tag == null ? \"X\" : __cond_tag)"),
        () -> "expected __cond_tag default coalesce; saw: " + bridge
      );
      assertTrue(
        bridge.contains("demo.AddressBridge.patch(base.addr(), __pp_addr)"),
        () -> "expected @ViaMapper patch routing; saw: " + bridge
      );
      assertTrue(
        bridge.contains("__tx_qty.backward(__pp_qty)"),
        () -> "expected transform via __tx_qty.backward in patch; saw: " + bridge
      );
      // Negative: dropped source.id must read from base in patch, never from partial.
      assertTrue(bridge.contains("base.id()"), () -> "dropped field must read from base in patch; saw: " + bridge);
    }

    @Test
    @DisplayName("GAP-5a: @Compute + @Default on the same field rejected at build")
    void computeAndDefaultOnSameFieldRejected() {
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
          import io.github.eschizoid.telescope.annotations.Default;
          @Bridge(
            value = demo.B.class,
            computes = { @Compute(field = "ts", using = demo.NowSupplier.class) },
            defaults = { @Default(field = "ts", value = "fallback") }
          )
          public record A(String id, String ts) {}
          """
        ),
        source("demo.B", "package demo; public record B(String id, String ts) {}")
      );

      assertFalse(compilation.success(), "computes + defaults overlap should fail");
      assertTrue(
        compilation.hasError("appears in both computes (target slot) and defaults"),
        () -> "expected overlap diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("GAP-5b: @Compute + @Transform on the same field rejected at build")
    void computeAndTransformOnSameFieldRejected() {
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
          import io.github.eschizoid.telescope.annotations.Compute;
          import io.github.eschizoid.telescope.annotations.Transform;
          @Bridge(
            value = demo.B.class,
            computes   = { @Compute(field   = "ts", using = demo.NowSupplier.class) },
            transforms = { @Transform(field = "ts", using = demo.NoopFn.class) }
          )
          public record A(String id, String ts) {}
          """
        ),
        source("demo.B", "package demo; public record B(String id, String ts) {}")
      );

      assertFalse(compilation.success(), "computes + transforms overlap should fail");
      assertTrue(
        compilation.hasError("appears in both computes (target slot) and transforms"),
        () -> "expected overlap diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("GAP-5c: @Compute + @ViaMapper on the same field rejected at build")
    void computeAndViaMapperOnSameFieldRejected() {
      final var compilation = compile(
        source(
          "demo.NowSupplier",
          """
          package demo;
          import java.util.function.Supplier;
          public final class NowSupplier implements Supplier<demo.Inner> {
            public NowSupplier() {}
            @Override public demo.Inner get() { return new demo.Inner("computed"); }
          }
          """
        ),
        source(
          "demo.InnerBridge",
          """
          package demo;
          public final class InnerBridge {
            public static demo.InnerDto forward(demo.Inner i) { return new demo.InnerDto(i.v()); }
            public static demo.Inner backward(demo.InnerDto i) { return new demo.Inner(i.v()); }
          }
          """
        ),
        source("demo.Inner", "package demo; public record Inner(String v) {}"),
        source("demo.InnerDto", "package demo; public record InnerDto(String v) {}"),
        source(
          "demo.A",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Compute;
          import io.github.eschizoid.telescope.annotations.ViaMapper;
          @Bridge(
            value = demo.B.class,
            computes   = { @Compute(field   = "inner", using = demo.NowSupplier.class) },
            viaMappers = { @ViaMapper(field = "inner", using = demo.InnerBridge.class) }
          )
          public record A(String id, demo.Inner inner) {}
          """
        ),
        source("demo.B", "package demo; public record B(String id, demo.Inner inner) {}")
      );

      assertFalse(compilation.success(), "computes + viaMappers overlap should fail");
      assertTrue(
        compilation.hasError("appears in both computes (target slot) and viaMappers"),
        () -> "expected overlap diagnostic; saw " + compilation.errorMessages()
      );
    }
  }

  @Nested
  @DisplayName("@Bridge(lenient = true) — opt-out of strict bijection")
  class LenientMode {

    @Test
    @DisplayName("small DTO → large entity: lenient skips bijection check, generated BRIDGE compiles")
    void smallSrcLargeTarget() {
      // Without lenient = true this would fail with "must expose the same field names (a
      // bijection)"
      // because Target has 5 fields that Source doesn't. The whole point of the lenient mode is to
      // let this exact shape compile. Same scenario MapStruct handles by default for every mapper.
      final var compilation = compile(
        source(
          "demo.SmallDto",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(value = demo.LargeEntity.class, lenient = true)
          public record SmallDto(String id, String email) {}
          """
        ),
        source(
          "demo.LargeEntity",
          """
          package demo;
          public record LargeEntity(
            String id, String email,
            String middleName, int loyaltyPoints, boolean optedIn,
            String region, java.util.List<String> tags
          ) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "lenient should compile; saw " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.SmallDtoBridge");
      assertNotNull(bridge, "expected SmallDtoBridge to be generated");
      // Forward direction: matched fields written through; unmatched fields take JLS defaults.
      // The generated rebuild line writes ALL target components; unmatched ones see the literal
      // default value from defaultLiteralFor (e.g. `null` for String/List, `0` for int, `false`
      // for boolean). Pin those literals so a future refactor that breaks the synthesis surfaces
      // here.
      assertTrue(
        bridge.contains("s.id()") && bridge.contains("s.email()"),
        () -> "forward should read matched source components; saw:\n" + bridge
      );
      assertTrue(
        bridge.contains("null") && bridge.contains("0") && bridge.contains("false"),
        () -> "forward should fill unmatched primitives + references with JLS defaults; saw:\n" + bridge
      );
    }

    @Test
    @DisplayName("large DTO → small entity: extra source fields silently dropped, no error")
    void largeSrcSmallTarget() {
      // Mirror image of the above: Source has fields with no Target counterpart. Without lenient,
      // bijection check fails. With lenient, the extras are auto-dropped (silently ignored on
      // forward, JLS-defaulted on backward — same as an explicit @Bridge(drops = {...})).
      final var compilation = compile(
        source(
          "demo.LargeDto",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(value = demo.SmallEntity.class, lenient = true)
          public record LargeDto(
            String id, String email,
            String tracingId, long createdAtEpoch, String tenantHint
          ) {}
          """
        ),
        source(
          "demo.SmallEntity",
          """
          package demo;
          public record SmallEntity(String id, String email) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "lenient should compile; saw " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.LargeDtoBridge");
      assertNotNull(bridge);
      // Forward emits only id + email reads; tracingId / createdAtEpoch / tenantHint don't appear
      // as source reads in the forward path (they're auto-drop'd).
      assertTrue(
        bridge.contains("s.id()") && bridge.contains("s.email()"),
        () -> "forward should read matched source components; saw:\n" + bridge
      );
    }

    @Test
    @DisplayName("lenient = false (default) still enforces strict bijection — regression guard")
    void defaultRemainsStrict() {
      // Pin that the opt-in semantics hold: omitting `lenient` (or setting it to false) keeps the
      // historical bijection error. A future refactor that silently flipped the default would
      // change adopter semantics — this test would catch it immediately.
      final var compilation = compile(
        source(
          "demo.Src",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.Dst.class)
          public record Src(String id, String email) {}
          """
        ),
        source("demo.Dst", "package demo; public record Dst(String id, String email, String extra) {}")
      );

      assertFalse(compilation.success(), "default lenient = false must still enforce bijection");
      assertTrue(
        compilation.hasError("must expose the same field names"),
        () -> "expected bijection diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("lenient + renames: renamed pair is honoured; remaining unmatched fields auto-filled")
    void lenientPlusRenames() {
      // Hybrid case: user declares one rename to bridge a deliberate name difference, but other
      // unmatched fields take the lenient auto-fill path. The rename + auto-fill must not
      // double-cover any field. If they did, downstream codegen would emit duplicate writes.
      final var compilation = compile(
        source(
          "demo.SrcWithRename",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Rename;
          @Bridge(value = demo.DstWithRename.class, lenient = true,
            renames = { @Rename(source = "orderNumber", target = "referenceCode") })
          public record SrcWithRename(String orderNumber, String memo) {}
          """
        ),
        source(
          "demo.DstWithRename",
          """
          package demo;
          public record DstWithRename(String referenceCode, String memo, int totalCents) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "lenient + rename should compile; saw " + compilation.errorMessages());
      final var bridge = compilation.generated().get("demo.SrcWithRenameBridge");
      assertNotNull(bridge);
      assertTrue(
        bridge.contains("s.orderNumber()") && bridge.contains("s.memo()"),
        () -> "forward should read both matched source components (renamed + auto-matched); saw:\n" + bridge
      );
    }
  }

  @Nested
  @DisplayName("@Bridge(source = X.class, target = Y.class) — carrier form for cross-module pairs")
  class CarrierForm {

    @Test
    @DisplayName("carrier form: emitted bridge sits in the CARRIER's package, named after the carrier")
    void carrierEmittedInCarrierPackage() {
      // The whole point of carrier form (ADR-0007): when source and target live in modules with
      // no compile-time visibility, the bridge declaration lives on a third "carrier" class in a
      // module that sees both. The emitted <Carrier>Bridge MUST land in the carrier's package, not
      // the source's — the source's module can't see the carrier and can't write into its package.
      final var compilation = compile(
        source("modela.UserEntity", "package modela; public record UserEntity(String id, String email) {}"),
        source("modelb.UserDto", "package modelb; public record UserDto(String id, String email) {}"),
        source(
          "carrier.IdentificationBridgeDef",
          """
          package carrier;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(source = modela.UserEntity.class, target = modelb.UserDto.class)
          public class IdentificationBridgeDef {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "carrier form should compile; saw " + compilation.errorMessages());
      assertNotNull(
        compilation.generated().get("carrier.IdentificationBridgeDefBridge"),
        () -> "expected carrier.IdentificationBridgeDefBridge; saw " + compilation.generated().keySet()
      );
      assertNull(
        compilation.generated().get("modela.UserEntityBridge"),
        "carrier form must NOT emit a bridge into the source's package"
      );
      assertNull(
        compilation.generated().get("modelb.UserDtoBridge"),
        "carrier form must NOT emit a bridge into the target's package"
      );
      final var bridge = compilation.generated().get("carrier.IdentificationBridgeDefBridge");
      assertTrue(
        bridge.contains("package carrier;"),
        () -> "emitted bridge should declare the carrier's package; saw:\n" + bridge
      );
      assertTrue(
        bridge.contains("Telescope<modela.UserEntity, modelb.UserDto>"),
        () -> "BRIDGE constant should be typed Telescope<Source, Target> referencing both modules; saw:\n" + bridge
      );
    }

    @Test
    @DisplayName("adopter shape: carrier + lenient + sibling @Rename + a raw collection-subtype field, all together")
    void carrierLenientRenameWithRawCollectionField() {
      // Faithful reproduction of the reported scenario: a carrier @Bridge with lenient = true and a
      // @Rename on a scalar sibling, where the bean target also nests a custom collection wrapper
      // (`class Wrap extends ArrayList<Elem>`). All four dimensions must coexist: the raw container
      // field element-bridges, the rename applies, and leniency lets the extra target field
      // default.
      final var compilation = compile(
        source(
          "modela.DocDbDetails",
          """
          package modela;
          public class DocDbDetails {
            private modela.SrcUrls imageUrls;
            private String icVerificationExt;
            public modela.SrcUrls getImageUrls() { return imageUrls; }
            public void setImageUrls(final modela.SrcUrls v) { this.imageUrls = v; }
            public String getIcVerificationExt() { return icVerificationExt; }
            public void setIcVerificationExt(final String v) { this.icVerificationExt = v; }
          }
          """
        ),
        source(
          "modela.SrcUrls",
          "package modela; import java.util.ArrayList; public class SrcUrls extends ArrayList<modela.SrcUrl> {}"
        ),
        source("modela.SrcUrl", "package modela; public record SrcUrl(String url) {}"),
        source(
          "modelb.DocBoDetails",
          """
          package modelb;
          public class DocBoDetails {
            private modelb.DstUrls imageUrls;
            private String vendorExtendedResult;
            private String extra;
            public modelb.DstUrls getImageUrls() { return imageUrls; }
            public void setImageUrls(final modelb.DstUrls v) { this.imageUrls = v; }
            public String getVendorExtendedResult() { return vendorExtendedResult; }
            public void setVendorExtendedResult(final String v) { this.vendorExtendedResult = v; }
            public String getExtra() { return extra; }
            public void setExtra(final String v) { this.extra = v; }
          }
          """
        ),
        source(
          "modelb.DstUrls",
          "package modelb; import java.util.ArrayList; public class DstUrls extends ArrayList<modelb.DstUrl> {}"
        ),
        source("modelb.DstUrl", "package modelb; public record DstUrl(String url) {}"),
        source(
          "carrier.IdentificationBridgeDef",
          """
          package carrier;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Rename;
          @Bridge(
            source = modela.DocDbDetails.class,
            target = modelb.DocBoDetails.class,
            lenient = true,
            renames = { @Rename(source = "icVerificationExt", target = "vendorExtendedResult") })
          public class IdentificationBridgeDef {}
          """
        )
      );

      assertTrue(
        compilation.success(),
        () -> "the adopter's full shape should compile; saw " + compilation.errorMessages()
      );
      final var bridge = compilation.generated().get("carrier.IdentificationBridgeDefBridge");
      assertNotNull(bridge);
      // The raw collection field element-bridges through a generated element sub-bridge...
      assertNotNull(compilation.generated().get("modela.SrcUrlToDstUrlBridge"));
      assertTrue(bridge.contains("new modelb.DstUrls()"), bridge);
      // ...and the misleading bean-introspection error never appears.
      assertFalse(compilation.hasError("no setter for 'empty'"), () -> compilation.errorMessages().toString());
    }

    @Test
    @DisplayName("carrier form: forward + backward emit reads/writes against the right field accessors")
    void carrierForwardBackwardWritesRealReads() {
      // The carrier's class itself carries no fields — adopters worry that the processor might
      // accidentally read from the empty carrier. This test pins that the emitted body reads from
      // the actual SOURCE and writes to the actual TARGET, NOT from/to the carrier.
      final var compilation = compile(
        source("a.Src", "package a; public record Src(String id, String name) {}"),
        source("b.Dst", "package b; public record Dst(String id, String name) {}"),
        source(
          "c.MyCarrier",
          """
          package c;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(source = a.Src.class, target = b.Dst.class)
          public class MyCarrier {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "carrier form should compile; saw " + compilation.errorMessages());
      final var bridge = compilation.generated().get("c.MyCarrierBridge");
      assertNotNull(bridge, "expected c.MyCarrierBridge");
      assertTrue(
        bridge.contains("s.id()") && bridge.contains("s.name()"),
        () -> "forward should read source's id + name (NOT the carrier's); saw:\n" + bridge
      );
      assertTrue(
        bridge.contains("t.id()") && bridge.contains("t.name()"),
        () -> "backward should read target's id + name (NOT the carrier's); saw:\n" + bridge
      );
    }

    @Test
    @DisplayName("carrier form: source = ... without target = ... is a precise error")
    void carrierMissingTargetIsError() {
      final var compilation = compile(
        source("a.Src", "package a; public record Src(String id) {}"),
        source(
          "c.HalfCarrier",
          """
          package c;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(source = a.Src.class)
          public class HalfCarrier {}
          """
        )
      );

      assertFalse(compilation.success(), "source without target should fail");
      assertTrue(
        compilation.hasError("source = ... requires target = ..."),
        () -> "expected precise diagnostic naming both attributes; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("carrier form: bare @Bridge (no value, no source) is a precise error — regression guard")
    void bareBridgeIsError() {
      // Now that value() defaults to Void.class to enable carrier form, the empty-annotation case
      // must still surface a precise error. Without this guard, the historical "must name a class"
      // semantics silently degrade to "compiles but does nothing useful". Regression guard.
      final var compilation = compile(
        source(
          "demo.Bare",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge
          public class Bare {}
          """
        )
      );

      assertFalse(compilation.success(), "bare @Bridge should fail");
      assertTrue(
        compilation.hasError("requires either value = TargetClass.class") ||
          compilation.hasError("source = ... + target = ..."),
        () -> "expected precise diagnostic naming both forms; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("carrier form: renames / drops apply identically to the model-anchored form")
    void carrierRespectsRenamesAndDrops() {
      // Every modifier (@Rename, drops, constants, transforms) must behave the same on the carrier
      // form as on the model-anchored form. Pin one non-trivial combo so a future refactor that
      // special-cased the carrier path can't silently lose modifier semantics.
      final var compilation = compile(
        source("a.SrcRD", "package a; public record SrcRD(String oldName, String stripMe, int value) {}"),
        source("b.DstRD", "package b; public record DstRD(String newName, int value) {}"),
        source(
          "c.CarrierRD",
          """
          package c;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Rename;
          @Bridge(source = a.SrcRD.class, target = b.DstRD.class,
            drops = { "stripMe" },
            renames = { @Rename(source = "oldName", target = "newName") })
          public class CarrierRD {}
          """
        )
      );

      assertTrue(
        compilation.success(),
        () -> "carrier + renames + drops should compile; saw " + compilation.errorMessages()
      );
      final var bridge = compilation.generated().get("c.CarrierRDBridge");
      assertNotNull(bridge);
      assertTrue(
        bridge.contains("s.oldName()") && bridge.contains("s.value()"),
        () -> "forward should read renamed-source + kept fields; saw:\n" + bridge
      );
    }
  }

  @Nested
  @DisplayName("writeStrategy — explicit hints succeed when the matching shape is present")
  class ExplicitWriteStrategy {

    @Test
    @DisplayName("writeStrategy = CONSTRUCTOR succeeds on a POJO that also has a builder() — picks ctor, not builder")
    void explicitConstructorWinsOverAvailableBuilder() {
      // AUTO probes CONSTRUCTOR before BUILDER, so picking CONSTRUCTOR explicitly produces the
      // same generated code on this fixture. The behavioural pin: if a future refactor swaps the
      // AUTO probe order to prefer BUILDER, the explicit CONSTRUCTOR hint must still bind the
      // constructor — the hint is the documented way to escape AUTO's default; falling through to
      // BUILDER on an explicit CONSTRUCTOR hint would be a silent semantic regression.
      final var compilation = compile(
        source(
          "demo.SrcEC",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.WriteStrategy;
          @Bridge(value = demo.DstEC.class, writeStrategy = WriteStrategy.CONSTRUCTOR)
          public record SrcEC(String id, int score) {}
          """
        ),
        source(
          "demo.DstEC",
          """
          package demo;
          public class DstEC {
            private final String id;
            private final int score;
            public DstEC(String id, int score) { this.id = id; this.score = score; }
            public String getId() { return id; }
            public int getScore() { return score; }
            public static Builder builder() { return new Builder(); }
            public static final class Builder {
              private String id;
              private int score;
              public Builder id(String id) { this.id = id; return this; }
              public Builder score(int score) { this.score = score; return this; }
              public DstEC build() { return new DstEC(id, score); }
            }
          }
          """
        )
      );

      assertTrue(
        compilation.success(),
        () -> "explicit CONSTRUCTOR must bind the ctor; saw " + compilation.errorMessages()
      );
      final var bridge = compilation.generated().get("demo.SrcECBridge");
      assertNotNull(bridge);
      assertTrue(
        bridge.contains("new demo.DstEC(s.id(), s.score())"),
        () -> "forward must call the matched ctor directly; saw:\n" + bridge
      );
      assertFalse(
        bridge.contains(".builder()"),
        () -> "explicit CONSTRUCTOR must NOT fall through to BUILDER even when builder() exists; saw:\n" + bridge
      );
    }

    @Test
    @DisplayName(
      "writeStrategy = BUILDER fails with a per-field diagnostic when the builder lacks a method for one field"
    )
    void builderMissingPerFieldMethodRejected() {
      // Real-world shape: a Lombok @Builder where one field's name was changed but the builder
      // method wasn't regenerated (or a hand-written builder that forgot one fluent setter). The
      // diagnostic must name the missing field AND the builder class so both ends of the
      // mismatch are visible in one read.
      final var compilation = compile(
        source(
          "demo.SrcMB",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.WriteStrategy;
          @Bridge(value = demo.DstMB.class, writeStrategy = WriteStrategy.BUILDER)
          public record SrcMB(String id, String email) {}
          """
        ),
        source(
          "demo.DstMB",
          """
          package demo;
          public class DstMB {
            private final String id;
            private final String email;
            private DstMB(String id, String email) { this.id = id; this.email = email; }
            public String getId() { return id; }
            public String getEmail() { return email; }
            public static Builder builder() { return new Builder(); }
            public static final class Builder {
              private String id;
              private String email;
              public Builder id(String id) { this.id = id; return this; }
              // No method for 'email' — pins the per-field rejection diagnostic.
              public DstMB build() { return new DstMB(id, email); }
            }
          }
          """
        )
      );

      assertFalse(compilation.success(), "builder without a per-field setter must fail");
      assertTrue(
        compilation.hasError("no method for 'email'"),
        () -> "expected per-field missing-method diagnostic; saw " + compilation.errorMessages()
      );
    }
  }

  @Nested
  @DisplayName("Sealed roots — additional rejection diagnostics")
  class SealedRejections {

    @Test
    @DisplayName(
      "sealed source subtype carrying multiple @Bridges, none targeting the sealed-target permits, is rejected"
    )
    void multiBridgeSubtypeWithNoMatchingPermitRejected() {
      // A subtype of a @Bridge'd sealed source can legitimately carry multiple @Bridge
      // annotations (one for the sealed-dispatch target, others for unrelated DTOs). When NONE
      // of those targets is in the sealed-target's permits list, the multi-target diagnostic
      // (distinct from the single-target one) must fire. Pins the multi-target branch so a
      // refactor of collectBridgeAnnotations can't silently fall into the single-target message.
      final var compilation = compile(
        source(
          "a.SealedSrc",
          """
          package a;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(b.SealedDst.class)
          public sealed interface SealedSrc permits SrcCase {}
          """
        ),
        source(
          "a.SrcCase",
          """
          package a;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(c.UnrelatedDtoA.class)
          @Bridge(c.UnrelatedDtoB.class)
          public record SrcCase(String id) implements SealedSrc {}
          """
        ),
        source(
          "b.SealedDst",
          """
          package b;
          public sealed interface SealedDst permits DstCase {}
          """
        ),
        source("b.DstCase", "package b; public record DstCase(String id) implements SealedDst {}"),
        source("c.UnrelatedDtoA", "package c; public record UnrelatedDtoA(String id) {}"),
        source("c.UnrelatedDtoB", "package c; public record UnrelatedDtoB(String id) {}")
      );

      assertFalse(compilation.success(), "multi-target subtype with no permit match must fail");
      assertTrue(
        compilation.hasError("multiple @Bridge targets, none of which is a permits of"),
        () -> "expected multi-target rejection diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("sealed source with no permits clause is rejected before any subtype probe runs")
    void sealedSourceWithEmptyPermitsRejected() {
      // A sealed interface compiles even when its permits clause is implicit AND no subtype is
      // declared in the same file. The dispatch generation needs at least one permit to emit a
      // switch, so this guard fires before any other sealed analysis touches the type — pins the
      // empty-permits branch independent of the multi-target / no-bridge subtype branches.
      final var compilation = compile(
        source(
          "demo.EmptySealed",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.EmptySealedDst.class)
          public sealed interface EmptySealed {}
          """
        ),
        source(
          "demo.EmptySealedDst",
          "package demo; public sealed interface EmptySealedDst permits demo.EmptySealedDstCase {}"
        ),
        source(
          "demo.EmptySealedDstCase",
          "package demo; public record EmptySealedDstCase(String id) implements EmptySealedDst {}"
        )
      );

      assertFalse(compilation.success(), "sealed source with no permits must fail");
      assertTrue(
        compilation.hasError("requires an explicit permits clause"),
        () -> "expected explicit-permits diagnostic; saw " + compilation.errorMessages()
      );
    }
  }

  @Nested
  @DisplayName("Carrier form — source/target top-level enforcement also covers the carrier path")
  class CarrierTopLevelEnforcement {

    @Test
    @DisplayName(
      "carrier form: source = NestedType.class is rejected with the same top-level diagnostic as the model-anchored form"
    )
    void carrierWithNestedSourceRejected() {
      // The model-anchored nested-rejection test (`nestedIsRejected`) pins the target-side guard.
      // The carrier form's source-side guard sits next to it and would silently regress if a
      // refactor of the top-level check special-cased the carrier path. Pins the source-side
      // top-level enforcement on the carrier path specifically.
      final var compilation = compile(
        source(
          "demo.Outer",
          """
          package demo;
          public class Outer {
            public record InnerSrc(String id) {}
          }
          """
        ),
        source("demo.TopTarget", "package demo; public record TopTarget(String id) {}"),
        source(
          "demo.Carrier",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(source = demo.Outer.InnerSrc.class, target = demo.TopTarget.class)
          public class Carrier {}
          """
        )
      );

      assertFalse(compilation.success(), "carrier with a nested source must fail");
      assertTrue(
        compilation.hasError("source must be a top-level type"),
        () -> "expected top-level source diagnostic; saw " + compilation.errorMessages()
      );
    }
  }

  @Nested
  @DisplayName("Primitive ↔ wrapper fields auto-bridge with box/unbox and backward null-default")
  class PrimitiveWrapperBridging {

    @Test
    @DisplayName("a boolean field bridged to a Boolean field compiles and emits a bridge instead of erroring")
    void primitiveToWrapperFieldAutoBridges() {
      final var compilation = compile(
        source(
          "demo.Src",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.Dst.class)
          public record Src(boolean locked, String name) {}
          """
        ),
        source(
          "demo.Dst",
          """
          package demo;
          public record Dst(Boolean locked, String name) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.SrcBridge");
      assertNotNull(generated, () -> "SrcBridge not generated; saw " + compilation.generated().keySet());
      // The primitive/wrapper field is handled by the PRIM_WRAPPER plan: forward auto-boxes,
      // backward unboxes and null-defaults to the primitive's JLS default.
      assertTrue(generated.contains("locked"), generated);
    }

    @Test
    @DisplayName("the reverse order (Boolean source field, boolean target field) also auto-bridges")
    void wrapperToPrimitiveFieldAutoBridges() {
      // isPrimitiveWrapperPair is order-independent; the wrapper-on-source / primitive-on-target
      // direction is a distinct code path and must compile too.
      final var compilation = compile(
        source(
          "demo.WSrc",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.WDst.class)
          public record WSrc(Integer count, String name) {}
          """
        ),
        source(
          "demo.WDst",
          """
          package demo;
          public record WDst(int count, String name) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      assertNotNull(
        compilation.generated().get("demo.WSrcBridge"),
        () -> "WSrcBridge not generated; saw " + compilation.generated().keySet()
      );
    }
  }

  @Nested
  @DisplayName("@Bridge(lenient = true) propagates leniency into nested sub-pairs")
  class LenientNestedPropagation {

    @Test
    @DisplayName(
      "a lenient parent whose nested target sub-type has an extra field compiles (sub-pair inherits lenient)"
    )
    void lenientPropagatesIntoNestedSubPair() {
      final var compilation = compile(
        source(
          "demo.Outer",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(value = demo.OuterBO.class, lenient = true)
          public record Outer(demo.Inner inner) {}
          """
        ),
        source(
          "demo.Inner",
          """
          package demo;
          public record Inner(String a) {}
          """
        ),
        source(
          "demo.OuterBO",
          """
          package demo;
          public record OuterBO(demo.InnerBO inner) {}
          """
        ),
        source(
          "demo.InnerBO",
          """
          package demo;
          public record InnerBO(String a, String extra) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      assertNotNull(
        compilation.generated().get("demo.OuterBridge"),
        () -> "OuterBridge not generated; saw " + compilation.generated().keySet()
      );
      // The nested sub-bridge is emitted leniently: InnerBO's extra field has no source counterpart
      // and is defaulted rather than failing the strict bijection. (Auto-recursed sub-pairs use the
      // disambiguated <Source>To<Target>Bridge name.)
      final var sub = compilation.generated().get("demo.InnerToInnerBOBridge");
      assertNotNull(sub, () -> "Inner sub-bridge not generated; saw " + compilation.generated().keySet());
    }

    @Test
    @DisplayName("a NON-lenient parent with an extra nested-target field still fails the nested bijection")
    void nonLenientNestedMismatchStillErrors() {
      // Same shape as above but WITHOUT lenient — the nested sub-pair must still be held to the
      // strict bijection, proving the propagation didn't make every nested pair lenient.
      final var compilation = compile(
        source(
          "demo.SOuter",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.SOuterBO.class)
          public record SOuter(demo.SInner inner) {}
          """
        ),
        source(
          "demo.SInner",
          """
          package demo;
          public record SInner(String a) {}
          """
        ),
        source(
          "demo.SOuterBO",
          """
          package demo;
          public record SOuterBO(demo.SInnerBO inner) {}
          """
        ),
        source(
          "demo.SInnerBO",
          """
          package demo;
          public record SInnerBO(String a, String extra) {}
          """
        )
      );

      assertFalse(compilation.success(), "a strict nested mismatch must still fail compilation");
      assertTrue(
        compilation.hasError("same field names"),
        () -> "expected a nested bijection diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName(
      "lenient propagates into a CONTAINER-ELEMENT sub-pair (List<Elem> whose element target has an extra field compiles)"
    )
    void lenientPropagatesIntoContainerElementSubPair() {
      // The runtime treats every nested auto-recursed pair as lenient, including container element
      // pairs. Codegen previously only propagated lenient into scalar sub-pairs; a List<Elem> whose
      // element sub-bridge has a field mismatch must now also inherit the parent's leniency.
      final var compilation = compile(
        source(
          "demo.LOuter",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(value = demo.LOuterBO.class, lenient = true)
          public record LOuter(java.util.List<demo.LElem> items) {}
          """
        ),
        source(
          "demo.LElem",
          """
          package demo;
          public record LElem(String a) {}
          """
        ),
        source(
          "demo.LOuterBO",
          """
          package demo;
          public record LOuterBO(java.util.List<demo.LElemBO> items) {}
          """
        ),
        source(
          "demo.LElemBO",
          """
          package demo;
          public record LElemBO(String a, String extra) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      assertNotNull(
        compilation.generated().get("demo.LElemToLElemBOBridge"),
        () -> "element sub-bridge not generated; saw " + compilation.generated().keySet()
      );
    }
  }
}
