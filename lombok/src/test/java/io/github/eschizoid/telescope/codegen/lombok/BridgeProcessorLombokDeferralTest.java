package io.github.eschizoid.telescope.codegen.lombok;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.BridgedDataUser;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.BridgedDataUserDto;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.BridgedGetterSetterUser;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.BridgedRecordTarget;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.PlainParentWithLombokChild;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.PlainParentWithLombokChildDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins the round-deferred emission contract added to {@code BridgeProcessor} so that
 * {@code @Bridge} on Lombok-annotated types produces a fully-populated {@code <X>Bridge.BRIDGE}.
 *
 * <p>Before the fix, {@code BridgeProcessor} emitted in round 1. {@code Elements.getAllMembers(
 * Lombok-annotated-class)} returned the un-patched member list (no getters/setters/builder), and
 * the emitted {@code BRIDGE} body had no field rows — every {@code forward}/{@code backward} call
 * collapsed to a same-class no-op. After the fix, emission defers to {@code processingOver()}, by
 * which point Lombok's AST visitors have fired and the synthesized accessors are visible. The
 * round-trip assertions below would fail wholesale on the pre-fix processor — they are the real
 * regression guard.
 *
 * <p>Like {@link LombokFocusProcessorTest}, this test cannot use the in-memory {@code
 * ProcessorHarness} (Lombok's javac AST hook doesn't install correctly in the in-process {@code
 * JavaCompiler.CompilationTask} flow). It runs under Gradle's standard {@code compileTestJava}
 * pipeline.
 */
class BridgeProcessorLombokDeferralTest {

  @Nested
  @DisplayName("@Data ↔ @Data — both sides carry the @Data trigger")
  class DataBothSides {

    @Test
    @DisplayName("BridgedDataUserBridge.BRIDGE is generated and exposes a Telescope<Source, Target>")
    void bridgeConstantExists() throws Exception {
      final var bridgeClass = Class.forName(
        "io.github.eschizoid.telescope.codegen.lombok.fixtures.BridgedDataUserBridge"
      );
      assertNotNull(bridgeClass);
      final var bridgeField = bridgeClass.getField("BRIDGE");
      assertNotNull(bridgeField);
      assertSame(Telescope.class, bridgeField.getType());
      assertNotNull(bridgeField.get(null), "BRIDGE constant must be initialized at class load");
    }

    @Test
    @DisplayName(
      "BRIDGE.read(source) maps every field across @Data accessors — proves Lombok-patched members were seen"
    )
    void forwardReadsAllFields() throws Exception {
      final var bridge = lookupBridge("BridgedDataUserBridge");
      final var src = new BridgedDataUser("u-42", "alice@example.com");

      final var dto = bridge.read(src);

      assertEquals(BridgedDataUserDto.class, dto.getClass());
      // If round-deferral had not landed, the BRIDGE body would have no field rows; dto.getId() and
      // dto.getEmail() would BOTH be null even with a populated source. The two assertions below
      // are the failure surface that would catch a regression to round-1 emission.
      assertEquals("u-42", ((BridgedDataUserDto) dto).getId());
      assertEquals("alice@example.com", ((BridgedDataUserDto) dto).getEmail());
    }

    @Test
    @DisplayName("BRIDGE round-trips source → target → source losslessly")
    void roundTrip() throws Exception {
      final var forward = lookupBridge("BridgedDataUserBridge");
      final var src = new BridgedDataUser("u-7", "bob@example.com");
      final var dto = (BridgedDataUserDto) forward.read(src);
      // Re-build via setters to verify the backward direction also reads through Lombok-synthesized
      // getters. Same regression guard as the forward direction.
      final var rebuilt = new BridgedDataUser();
      rebuilt.setId(dto.getId());
      rebuilt.setEmail(dto.getEmail());
      assertEquals(src, rebuilt);
    }
  }

  @Nested
  @DisplayName("@Getter+@Setter (NOT @Data) — broader trigger set catches member-synthesizing annotations")
  class GetterSetterTrigger {

