package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for intermediate target allocation — closes the v1.0 limitation that previously
 * documented in {@code Mapping.to(Accessor, Telescope)}'s javadoc.
 *
 * <p>When a flat source field is routed through a multi-hop target telescope, the engine now
 * synthesizes a recursive default-tree instance of the intermediate at the first hop (and every
 * subsequent record-typed hop) instead of registering a {@code null} placeholder. The post-fixup
 * overlay then descends into the allocated structure and writes the leaf — no per-hop allocation
 * glue from the user, no fall-back to {@code Mapping.via(...)}.
 *
 * <p>Records only for v1.0. Bean intermediates still need a {@code Mapping.via(...)} workaround
 * because bean construction (no-arg ctor vs. builder vs. fields-only) isn't always one-shot.
 */
class MappingIntermediateAllocationTest {

  // --- One-hop intermediate (the original v1.0 wart) ---
  record OneHopAddress(String city, String zip) {}

  record OneHopPerson(String name, int age, OneHopAddress address) {}

  // --- Multi-hop intermediate (the case the type-system-driven allocator handles for free) ---
  record Geocode(double lat, double lon) {}

  record DeepAddress(String city, String zip, Geocode geocode) {}

  record DeepPerson(String name, int age, DeepAddress address) {}

  // --- Even deeper, for the "type-driven recursion" claim ---
  record Inner(String value) {}

  record Mid(Inner inner) {}

  record Outer(Mid mid) {}

  record DeepTarget(Outer outer) {}

  @Nested
  @DisplayName("one-hop intermediate — Slim source → Person target with allocated Address")
  class OneHopAllocation {

    @Test
    @DisplayName("flat source with no `address` field still routes through Person.address.city")
    void flatSourceRoutesIntoAllocatedAddress() {
      record Slim(String displayCity) {}

      final var mapper = Telescope.mapper(
        Slim.class,
        OneHopPerson.class,
        to(Slim::displayCity, Telescope.of(OneHopPerson.class).field(OneHopPerson::address).field(OneHopAddress::city))
      );

      final var out = mapper.forward(new Slim("Brooklyn"));
      assertNotNull(out.address(), "intermediate Address should be allocated, not null");
      assertEquals("Brooklyn", out.address().city());
      // Primitive default; default for non-claimed reference fields is null.
      assertEquals(0, out.age());
      assertEquals(null, out.address().zip());
    }
  }

  @Nested
  @DisplayName("multi-hop intermediate — recursive default-tree handles arbitrary depth")
  class MultiHopAllocation {

    @Test
    @DisplayName("two-hop nested target (address.geocode.lat) from a flat source")
    void twoHopNestedTarget() {
      record Slim(double latitude) {}

      final var mapper = Telescope.mapper(
        Slim.class,
        DeepPerson.class,
        to(
          Slim::latitude,
          Telescope.of(DeepPerson.class).field(DeepPerson::address).field(DeepAddress::geocode).field(Geocode::lat)
        )
      );

      final var out = mapper.forward(new Slim(40.6782));
      assertNotNull(out.address(), "address should be allocated");
      assertNotNull(out.address().geocode(), "address.geocode should be allocated (recursive)");
      assertEquals(40.6782, out.address().geocode().lat());
      assertEquals(0.0, out.address().geocode().lon()); // primitive default
    }

    @Test
    @DisplayName("three-hop nested target (outer.mid.inner.value) — recursion depth is type-driven, not capped")
    void threeHopNestedTarget() {
      record Slim(String value) {}

      final var mapper = Telescope.mapper(
        Slim.class,
        DeepTarget.class,
        to(
          Slim::value,
          Telescope.of(DeepTarget.class)
            .field(DeepTarget::outer)
            .field(Outer::mid)
            .field(Mid::inner)
            .field(Inner::value)
        )
      );

      final var out = mapper.forward(new Slim("payload"));
      assertNotNull(out.outer());
      assertNotNull(out.outer().mid());
      assertNotNull(out.outer().mid().inner());
      assertEquals("payload", out.outer().mid().inner().value());
    }
  }
}
