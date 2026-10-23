package io.github.eschizoid.telescope.declaredbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.declaredbridge.DeclaredFixtures.Forward;
import io.github.eschizoid.telescope.declaredbridge.DeclaredFixtures.ForwardDto;
import io.github.eschizoid.telescope.declaredbridge.DeclaredFixtures.ForwardMappers;
import io.github.eschizoid.telescope.declaredbridge.DeclaredFixtures.OtherMappers;
import io.github.eschizoid.telescope.declaredbridge.DeclaredFixtures.Point;
import io.github.eschizoid.telescope.declaredbridge.DeclaredFixtures.PointDto;
import io.github.eschizoid.telescope.declaredbridge.DeclaredFixtures.PointMappers;
import io.github.eschizoid.telescope.declaredbridge.DeclaredFixtures.StaleMappers;
import io.github.eschizoid.telescope.introspection.OpticNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A mapper factory called from the class whose {@code @Bridge} field declares the mapper returns a
 * mapper over the generated bridge; called from any other class, it builds the runtime mapper.
 */
class DeclaredBridgeTest {

  @Test
  @DisplayName("the declaring class's mapper runs the generated bridge, both ways and in patch")
  void theDeclaringClassRunsTheBridge() {
    final var mapper = PointMappers.POINT;
    final var before = DeclaredFixtures.GENERATED_CALLS.get();
    assertEquals(new PointDto("a", "n"), mapper.forward(new Point("a", "n")));
    assertEquals(new Point("b", "m"), mapper.backward(new PointDto("b", "m")));
    assertEquals(new Point("c", "n"), mapper.patch(new Point("a", "n"), new PointDto("c", null)));
    assertEquals(3, DeclaredFixtures.GENERATED_CALLS.get() - before);
  }

  @Test
  @DisplayName("another class building the same mapper gets the runtime mapper, with the same result")
  void anotherClassBuildsTheRuntimeMapper() {
    final var mapper = OtherMappers.point();
    final var before = DeclaredFixtures.GENERATED_CALLS.get();
    assertEquals(PointMappers.POINT.forward(new Point("a", "n")), mapper.forward(new Point("a", "n")));
    assertEquals(1, DeclaredFixtures.GENERATED_CALLS.get() - before);
  }

  @Test
  @DisplayName("a bridge generated from other rows than the call's is passed over, and explain says why")
  void aStaleBridgeIsPassedOver() {
    final var mapper = StaleMappers.STALE;
    final var before = DeclaredFixtures.GENERATED_CALLS.get();
    assertEquals(new DeclaredFixtures.StaleDto("s"), mapper.forward(new DeclaredFixtures.Stale("s")));
    assertEquals(0, DeclaredFixtures.GENERATED_CALLS.get() - before);
    final var declared = mapper.explain().declared().orElseThrow();
    assertEquals(OpticNode.Runs.RUNTIME, declared.runs());
    assertTrue(declared.why().contains("generated from the rows [SameTypedTo, Drop]"), declared::why);
    assertTrue(declared.why().contains("built from [SameTypedTo]"), declared::why);
    assertTrue(declared.why().contains("re-run the annotation processor"), declared::why);
    assertTrue(mapper.explain().toString().startsWith("Runs: runtime — "), mapper.explain()::toString);
  }

  @Test
  @DisplayName("explain and trace report the generated bridge a declaring class's mapper runs")
  void explainReportsTheGeneratedBridge() {
    final var declared = PointMappers.POINT.explain().declared().orElseThrow();
    assertEquals(OpticNode.Runs.GENERATED, declared.runs());
    assertEquals(PointMappers.class.getName(), declared.declaredBy());
    assertTrue(OtherMappers.point().explain().declared().isEmpty());
    assertTrue(PointMappers.later().explain().declared().isEmpty(), "a method of the class builds the runtime mapper");
    assertTrue(PointMappers.POINT.trace(new Point("a", "n")).toString().contains("runs generated"));
  }

  @Test
  @DisplayName("hooks on the declaring class's mapper run around the generated bridge")
  void hooksRunAroundTheBridge() {
    final var hooked = PointMappers.POINT
      .beforeForward(p -> new Point(p.name().trim(), p.note()))
      .afterForward(d -> new PointDto(d.label().toUpperCase(), d.note()))
      .beforeBackward(d -> new PointDto(d.label().trim(), d.note()))
      .afterBackward(p -> new Point(p.name().toLowerCase(), p.note()));
    final var before = DeclaredFixtures.GENERATED_CALLS.get();
    assertEquals(new PointDto("A", "n"), hooked.forward(new Point(" a ", "n")));
    assertEquals(new Point("b", "m"), hooked.backward(new PointDto(" B ", "m")));
    assertEquals(2, DeclaredFixtures.GENERATED_CALLS.get() - before);
  }

  @Test
  @DisplayName("a forward-only mapper the declaring class builds runs the generated bridge")
  void aForwardMapperRunsTheBridge() {
    final var before = DeclaredFixtures.GENERATED_CALLS.get();
    assertEquals(new ForwardDto("x"), ForwardMappers.FORWARD.forward(new Forward("x")));
    assertEquals(1, DeclaredFixtures.GENERATED_CALLS.get() - before);
  }
}
