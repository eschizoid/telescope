package io.github.eschizoid.telescope.beans;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code @Bridge} processor generates working, reflection-free bridges for every
 * type-pair combination — record&rarr;POJO (each construction strategy), record&harr;record, and
 * POJO&harr;POJO. The {@code <Source>Bridge} classes referenced here are generated at test compile
 * time by {@code telescope-codegen}; if generation failed, this test would not compile.
 *
 * <p>{@code @Bridge} reads the source's fields and rebuilds the target (forward), and vice versa
 * (backward). The annotated type is the source, so {@code CtorRecordBridge.BRIDGE} is a {@code
 * Telescope<CtorRecord, CtorPojo>}: {@code read(record)} produces the POJO.
 */
class BridgeCodegenTest {

  @Test
  @DisplayName("record -> POJO via the target's all-args constructor")
  void ctorStrategy() {
    final var record = new CtorRecord("u1", "A@X");
    final var pojo = CtorRecordBridge.BRIDGE.read(record);
    assertEquals("u1", pojo.getId());
    assertEquals("A@X", pojo.getEmail());

    final var back = CtorRecordBridge.BRIDGE.set(record, new CtorPojo("u2", "B@Y"));
    assertEquals(new CtorRecord("u2", "B@Y"), back);
  }

  @Test
  @DisplayName("record -> POJO via the target's builder()")
  void builderStrategy() {
    final var record = new BuilderRecord("u1", "A@X");
    final var bean = BuilderRecordBridge.BRIDGE.read(record);
    assertEquals("u1", bean.getId());
    assertEquals("A@X", bean.getEmail());

    final var back = BuilderRecordBridge.BRIDGE.set(record, BuilderBean.builder().id("u2").email("B@Y").build());
    assertEquals(new BuilderRecord("u2", "B@Y"), back);
  }

  @Test
  @DisplayName("record -> POJO via the target's no-arg constructor + setters")
  void setterStrategy() {
    final var record = new SetterRecord("u1", "A@X");
    final var bean = SetterRecordBridge.BRIDGE.read(record);
    assertEquals("u1", bean.getId());
    assertEquals("A@X", bean.getEmail());

    final var replacement = new SetterBean();
    replacement.setId("u2");
    replacement.setEmail("B@Y");
    final var back = SetterRecordBridge.BRIDGE.set(record, replacement);
    assertEquals(new SetterRecord("u2", "B@Y"), back);
  }

  @Test
  @DisplayName("record <-> record (both via canonical constructor)")
  void recordToRecord() {
    final var a = new BridgeRecA("u1", 10);
    assertEquals(new BridgeRecB("u1", 10), BridgeRecABridge.BRIDGE.read(a));

    final var back = BridgeRecABridge.BRIDGE.set(a, new BridgeRecB("u2", 20));
    assertEquals(new BridgeRecA("u2", 20), back);
  }

  @Test
  @DisplayName("POJO <-> POJO (both via no-arg constructor + setters)")
  void pojoToPojo() {
    final var a = new BridgePojoA();
    a.setId("u1");
    a.setEmail("A@X");

    final var b = BridgePojoABridge.BRIDGE.read(a);
    assertEquals("u1", b.getId());
    assertEquals("A@X", b.getEmail());

    final var replacement = new BridgePojoB();
    replacement.setId("u2");
    replacement.setEmail("B@Y");
    final var back = BridgePojoABridge.BRIDGE.set(a, replacement);
    assertEquals("u2", back.getId());
    assertEquals("B@Y", back.getEmail());
  }

  @Test
  @DisplayName("primitive ↔ wrapper fields: forward auto-boxes; backward unboxes a non-null wrapper")
  void primitiveWrapperRoundTrip() {
    final var src = new BridgePrimRec(true, 7, "n");

    // forward: boolean → Boolean, int → Integer (auto-box, always safe).
    final var bo = BridgePrimRecBridge.BRIDGE.read(src);
    assertEquals(Boolean.TRUE, bo.locked());
    assertEquals(Integer.valueOf(7), bo.count());
    assertEquals("n", bo.name());

    // backward: a non-null Boolean / Integer auto-unboxes back to the primitives.
    final var back = BridgePrimRecBridge.BRIDGE.set(src, new BridgePrimRecBO(false, 9, "m"));
    assertEquals(false, back.locked());
    assertEquals(9, back.count());
    assertEquals("m", back.name());
  }

