package org.telescope.beans;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code @BeanBridge} processor generates working, reflection-free bridges — one per
 * auto-detected strategy. The {@code <Record>Bridge} classes referenced here are generated at test
 * compile time by {@code telescope-codegen}; if generation failed, this test would not compile.
 */
class BeanBridgeCodegenTest {

  @Test
  @DisplayName("constructor strategy: generated bridge reads and rebuilds")
  void ctorStrategy() {
    final var pojo = new CtorPojo("u1", "A@X");
    assertEquals(new CtorRecord("u1", "A@X"), CtorRecordBridge.BRIDGE.read(pojo));
    final var back = CtorRecordBridge.BRIDGE.set(pojo, new CtorRecord("u2", "B@Y"));
    assertEquals("u2", back.getId());
    assertEquals("B@Y", back.getEmail());
  }

  @Test
  @DisplayName("builder strategy: generated bridge reads and rebuilds")
  void builderStrategy() {
    final var pojo = BuilderBean.builder().id("u1").email("A@X").build();
    assertEquals(new BuilderRecord("u1", "A@X"), BuilderRecordBridge.BRIDGE.read(pojo));
    final var back = BuilderRecordBridge.BRIDGE.set(pojo, new BuilderRecord("u2", "B@Y"));
    assertEquals("u2", back.getId());
    assertEquals("B@Y", back.getEmail());
  }

  @Test
  @DisplayName("setter strategy: generated bridge reads and rebuilds")
  void setterStrategy() {
    final var pojo = new SetterBean();
    pojo.setId("u1");
    pojo.setEmail("A@X");
    assertEquals(new SetterRecord("u1", "A@X"), SetterRecordBridge.BRIDGE.read(pojo));
    final var back = SetterRecordBridge.BRIDGE.set(pojo, new SetterRecord("u2", "B@Y"));
    assertEquals("u2", back.getId());
    assertEquals("B@Y", back.getEmail());
  }
}
