package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.via;
import static io.github.eschizoid.telescope.mapping.Mapping.zip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins five {@link DeepMap} branches that the existing test suite did not cover. Round-2 review
 * identified each as load-bearing but unexercised — if a refactor accidentally removed any of these
 * guards or lift permutations, the failure mode would be a silent wrong result rather than a clear
 * exception. The tests here exist to make the guards regression-proof.
 *
 * <p>This is a coverage-pinning file, not an exploratory one. Each test targets one specific
 * line range identified in the audit.
 */
class DeepMapCoverageTest {

  @Nested
  @DisplayName("D1 — zip(...) backward cardinality mismatch")
  class ZipBackwardCardinality {

    record Cart(java.util.List<String> items) {}

    record CartDto(java.util.List<String> lines) {}

    @Test
    @DisplayName("zip backward throws naming both counts when target focuses != source focuses")
    void backwardCardinalityMismatch() {
      final var mapper = Telescope.mapper(
        Cart.class,
        CartDto.class,
        zip(
          Telescope.of(Cart.class).each(Cart::items),
          Telescope.of(CartDto.class).each(CartDto::lines)
        )
      );
      // Backward direction: target dto with 3 lines decoded against a source rebuilt with N
      // items — the zip-backward path enforces cardinality (line 609 in DeepMap.java) since the
      // positional copy can't reconcile unequal lengths. Direct mapper.backward(dto) drives
      // applyBackward, which is where the guard lives.
      final var dto = new CartDto(java.util.List.of("x", "y", "z"));
      final var ex = assertThrows(IllegalStateException.class, () -> mapper.backward(dto));
      assertTrue(ex.getMessage().toLowerCase().contains("cardinality"));
    }
  }

  @Nested
  @DisplayName("D2 — autoIso Map key-type mismatch")
  class AutoIsoMapKeyMismatch {

    record SrcMap(Map<String, String> data) {}

    record TgtMap(Map<Long, String> data) {}

    @Test
    @DisplayName("Map<String,X> ↔ Map<Long,X> rejected at build with key-type names in the diagnostic")
    void mapKeyMismatchRejected() {
      final var ex = assertThrows(IllegalStateException.class, () -> Telescope.mapper(SrcMap.class, TgtMap.class));
      assertTrue(ex.getMessage().toLowerCase().contains("key types"));
      assertTrue(ex.getMessage().contains("String"));
      assertTrue(ex.getMessage().contains("Long"));
    }
  }

  @Nested
  @DisplayName("D3 — via(...) lifting through Set and Optional containers")
  class ViaLifting {

    record TagE(String name) {}

    record TagD(String name) {}

    record SetHolder(Set<TagE> tags) {}

    record SetHolderDto(Set<TagD> tags) {}

    record OptHolder(Optional<TagE> tag) {}

    record OptHolderDto(Optional<TagD> tag) {}

    @Test
    @DisplayName("via auto-lifts the element mapper through Set<X> ↔ Set<Y>")
    void liftsThroughSet() {
      final var elementMapper = Telescope.mapper(TagE.class, TagD.class);
      final var mapper = Telescope.mapper(
        SetHolder.class,
        SetHolderDto.class,
        via(SetHolder::tags, SetHolderDto::tags, elementMapper)
      );
      final var dto = mapper.forward(new SetHolder(Set.of(new TagE("a"), new TagE("b"))));
      assertEquals(Set.of(new TagD("a"), new TagD("b")), dto.tags());
    }

    @Test
    @DisplayName("via auto-lifts the element mapper through Optional<X> ↔ Optional<Y>")
    void liftsThroughOptional() {
      final var elementMapper = Telescope.mapper(TagE.class, TagD.class);
      final var mapper = Telescope.mapper(
        OptHolder.class,
        OptHolderDto.class,
        via(OptHolder::tag, OptHolderDto::tag, elementMapper)
      );
      final var dto = mapper.forward(new OptHolder(Optional.of(new TagE("present"))));
      assertEquals(Optional.of(new TagD("present")), dto.tag());

      final var empty = mapper.forward(new OptHolder(Optional.empty()));
      assertEquals(Optional.empty(), empty.tag());
    }
  }

  @Nested
  @DisplayName("D4 — via(...) Map key-type mismatch")
  class ViaMapKeyMismatch {

    record UserEntity(String id) {}

    record UserDto(String id) {}

    record SrcMapHolder(Map<String, UserEntity> byId) {}

    record TgtMapHolder(Map<Long, UserDto> byId) {}

    @Test
    @DisplayName("via through Map<K1, X> ↔ Map<K2, Y> rejected at build with key-type names")
    void viaMapKeyMismatchRejected() {
      final var elementMapper = Telescope.mapper(UserEntity.class, UserDto.class);
      final var ex = assertThrows(IllegalStateException.class, () ->
        Telescope.mapper(SrcMapHolder.class, TgtMapHolder.class, via(SrcMapHolder::byId, TgtMapHolder::byId, elementMapper))
      );
      assertTrue(ex.getMessage().toLowerCase().contains("key types"));
      assertTrue(ex.getMessage().contains("String"));
      assertTrue(ex.getMessage().contains("Long"));
    }
  }

  @Nested
  @DisplayName("D5 — primitiveDefault for long/byte/short/char/float")
  class PrimitiveDefaults {

    // Nested record with all primitive types — when DeepMap intermediate-allocates this record
    // because the target's outer record references it via a Telescope row, recursiveDefault must
    // fill every primitive slot with the right zero. That walks every branch of primitiveDefault.
    record AllPrims(long l, byte b, short s, char c, float f, String tag) {}

    record Outer(AllPrims prims) {}

    record Slim(String tag) {}

    @Test
    @DisplayName("recursiveDefault fills long/byte/short/char/float zeros when intermediate AllPrims is allocated")
    void allPrimitiveDefaultsFire() {
      final var mapper = Telescope.mapper(
        Slim.class,
        Outer.class,
        io.github.eschizoid.telescope.mapping.Mapping.to(
          Slim::tag,
          Telescope.of(Outer.class).field(Outer::prims).field(AllPrims::tag)
        )
      );

      // Slim has no fields matching long/byte/short/char/float; the AllPrims intermediate is
      // allocated by recursiveDefault → primitiveDefault for each primitive slot.
      final var out = mapper.forward(new Slim("hello"));
      assertEquals("hello", out.prims().tag());
      assertEquals(0L, out.prims().l());
      assertEquals((byte) 0, out.prims().b());
      assertEquals((short) 0, out.prims().s());
      assertEquals((char) 0, out.prims().c());
      assertEquals(0.0f, out.prims().f());
    }
  }
}
