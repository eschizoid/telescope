package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.eschizoid.telescope.conversion.ForwardMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins the new container-lifts on {@link ForwardMapper} ({@code liftList}, {@code liftSet}, {@code
 * liftOptional}, {@code liftMapValues}) plus the {@code Mapper.toForwardMapper()} and {@code
 * Telescope.asForwardMapper(...)} cross-tier conversions. Forward-only mirror of {@code
 * Mapper.liftList/Set/Optional/MapValues} plus the {@code @Bridge → ForwardMapper} bean-wiring
 * ergonomic.
 */
class ForwardMapperLiftsAndConversionsTest {

  record Entity(String id, String email) {}

  record Dto(String id, String email) {}

  @Nested
  @DisplayName("ForwardMapper.liftList / liftSet / liftOptional / liftMapValues")
  class ContainerLifts {

    @Test
    @DisplayName("liftList — element-wise forward over a List; null list round-trips to null")
    void liftList() {
      final var elem = Telescope.mapperForward(Entity.class, Dto.class);
      final var lifted = elem.liftList();
      final var out = lifted.forward(List.of(new Entity("a", "alice@example.com"), new Entity("b", "bob@example.com")));
      assertEquals(2, out.size());
      assertEquals("a", out.get(0).id());
      assertEquals("bob@example.com", out.get(1).email());
      // Concrete output class is ArrayList (mirrors Iso.liftList).
      assertEquals(ArrayList.class, out.getClass());

      // Null pass-through.
      assertNull(lifted.forward(null));
    }

    @Test
    @DisplayName("liftSet — element-wise forward over a Set; output is LinkedHashSet preserving forward-pass order")
    void liftSet() {
      final var lifted = Telescope.mapperForward(Entity.class, Dto.class).liftSet();
      final var input = new java.util.LinkedHashSet<Entity>();
      input.add(new Entity("a", "alice@example.com"));
      input.add(new Entity("b", "bob@example.com"));

      final var out = lifted.forward(input);
      assertEquals(2, out.size());
      assertEquals(LinkedHashSet.class, out.getClass());

      assertNull(lifted.forward(null));
    }

    @Test
    @DisplayName("liftOptional — Optional.empty round-trips to Optional.empty; null reference to null")
    void liftOptional() {
      final var lifted = Telescope.mapperForward(Entity.class, Dto.class).liftOptional();
      final var out = lifted.forward(Optional.of(new Entity("a", "alice@example.com")));
      assertEquals("a", out.orElseThrow().id());

      final var empty = lifted.forward(Optional.empty());
      assertEquals(Optional.empty(), empty);

      // Null reference pass-through (records may legally hold a null Optional).
      assertNull(lifted.forward(null));
    }

    @Test
    @DisplayName("liftMapValues — keys preserved verbatim; values flow through forward; null map to null")
    void liftMapValues() {
      final ForwardMapper<Map<String, Entity>, Map<String, Dto>> lifted = Telescope.mapperForward(
        Entity.class,
        Dto.class
      ).liftMapValues();
      final var input = new LinkedHashMap<String, Entity>();
      input.put("k1", new Entity("v1", "alice@example.com"));
      final var out = lifted.forward(input);
      assertEquals(1, out.size());
      assertEquals("v1", out.get("k1").id());
      // Keys are preserved verbatim.
      assertEquals(Set.of("k1"), out.keySet());

      assertNull(lifted.forward(null));
    }
  }

  @Nested
  @DisplayName("Cross-tier conversions: Mapper.toForwardMapper + Telescope.asForwardMapper")
  class CrossTierConversions {

    record A(String id) {}

    record B(String id) {}

    @Test
    @DisplayName("Mapper.toForwardMapper() projects a bidirectional Mapper to a forward-only ForwardMapper")
    void mapperToForwardMapper() {
      final var bidi = Telescope.mapper(A.class, B.class);
      final var fm = bidi.toForwardMapper();
      assertEquals(A.class, fm.sourceClass());
      assertEquals(B.class, fm.targetClass());
      final var out = fm.forward(new A("o1"));
      assertEquals("o1", out.id());
    }

    @Test
    @DisplayName("Telescope.asForwardMapper(srcClass, tgtClass) wraps a Telescope path as a forward-only mapper")
    void telescopeAsForwardMapper() {
      // Telescope<A, A> identity — the asForwardMapper test cares about the wrapping shape, not
      // the path complexity.
      final var path = Telescope.of(A.class);
      final var fm = path.asForwardMapper(A.class, A.class);
      assertEquals(A.class, fm.sourceClass());
      assertEquals(A.class, fm.targetClass());
      final var out = fm.forward(new A("o1"));
      assertEquals("o1", out.id());
    }
  }
}