    @Test
    @DisplayName("BridgedGetterSetterUserBridge generates correctly — @Getter/@Setter alone triggers deferral")
    void bridgeConstantExists() throws Exception {
      final var bridgeClass = Class.forName(
        "io.github.eschizoid.telescope.codegen.lombok.fixtures.BridgedGetterSetterUserBridge"
      );
      assertNotNull(bridgeClass);
      final var bridgeField = bridgeClass.getField("BRIDGE");
      assertNotNull(bridgeField);
      assertNotNull(bridgeField.get(null));
    }

    @Test
    @DisplayName("forward reads through Lombok-synthesized getters into a plain record target")
    void forwardReadsThroughLombokAccessorsIntoRecord() throws Exception {
      final var bridge = lookupBridge("BridgedGetterSetterUserBridge");
      final var src = new BridgedGetterSetterUser();
      src.setId("u-99");
      src.setEmail("carol@example.com");

      final var target = bridge.read(src);

      assertEquals(BridgedRecordTarget.class, target.getClass());
      // The pre-fix failure mode: target.id() / target.email() are null because the @Getter-on-
      // BridgedGetterSetterUser accessors weren't visible to BridgeProcessor in round 1. Pins the
      // contract that the broader LOMBOK_SYNTHESIZING_ANNOTATIONS set catches @Getter/@Setter, not
      // just @Data/@Value/@Builder.
      assertEquals("u-99", ((BridgedRecordTarget) target).id());
      assertEquals("carol@example.com", ((BridgedRecordTarget) target).email());
    }
  }

  @Nested
  @DisplayName("Recursive sub-pair deferral — plain parent, Lombok child")
  class RecursiveSubPairDeferral {

    @Test
    @DisplayName(
      "plain @Bridge parent whose CHILD field is Lombok-annotated still recursively writes through patched accessors"
    )
    void plainParentLombokChildSubBridgeStillWorks() throws Exception {
      // Failure mode this pins: the parent pair is eager (no Lombok on parent), so it drains in
      // round 1. During the parent's emission, BridgeProcessor recursively discovers the sub-pair
      // BridgedDataUser ↔ BridgedDataUserDto. Without the recursive Lombok-deferral fix, that
      // sub-pair is pushed onto the eager queue and emitted in round 1 — when Lombok's AST patches
      // haven't fired yet and `Elements.getAllMembers(BridgedDataUser)` returns the un-patched
      // empty member list. The emitted sub-bridge's body has no field rows, every child.id /
      // child.email read collapses to null, and the parent's forward returns a Dto whose child has
      // null id/email. The assertion below would fail wholesale on that pre-fix processor.
      final var bridge = lookupBridge("PlainParentWithLombokChildBridge");
      final var child = new BridgedDataUser("u-9", "child@example.com");
      final var parent = new PlainParentWithLombokChild("parent-1", child);

      final var dto = (PlainParentWithLombokChildDto) bridge.read(parent);

      assertEquals("parent-1", dto.id(), "top-level field of plain parent → plain target");
      assertEquals(
        "u-9",
        dto.child().getId(),
        "child field MUST round-trip through Lombok accessors — pins recursive deferral"
      );
      assertEquals("child@example.com", dto.child().getEmail(), "child email MUST round-trip");
    }
  }

  // Reflective lookup of the generated <X>Bridge.BRIDGE — keeps this test from a compile-time
  // dependency on the generated class (which lives in the test source set's annotation-processed
  // output) and lets us assert on the same class-load path an adopter would take.
  @SuppressWarnings("unchecked")
  private static Telescope<Object, Object> lookupBridge(final String simpleName) throws Exception {
    final var bridgeClass = Class.forName("io.github.eschizoid.telescope.codegen.lombok.fixtures." + simpleName);
    final var bridgeField = bridgeClass.getField("BRIDGE");
    return (Telescope<Object, Object>) bridgeField.get(null);
  }
}
