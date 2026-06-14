package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.via;
import static io.github.eschizoid.telescope.mapping.Mapping.zip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * <p>This is a coverage-pinning file, not an exploratory one. Each test targets one specific line
 * range identified in the audit.
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
        zip(Telescope.of(Cart.class).each(Cart::items), Telescope.of(CartDto.class).each(CartDto::lines))
      );
      // Backward direction: target dto with 3 lines decoded against a source rebuilt with N
      // items — the zip-backward path enforces cardinality (line 609 in DeepMap.java) since the
      // positional copy can't reconcile unequal lengths. Direct mapper.backward(dto) drives
      // applyBackward, which is where the guard lives.
      final var dto = new CartDto(java.util.List.of("x", "y", "z"));
      final var ex = assertThrows(IllegalStateException.class, () -> mapper.backward(dto));
      final var msg = ex.getMessage();
      assertTrue(msg.toLowerCase().contains("cardinality"));
      // D1-tighten²: word-bounded regex so the "0" assertion can't accidentally match incidental
      // characters in a future diagnostic. Both counts must appear as standalone tokens, in some
      // order — the relationship between them is the load-bearing semantic, not the order.
      assertTrue(
        msg.matches("(?s).*\\b3\\b.*\\b0\\b.*") || msg.matches("(?s).*\\b0\\b.*\\b3\\b.*"),
        () -> "cardinality message must mention both 3 and 0 as word-bounded tokens, was: " + msg
      );
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
        Telescope.mapper(
          SrcMapHolder.class,
          TgtMapHolder.class,
          via(SrcMapHolder::byId, TgtMapHolder::byId, elementMapper)
        )
      );
      assertTrue(ex.getMessage().toLowerCase().contains("key types"));
      assertTrue(ex.getMessage().contains("String"));
      assertTrue(ex.getMessage().contains("Long"));
    }
  }

  // NOTE — FU-1 (placeholderIsoFor fieldType == null) targets a path reachable only via raw
  // / wildcard component types that the strict-mapper validation blocks at construction time.
  // Documented here so a future reviewer doesn't re-test it.

  @Nested
  @DisplayName("FU-2 — recursiveDefault POJO bean-arm via TelescopeTo through a bean intermediate")
  class RecursiveDefaultBeanArm {

    // A POJO bean intermediate with a public no-arg ctor and writable property. recursiveDefault
    // routes through this when a TelescopeTo row claims a nested field whose ancestor type is a
    // bean with no same-name source counterpart — exercising the bean arm at DeepMap.java:1099+
    // (the no-arg-ctor instantiation + property writes) that wasn't covered by the round-2 FU-2
    // claim. Round-3 review correctly identified this as reachable.
    public static class BeanInner {

      private String value;

      public BeanInner() {}

      public String getValue() {
        return value;
      }

      public void setValue(final String value) {
        this.value = value;
      }
    }

    public static class BeanOuter {

      private BeanInner inner;

      public BeanOuter() {}

      public BeanInner getInner() {
        return inner;
      }

      public void setInner(final BeanInner inner) {
        this.inner = inner;
      }
    }

    record Slim(String value) {}

    @Test
    @DisplayName("Bean intermediate allocated via no-arg ctor when TelescopeTo claims a nested field")
    void beanIntermediateAllocated() {
      // Slim → BeanOuter via a telescope path through Outer::inner → Inner::value. BeanInner has
      // no same-name source field for "inner"; recursiveDefault must allocate it via no-arg ctor
      // so the telescope set() can write `value` onto it.
      final var mapper = Telescope.mapper(
        Slim.class,
        BeanOuter.class,
        io.github.eschizoid.telescope.mapping.Mapping.to(
          Slim::value,
          Telescope.ofBean(BeanOuter.class).field(BeanOuter::getInner).field(BeanInner::getValue)
        )
      );

      final var out = mapper.forward(new Slim("hello"));
      assertNotNull(out.getInner(), "BeanInner must be allocated via no-arg ctor, not null");
      assertEquals("hello", out.getInner().getValue());
    }
  }

  @Nested
  @DisplayName("FU-3 — autoIso cross-paradigm Optional ↔ nullable bridge, both directions")
  class OptionalToNullable {

    record HasOpt(Optional<String> tag) {}

    record HasNullable(String tag) {}

    @Test
    @DisplayName("Optional<X> ↔ X auto-lifts both directions of liftOptionalToNullable")
    void optionalToNullableRoundTrip() {
      final var mapper = Telescope.mapper(HasOpt.class, HasNullable.class);

      // Optional.of("x") forward -> "x"
      assertEquals("x", mapper.forward(new HasOpt(Optional.of("x"))).tag());
      // Optional.empty() forward -> null
      assertNull(mapper.forward(new HasOpt(Optional.empty())).tag());
      // "x" backward -> Optional.of("x")
      assertEquals(Optional.of("x"), mapper.backward(new HasNullable("x")).tag());
      // null backward -> Optional.empty()
      assertEquals(Optional.empty(), mapper.backward(new HasNullable(null)).tag());
    }

    @Test
    @DisplayName("X ↔ Optional<Y> — the reversed direction also routes through liftOptionalToNullable.reverse()")
    void nullableToOptionalReversedDirection() {
      // Inverse pair — exercises the second branch in autoIso (line 681 in DeepMap.java).
      final var mapper = Telescope.mapper(HasNullable.class, HasOpt.class);
      assertEquals(Optional.of("y"), mapper.forward(new HasNullable("y")).tag());
      assertEquals(Optional.empty(), mapper.forward(new HasNullable(null)).tag());
      assertEquals("y", mapper.backward(new HasOpt(Optional.of("y"))).tag());
      assertNull(mapper.backward(new HasOpt(Optional.empty())).tag());
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

    // The "direct" placeholderIsoFor primitive branch (DeepMap.java:1151) is reachable only
    // through telescope-row-claimed intermediate slots; strict mapper validation rejects the
    // top-level "source has no field for target primitive" fixture the round-2 review sketched.
    // The recursive path above exercises the same primitiveDefault implementation, so the test
    // suite's behavioural coverage is complete even though the line-level call site count is one.
  }
}