  @Test
  @DisplayName(
    "primitive ↔ wrapper backward with a null wrapper null-defaults to the primitive's JLS default (parity with runtime primitiveWrapperIso)"
  )
  void primitiveWrapperBackwardNullUnboxDefaults() {
    // A null wrapper on the backward (unbox) direction must coalesce to the primitive's JLS default
    // (false / 0), not NPE — matching the runtime DeepMap primitiveWrapperIso, which null-defaults
    // both directions of the box/unbox conversion.
    final var src = new BridgePrimRec(true, 7, "n");
    final var back = BridgePrimRecBridge.BRIDGE.set(src, new BridgePrimRecBO(null, null, "m"));
    assertEquals(false, back.locked());
    assertEquals(0, back.count());
    assertEquals("m", back.name());
  }

  @Test
  @DisplayName(
    "primitive ↔ wrapper forward with a null wrapper source null-defaults the primitive target (the other direction's wiring)"
  )
  void primitiveWrapperForwardNullDefaults() {
    // Reverse orientation: wrapper source, primitive target — so the FORWARD (read) direction
    // writes
    // the primitive and must null-default a null wrapper source to false / 0, pinning the
    // fwdNullDefault wiring at runtime (the backward test pins bwdNullDefault).
    final var src = new BridgeWrapRec(null, null, "m");
    final var bo = BridgeWrapRecBridge.BRIDGE.read(src);
    assertEquals(false, bo.flag());
    assertEquals(0, bo.num());
    assertEquals("m", bo.name());
  }

  @Test
  @DisplayName(
    "Optional bridge: a null Optional reference passes through as null instead of NPE (parity with Iso.liftOptional)"
  )
  void optionalNullReferencePassesThrough() {
    // A null Optional field reference (not Optional.empty()) must not NPE on the generated
    // .map(...).
    // The runtime liftOptional guards `ox == null ? null`; the codegen must match both directions.
    final var dstForward = assertDoesNotThrow(() -> OptSrcBridge.BRIDGE.read(new OptSrc(null)));
    assertNull(dstForward.maybe(), "null Optional source bridges forward to null, not NPE");

    final var srcBackward = assertDoesNotThrow(() ->
      OptSrcBridge.BRIDGE.set(new OptSrc(Optional.empty()), new OptDst(null))
    );
    assertNull(srcBackward.maybe(), "null Optional target bridges backward to null, not NPE");
  }

  @Test
  @DisplayName("Optional bridge: a present Optional round-trips through the element sub-bridge")
  void optionalPresentRoundTrips() {
    final var src = new OptSrc(Optional.of(new OptElem("x")));
    final var dst = OptSrcBridge.BRIDGE.read(src);
    assertEquals(Optional.of(new OptElemBO("x")), dst.maybe());
    final var back = OptSrcBridge.BRIDGE.set(src, dst);
    assertEquals(Optional.of(new OptElem("x")), back.maybe());
  }

  @Test
  @DisplayName(
    "nullable→Optional bridge: a null Optional target reference passes through backward as null instead of NPE"
  )
  void nullableToOptionalBackwardNullGuard() {
    // NtoOSrc.maybe is a plain (nullable) OptElem; NtoODst.maybe is Optional<OptElemBO>. The
    // backward
    // (set) direction reads the TARGET Optional, so a null Optional reference there must not NPE on
    // the generated .map(...) — exercising the NULLABLE_TO_OPTIONAL backward guard.
    final var back = assertDoesNotThrow(() -> NtoOSrcBridge.BRIDGE.set(new NtoOSrc(null), new NtoODst(null)));
    assertNull(back.maybe(), "null Optional target bridges backward to a null source field, not NPE");

    // Forward and a present round-trip still work.
    final var fwd = NtoOSrcBridge.BRIDGE.read(new NtoOSrc(new OptElem("y")));
    assertEquals(Optional.of(new OptElemBO("y")), fwd.maybe());
  }

