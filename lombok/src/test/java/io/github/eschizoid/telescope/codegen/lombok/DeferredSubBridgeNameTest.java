package io.github.eschizoid.telescope.codegen.lombok;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.subbridge.CarriedKid;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.subbridge.CarriedKidDto;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.subbridge.CarriedParent;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.subbridge.CarriedParentDto;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.subbridge.Kid;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.subbridge.KidDto;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.subbridge.KidParent;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.subbridge.KidParentDto;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.subbridge.LoopKid;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.subbridge.LoopParent;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.subbridge.LoopParentDto;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.subbridge.TwinKid;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.subbridge.TwinKidDto;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.subbridge.TwinParent;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.subbridge.TwinParentDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A plain record bridge is planned in the first round, while the bridge for a Lombok-annotated
 * field type is written in the final one. The name the parent calls the sub-bridge by and the name
 * the sub-bridge is written under must therefore come out the same however far apart they are
 * decided.
 *
 * <p>Every fixture here is compiled by Gradle's {@code compileTestJava} with Lombok on the
 * processor path, so a parent naming a class nothing writes fails the build before any test runs.
 * The tests then pin which name each case takes and that the parent converts through it. Bridges
 * written in the final round cannot be named from this source set, so they are reached
 * reflectively.
 */
class DeferredSubBridgeNameTest {

  private static final String PKG = "io.github.eschizoid.telescope.codegen.lombok.fixtures.subbridge.";

  @Test
  @DisplayName("a Lombok field type with no @Bridge is written as <Source>To<Target>Bridge and converts")
  void undeclaredLombokChildTakesTheLongName() throws Exception {
    assertBridgeExists(PKG + "KidToKidDtoBridge");
    assertNoClass(PKG + "KidBridge");

    final var dto = (KidParentDto) bridge(PKG + "KidParentBridge").read(new KidParent("p-1", new Kid("ada", 7)));

    assertEquals("p-1", dto.id());
    assertEquals(new KidDto("ada", 7), dto.kid());
  }

  @Test
  @DisplayName("a Lombok field type declaring two targets is written under the long name for each, and converts")
  void multiTargetLombokChildTakesTheLongName() throws Exception {
    assertBridgeExists(PKG + "TwinKidToTwinKidDtoBridge");
    assertBridgeExists(PKG + "TwinKidToTwinKidViewBridge");
    assertNoClass(PKG + "TwinKidBridge");

    final var dto = (TwinParentDto) bridge(PKG + "TwinParentBridge").read(new TwinParent("p-2", new TwinKid("bo")));

    assertEquals(new TwinKidDto("bo"), dto.kid());
  }

  @Test
  @DisplayName("a Lombok field type bridged by a carrier is written as <Carrier>Bridge beside it, and converts")
  void carrierDeclaredLombokChildTakesTheCarrierName() throws Exception {
    assertBridgeExists(PKG + "carrier.CarriedKidMappingBridge");
    assertNoClass(PKG + "CarriedKidToCarriedKidDtoBridge");

    final var dto = (CarriedParentDto) bridge(PKG + "CarriedParentBridge").read(
      new CarriedParent("p-3", new CarriedKid("cy"))
    );

    assertEquals(new CarriedKidDto("cy"), dto.kid());
  }

  @Test
  @DisplayName(
    "a cycle through an undeclared Lombok child converts once per object, and the parent is written only once"
  )
  void cycleThroughUndeclaredLombokChildConverts() throws Exception {
    assertBridgeExists(PKG + "LoopKidToLoopKidDtoBridge");
    // The final round reaches the parent's pair again through the child; it already has a bridge.
    assertNoClass(PKG + "LoopParentToLoopParentDtoBridge");

    final var kid = new LoopKid();
    kid.setName("dee");
    final var parent = new LoopParent("p-4", kid);
    kid.setParent(parent);

    final var dto = (LoopParentDto) bridge(PKG + "LoopParentBridge").read(parent);

    assertEquals("p-4", dto.id());
    assertEquals("dee", dto.kid().getName());
    // The parent is already being converted when the child reaches it, so that edge converts to
    // null rather than recursing.
    assertNull(dto.kid().getParent());
    assertNotSame(kid, dto.kid());
  }

  private static void assertBridgeExists(final String fqcn) throws Exception {
    assertSame(Telescope.class, Class.forName(fqcn).getField("BRIDGE").getType(), fqcn);
  }

  private static void assertNoClass(final String fqcn) {
    assertThrows(ClassNotFoundException.class, () -> Class.forName(fqcn), fqcn + " should not be generated");
  }

  @SuppressWarnings("unchecked")
  private static Telescope<Object, Object> bridge(final String fqcn) throws Exception {
    return (Telescope<Object, Object>) Class.forName(fqcn).getField("BRIDGE").get(null);
  }
}
