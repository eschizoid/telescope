package io.github.eschizoid.telescope.declaredbridge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.BridgeGrant;
import io.github.eschizoid.telescope.conversion.BridgeProvider;
import io.github.eschizoid.telescope.conversion.BridgeRegistry;
import io.github.eschizoid.telescope.conversion.ForwardMapper;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.declaredbridge.DeclaredFixtures.Forward;
import io.github.eschizoid.telescope.declaredbridge.DeclaredFixtures.ForwardDto;
import io.github.eschizoid.telescope.declaredbridge.DeclaredFixtures.ForwardMappers;
import io.github.eschizoid.telescope.declaredbridge.DeclaredFixtures.OtherMappers;
import io.github.eschizoid.telescope.declaredbridge.DeclaredFixtures.Point;
import io.github.eschizoid.telescope.declaredbridge.DeclaredFixtures.PointDto;
import io.github.eschizoid.telescope.declaredbridge.DeclaredFixtures.PointMappers;
import io.github.eschizoid.telescope.declaredbridge.DeclaredFixtures.StaleMappers;
import io.github.eschizoid.telescope.introspection.OpticNode;
import java.util.concurrent.atomic.AtomicReference;
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
    assertEquals(null, mapper.forward(null));
    assertEquals(null, mapper.backward(null));
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
  @DisplayName("a bridge generated from rows naming other accessors is passed over on the JVM")
  void aBridgeFromOtherAccessorsIsPassedOver() {
    // An image compares row kinds only; the accessors are read on the JVM.
    assumeTrue(System.getProperty("org.graalvm.nativeimage.imagecode") == null);
    final var mapper = DeclaredFixtures.MovedMappers.MOVED;
    final var before = DeclaredFixtures.GENERATED_CALLS.get();
    assertEquals(new DeclaredFixtures.MovedDto("n"), mapper.forward(new DeclaredFixtures.Moved("n", "a")));
    assertEquals(0, DeclaredFixtures.GENERATED_CALLS.get() - before);
    final var declared = mapper.explain().declared().orElseThrow();
    assertEquals(OpticNode.Runs.RUNTIME, declared.runs());
    assertTrue(declared.why().contains("rows naming the accessors [alias>label, name>]"), declared::why);
    assertTrue(declared.why().contains("built from rows naming [name>label, alias>]"), declared::why);
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
    final var hooked = PointMappers.POINT.beforeForward(p -> new Point(p.name().trim(), p.note()))
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
    assertEquals(null, ForwardMappers.FORWARD.forward(null));
  }

  @Test
  @DisplayName("the declaring class's mapper is the class generated for the field, and keeps the mapper's state")
  void theFieldsMapperIsTheGeneratedClass() {
    final var mapper = assertInstanceOf(DeclaredFixtures.PointFieldMapper.class, PointMappers.POINT);
    assertEquals(Point.class, mapper.sourceClass());
    assertEquals(PointDto.class, mapper.targetClass());
    assertEquals(OpticNode.Runs.GENERATED, mapper.explain().declared().orElseThrow().runs());
    assertEquals(null, mapper.patch(null, new PointDto("c", null)));
    assertInstanceOf(DeclaredFixtures.ForwardFieldMapper.class, ForwardMappers.FORWARD);
    assertEquals(OpticNode.Runs.GENERATED, ForwardMappers.FORWARD.explain().declared().orElseThrow().runs());
  }

  @Test
  @DisplayName("only a mapper over a generated bridge with no hooks can be taken by a generated class")
  void onlyAGeneratedMapperIsSpecialised() {
    // Initialising the declaring class dispatches its field's factory call to the provider.
    assertNotNull(PointMappers.POINT);
    final var grant = DeclaredFixtures.HANDED.get();
    assertNotNull(grant, "the registered provider is handed the grant at dispatch");
    assertThrows(IllegalArgumentException.class, () ->
      new DeclaredFixtures.PointFieldMapper(OtherMappers.point(), grant)
    );
    assertThrows(IllegalArgumentException.class, () ->
      new DeclaredFixtures.PointFieldMapper(PointMappers.POINT.beforeForward(p -> p), grant)
    );
    assertThrows(IllegalArgumentException.class, () ->
      new DeclaredFixtures.ForwardFieldMapper(Telescope.mapperForward(Forward.class, ForwardDto.class), grant)
    );
  }

  @Test
  @DisplayName(
    "a class written by hand cannot wrap a field's mapper, and an unregistered provider is never handed the grant"
  )
  void aHandWrittenClassHoldsNoGrant() {
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      new DeclaredFixtures.PointFieldMapper(PointMappers.POINT, null)
    );
    assertTrue(refused.getMessage().contains("with the grant its provider is handed"), refused::getMessage);
    assertThrows(IllegalArgumentException.class, () ->
      new DeclaredFixtures.ForwardFieldMapper(ForwardMappers.FORWARD, null)
    );
    final var captured = new AtomicReference<BridgeGrant>();
    final var unregistered = new BridgeProvider() {
      @Override
      public Class<?> sourceType() {
        return Point.class;
      }

      @Override
      public Class<?> targetType() {
        return PointDto.class;
      }

      @Override
      public Class<?> declaringType() {
        return PointMappers.class;
      }

      @Override
      public Object bridge() {
        return DeclaredFixtures.POINT_BRIDGE;
      }

      @Override
      public Mapper<?, ?> mapper(final Mapper<?, ?> built, final BridgeGrant grant) {
        captured.set(grant);
        return built;
      }

      @Override
      public ForwardMapper<?, ?> forwardMapper(final ForwardMapper<?, ?> built, final BridgeGrant grant) {
        captured.set(grant);
        return built;
      }
    };
    assertSame(PointMappers.POINT, BridgeRegistry.specialise(unregistered, PointMappers.POINT));
    assertSame(ForwardMappers.FORWARD, BridgeRegistry.specialise(unregistered, ForwardMappers.FORWARD));
    assertNull(captured.get());
  }

  @Test
  @DisplayName("a call through a method reference or by reflection from the static initialiser gets the runtime mapper")
  void aCallWithAFrameBetweenGetsTheRuntimeMapper() {
    assertAll(
      () -> assertTrue(PointMappers.THROUGH_REFERENCE.explain().declared().isEmpty(), "through a method reference"),
      () -> assertTrue(PointMappers.THROUGH_REFLECTION.explain().declared().isEmpty(), "by reflection"),
      () -> assertFalse(PointMappers.THROUGH_REFERENCE instanceof DeclaredFixtures.PointFieldMapper),
      () -> assertFalse(PointMappers.THROUGH_REFLECTION instanceof DeclaredFixtures.PointFieldMapper)
    );
    final var before = DeclaredFixtures.GENERATED_CALLS.get();
    assertEquals(new PointDto("a", "n"), PointMappers.THROUGH_REFERENCE.forward(new Point("a", "n")));
    assertEquals(0, DeclaredFixtures.GENERATED_CALLS.get() - before);
  }
}
