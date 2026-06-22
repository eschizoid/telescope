package io.github.eschizoid.telescope.beans;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
      OptSrcBridge.BRIDGE.set(new OptSrc(java.util.Optional.empty()), new OptDst(null))
    );
    assertNull(srcBackward.maybe(), "null Optional target bridges backward to null, not NPE");
  }

  @Test
  @DisplayName("Optional bridge: a present Optional round-trips through the element sub-bridge")
  void optionalPresentRoundTrips() {
    final var src = new OptSrc(java.util.Optional.of(new OptElem("x")));
    final var dst = OptSrcBridge.BRIDGE.read(src);
    assertEquals(java.util.Optional.of(new OptElemBO("x")), dst.maybe());
    final var back = OptSrcBridge.BRIDGE.set(src, dst);
    assertEquals(java.util.Optional.of(new OptElem("x")), back.maybe());
  }
}