  @Test
  @DisplayName(
    "container bridge: a null element passes through as null instead of NPE (parity with the runtime element Iso)"
  )
  void containerNullElementPassesThrough() {
    // A null element inside a bridged List must map to null, not NPE on subBridge.forward(null).
    final var src = new ElemListSrc(Arrays.asList(new OptElem("a"), null));
    final var dst = assertDoesNotThrow(() -> ElemListSrcBridge.BRIDGE.read(src));
    assertEquals(2, dst.items().size());
    assertEquals(new OptElemBO("a"), dst.items().get(0));
    assertNull(dst.items().get(1), "null element bridges to null");

    // The backward direction shares the same per-element guard: a null element survives set(...).
    final var back = assertDoesNotThrow(() ->
      ElemListSrcBridge.BRIDGE.set(src, new ElemListDst(Arrays.asList(new OptElemBO("a"), null)))
    );
    assertEquals(2, back.items().size());
    assertEquals(new OptElem("a"), back.items().get(0));
    assertNull(back.items().get(1), "null element bridges backward to null");
  }

  @Test
  @DisplayName(
    "concrete container subtype: a LinkedList<Y> target field is bridged element-wise and rebuilt as a LinkedList, not the default ArrayList (parity with the runtime container allocation table)"
  )
  void concreteListSubtypeRebuildsAsTargetClass() {
    final var src = new ConcreteListSrc(Arrays.asList(new OptElem("a"), new OptElem("b")));
    final var dst = ConcreteListSrcBridge.BRIDGE.read(src);
    assertInstanceOf(LinkedList.class, dst.items(), "target's declared concrete class is preserved");
    assertEquals(Arrays.asList(new OptElemBO("a"), new OptElemBO("b")), dst.items());

    final var back = ConcreteListSrcBridge.BRIDGE.set(src, dst);
    assertEquals(Arrays.asList(new OptElem("a"), new OptElem("b")), back.items());
  }

  @Test
  @DisplayName(
    "concrete container subtype with identity elements: a List<String> ↔ LinkedList<String> copies inline into the target's concrete class"
  )
  void concreteListSubtypeIdentityElementCopiesIntoTargetClass() {
    final var src = new IdListSrc(Arrays.asList("x", "y"));
    final var dst = IdListSrcBridge.BRIDGE.read(src);
    assertInstanceOf(LinkedList.class, dst.tags(), "identity-element copy lands in the target's concrete class");
    assertEquals(Arrays.asList("x", "y"), dst.tags());
  }

  @Test
  @DisplayName(
    "concrete Set subtype: a TreeSet<String> target is rebuilt as a TreeSet via the no-presize copy constructor"
  )
  void concreteSetSubtypeRebuildsAsTreeSet() {
    // Seed an insertion-ordered (unsorted) source so the [a, b, c] result proves the TreeSet's sort
    // is active, independent of the instanceof check.
    final var src = new SetIdSrc(new LinkedHashSet<>(Arrays.asList("b", "a", "c")));
    final var dst = SetIdSrcBridge.BRIDGE.read(src);
    assertInstanceOf(TreeSet.class, dst.tags(), "target's declared TreeSet class is preserved");
    assertEquals(Arrays.asList("a", "b", "c"), List.copyOf(dst.tags()));
  }

  @Test
  @DisplayName(
    "concrete Map subtype: a TreeMap<String, Y> target bridges values element-wise and is rebuilt as a TreeMap"
  )
  void concreteMapSubtypeRebuildsAsTreeMap() {
    final Map<String, OptElem> in = new LinkedHashMap<>();
    in.put("k1", new OptElem("a"));
    in.put("k2", new OptElem("b"));
    final var dst = MapBrSrcBridge.BRIDGE.read(new MapBrSrc(in));
    assertInstanceOf(TreeMap.class, dst.byKey(), "target's declared TreeMap class is preserved");
    assertEquals(new OptElemBO("a"), dst.byKey().get("k1"));
    assertEquals(new OptElemBO("b"), dst.byKey().get("k2"));
  }

  @Test
  @DisplayName(
    "backward concrete container: a LinkedList<String> source field is rebuilt as a LinkedList on the backward pass"
  )
  void backwardConcreteContainerRebuildsAsSourceClass() {
    final var src = new BwdConcreteSrc(new LinkedList<>(Arrays.asList("x", "y")));
    final var dst = BwdConcreteSrcBridge.BRIDGE.read(src);
    final var back = BwdConcreteSrcBridge.BRIDGE.set(src, dst);
    assertInstanceOf(LinkedList.class, back.tags(), "backward rebuild lands in the source's concrete class");
    assertEquals(Arrays.asList("x", "y"), back.tags());
  }
}
