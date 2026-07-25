package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.drop;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins the null-consistency contract across container navigation and mapper reconstruction: a null
 * container or null {@code Optional} field focuses nothing — the same graceful skip on every
 * navigation surface — and a dropped primitive source field reconstructs to its JLS default on the
 * way back instead of exploding on unboxing.
 */
class NullConsistencyTest {

  record Holder(List<String> items, Set<String> tags, Map<String, Integer> scores, Optional<String> nick) {}

  static final Holder ALL_NULL = new Holder(null, null, null, null);

  @Nested
  @DisplayName("null containers focus nothing on every navigation surface")
  class NullContainers {

    @Test
    @DisplayName("each(Accessor) over a null List — skipped (the long-standing contract)")
    void eachAccessorNullList() {
      final var items = Telescope.of(Holder.class).each(Holder::items);
      assertEquals(List.of(), items.toList(ALL_NULL));
      assertFalse(items.exists(ALL_NULL));
      assertEquals(ALL_NULL, items.update(ALL_NULL, String::toUpperCase));
    }

    @Test
    @DisplayName("typed .list(...).each() over the same null List behaves identically")
    void typedListStepNullList() {
      final var items = Telescope.of(Holder.class).list(Holder::items).each();
      assertEquals(List.of(), items.toList(ALL_NULL));
      assertFalse(items.exists(ALL_NULL));
      assertEquals(ALL_NULL, items.update(ALL_NULL, String::toUpperCase));
    }

    @Test
    @DisplayName("typed .setField(...).each() over a null Set — skipped")
    void typedSetStepNullSet() {
      final var tags = Telescope.of(Holder.class).setField(Holder::tags).each();
      assertEquals(List.of(), tags.toList(ALL_NULL));
      assertEquals(ALL_NULL, tags.update(ALL_NULL, String::toUpperCase));
    }

    @Test
    @DisplayName("typed .mapField(...).values() over a null Map — skipped")
    void typedMapStepNullMap() {
      final var scores = Telescope.of(Holder.class).mapField(Holder::scores).values();
      assertEquals(List.of(), scores.toList(ALL_NULL));
      assertEquals(ALL_NULL, scores.update(ALL_NULL, v -> v + 1));
    }

    @Test
    @DisplayName("eachValue(Accessor) over a null Map — skipped")
    void eachValueNullMap() {
      final var scores = Telescope.of(Holder.class).eachValue(Holder::scores);
      assertEquals(List.of(), scores.toList(ALL_NULL));
      assertEquals(ALL_NULL, scores.update(ALL_NULL, v -> v + 1));
    }
  }

  @Nested
  @DisplayName("a null Optional field (not empty — null) focuses nothing")
  class NullOptionalField {

    @Test
    @DisplayName("whenPresent over a null Optional skips like an empty one")
    void whenPresentNullOptional() {
      final var nick = Telescope.of(Holder.class).whenPresent(Holder::nick);
      assertEquals(Optional.empty(), nick.find(ALL_NULL));
      assertFalse(nick.exists(ALL_NULL));
      assertEquals(ALL_NULL, nick.update(ALL_NULL, String::toUpperCase));
    }

    @Test
    @DisplayName("typed .optional(...).present() over a null Optional skips like an empty one")
    void typedOptionalStepNull() {
      final var nick = Telescope.of(Holder.class).optional(Holder::nick).present();
      assertEquals(Optional.empty(), nick.find(ALL_NULL));
      assertEquals(ALL_NULL, nick.update(ALL_NULL, String::toUpperCase));
    }
  }

  @Nested
  @DisplayName("dropped primitive source fields reconstruct to JLS defaults backward")
  class DropPrimitiveBackward {

    record SrcWithInt(String name, int retries) {}

    record TgtNameOnly(String name) {}

    @Test
    @DisplayName("drop(Src::primitiveField) + backward() yields the JLS default, not an unboxing NPE")
    void dropPrimitiveBackwardYieldsDefault() {
      final var mapper = Telescope.mapper(SrcWithInt.class, TgtNameOnly.class, drop(SrcWithInt::retries));

      final var tgt = mapper.forward(new SrcWithInt("ann", 7));
      assertEquals(new TgtNameOnly("ann"), tgt);

      final var back = mapper.backward(tgt);
      assertEquals("ann", back.name());
      assertEquals(0, back.retries()); // JLS default — the dropped slot cannot round-trip
    }

    @Test
    @DisplayName("drop(Src::referenceField) + backward() still null-fills (unchanged contract)")
    void dropReferenceBackwardNullFills() {
      record SrcWithRef(String name, String note) {}
      final var mapper = Telescope.mapper(SrcWithRef.class, TgtNameOnly.class, drop(SrcWithRef::note));
      final var back = mapper.backward(new TgtNameOnly("bo"));
      assertEquals("bo", back.name());
      assertNull(back.note());
    }
  }
}
