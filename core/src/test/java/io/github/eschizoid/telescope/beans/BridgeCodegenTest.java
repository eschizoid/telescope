package io.github.eschizoid.telescope.beans;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
