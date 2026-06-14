package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope.ListTelescope;
import io.github.eschizoid.telescope.Telescope.MapTelescope;
import io.github.eschizoid.telescope.Telescope.OptionalTelescope;
import io.github.eschizoid.telescope.Telescope.SetTelescope;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the typed container subclasses ({@link ListTelescope} / {@link SetTelescope} / {@link
 * MapTelescope} / {@link OptionalTelescope}) introduced when the runtime-dispatched {@code each()}
 * no-arg form was deleted. Each subclass's typed terminal ({@code each() / values() / present()})
 * descends into elements via pure lattice composition — no runtime container dispatch, no
 * reflection beyond the normal record/bean introspection used for the lens.
 */
class TypedContainerPathTest {

  record Tag(String name) {}

  record TagList(String owner, List<Tag> tags) {}

  record TagSet(String owner, Set<Tag> tags) {}

  record TagOptional(String owner, Optional<Tag> tag) {}

  record TagMap(String owner, Map<String, Tag> tagsByKey) {}

  @Nested
  @DisplayName("Typed `.list(getter)` returns ListTelescope with compile-checked .each()")
  class ListContainer {

    @Test
    @DisplayName("List leaf type is preserved as ListTelescope; .each() steps into elements via lattice")
    void typedListTelescope() {
      final ListTelescope<TagList, Tag> tags = Telescope.of(TagList.class).list(TagList::tags);
      final var src = new TagList("alice", List.of(new Tag("a"), new Tag("b"), new Tag("c")));

      assertEquals(List.of(new Tag("a"), new Tag("b"), new Tag("c")), tags.read(src));

      // Step into elements via the typed terminal — compile-checked, no runtime dispatch.
      final Telescope<TagList, Tag> elements = tags.each();
      final var upper = elements.update(src, t -> new Tag(t.name().toUpperCase()));
      assertEquals(List.of(new Tag("A"), new Tag("B"), new Tag("C")), upper.tags());
    }

    @Test
    @DisplayName("static Telescope.asList promotes a pre-built Telescope<S, List<X>> to ListTelescope")
    void asListPromotion() {
      final Telescope<TagList, List<Tag>> raw = Telescope.of(TagList.class).field(TagList::tags);
      final ListTelescope<TagList, Tag> promoted = Telescope.asList(raw);
      final var elements = promoted.each();

      final var src = new TagList("alice", List.of(new Tag("a")));
      assertEquals(List.of(new Tag("a")), elements.toList(src));
    }

    @Test
    @DisplayName("asList on an already-ListTelescope rewraps to an equivalent ListTelescope (behavior-preserving)")
    void asListBehaviorPreserving() {
      final ListTelescope<TagList, Tag> tags = Telescope.of(TagList.class).list(TagList::tags);
      final ListTelescope<TagList, Tag> promoted = Telescope.asList(tags);
      final var src = new TagList("alice", List.of(new Tag("a"), new Tag("b")));
      assertEquals(tags.read(src), promoted.read(src), "rewrapped ListTelescope must read identical values");
      final var updated = promoted.each().update(src, t -> new Tag(t.name().toUpperCase()));
      assertEquals(
        List.of(new Tag("A"), new Tag("B")),
        updated.tags(),
        "rewrapped ListTelescope must update identically"
      );
    }
  }

  @Nested
  @DisplayName("Typed `.setField(getter)` returns SetTelescope with compile-checked .each()")
  class SetContainer {

    @Test
    @DisplayName("Set leaf type is preserved as SetTelescope; .each() steps into elements via lattice")
    void typedSetTelescope() {
      final SetTelescope<TagSet, Tag> tags = Telescope.of(TagSet.class).setField(TagSet::tags);
      final var src = new TagSet("alice", new LinkedHashSet<>(List.of(new Tag("a"), new Tag("b"))));

      final var upper = tags.each().update(src, t -> new Tag(t.name().toUpperCase()));
      assertEquals(Set.of(new Tag("A"), new Tag("B")), upper.tags());
    }

    @Test
    @DisplayName("static Telescope.asSet promotes a pre-built Telescope<S, Set<X>> to SetTelescope")
    void asSetPromotion() {
      final Telescope<TagSet, Set<Tag>> raw = Telescope.of(TagSet.class).field(TagSet::tags);
      final SetTelescope<TagSet, Tag> promoted = Telescope.asSet(raw);
      final var src = new TagSet("alice", new LinkedHashSet<>(List.of(new Tag("a"))));
      assertEquals(Set.of(new Tag("a")), promoted.each().toList(src).stream().collect(Collectors.toSet()));
    }
  }

  @Nested
  @DisplayName("Typed `.mapField(getter)` returns MapTelescope with compile-checked .values()")
  class MapContainer {

    @Test
    @DisplayName("Map leaf is preserved as MapTelescope; .values() steps into values, keys retained")
    void typedMapTelescope() {
      final MapTelescope<TagMap, String, Tag> tagsByKey = Telescope.of(TagMap.class).mapField(TagMap::tagsByKey);
      final var src = new TagMap("alice", Map.of("a", new Tag("x"), "b", new Tag("y")));

      final var upper = tagsByKey.values().update(src, t -> new Tag(t.name().toUpperCase()));
      assertEquals(new Tag("X"), upper.tagsByKey().get("a"));
      assertEquals(new Tag("Y"), upper.tagsByKey().get("b"));
    }

    @Test
    @DisplayName("static Telescope.asMap promotes a pre-built Telescope<S, Map<K,V>> to MapTelescope")
    void asMapPromotion() {
      final Telescope<TagMap, Map<String, Tag>> raw = Telescope.of(TagMap.class).field(TagMap::tagsByKey);
      final MapTelescope<TagMap, String, Tag> promoted = Telescope.asMap(raw);
      final var src = new TagMap("alice", Map.of("a", new Tag("x")));
      assertEquals(List.of(new Tag("x")), promoted.values().toList(src));
    }
  }

  @Nested
  @DisplayName("Typed `.optional(getter)` returns OptionalTelescope with compile-checked .present()")
  class OptionalContainer {

    @Test
    @DisplayName(
      "Optional leaf preserved as OptionalTelescope; .present() Affine-updates when present, no-op when empty"
    )
    void typedOptionalTelescope() {
      final OptionalTelescope<TagOptional, Tag> tag = Telescope.of(TagOptional.class).optional(TagOptional::tag);

      // Present: update flows through.
      final var with = new TagOptional("alice", Optional.of(new Tag("a")));
      final var upper = tag.present().update(with, t -> new Tag(t.name().toUpperCase()));
      assertEquals(Optional.of(new Tag("A")), upper.tag());

      // Empty: update is a no-op (Affine semantics inherited from Traversals.eachOptional).
      final var without = new TagOptional("bob", Optional.empty());
      final var unchanged = tag.present().update(without, t -> new Tag(t.name().toUpperCase()));
      assertEquals(Optional.empty(), unchanged.tag());
    }

    @Test
    @DisplayName("static Telescope.asOptional promotes a pre-built Telescope<S, Optional<X>>")
    void asOptionalPromotion() {
      final Telescope<TagOptional, Optional<Tag>> raw = Telescope.of(TagOptional.class).field(TagOptional::tag);
      final OptionalTelescope<TagOptional, Tag> promoted = Telescope.asOptional(raw);
      final var src = new TagOptional("alice", Optional.of(new Tag("x")));
      assertEquals(List.of(new Tag("x")), promoted.present().toList(src));
    }
  }

  @Nested
  @DisplayName("Ergonomics regression — existing chained call sites compile and produce the same results")
  class ExistingErgonomics {

    record Address(String city, String zip) {}

    record User(String name, String email, Address address) {}

    record Team(String name, List<User> users) {}

    record Department(String name, List<Team> teams, Optional<User> head) {}

    record Company(String name, List<Department> departments, Map<String, Address> offices) {}

    @Test
    @DisplayName("Company example — chained typed .each(Accessor) form unchanged from before")
    void canonicalCompanyExample() {
      // The canonical company example from CLAUDE.md / README — uses the existing instance
      // .each(Accessor) form which steps into iterables. Verifies nothing about ergonomics changed.
      final var company = new Company(
        "Acme",
        List.of(
          new Department(
            "Eng",
            List.of(new Team("Platform", List.of(new User("alice", "Alice@AcMe.Com", new Address("NYC", "10001"))))),
            Optional.of(new User("eve", "EVE@acme.com", new Address("SF", "94016")))
          )
        ),
        Map.of("US", new Address("NYC", "10001"))
      );

      final var lowered = Telescope.of(Company.class)
        .each(Company::departments)
        .each(Department::teams)
        .each(Team::users)
        .field(User::email)
        .update(company, String::toLowerCase);

      assertEquals("alice@acme.com", lowered.departments().get(0).teams().get(0).users().get(0).email());
    }

    @Test
    @DisplayName("Optional<User> path — existing .whenPresent(Accessor) form unchanged")
    void optionalForm() {
      final var company = new Company(
        "Acme",
        List.of(
          new Department("Eng", List.of(), Optional.of(new User("Eve", "EVE@acme.com", new Address("SF", "94016"))))
        ),
        Map.of()
      );
      final var lowered = Telescope.of(Company.class)
        .each(Company::departments)
        .whenPresent(Department::head)
        .field(User::email)
        .update(company, String::toLowerCase);

      assertEquals("eve@acme.com", lowered.departments().get(0).head().orElseThrow().email());
    }

    @Test
    @DisplayName("Map<String, Address> path — existing .eachValue(Accessor) form unchanged")
    void mapValuesForm() {
      final var company = new Company(
        "Acme",
        List.of(),
        Map.of("US", new Address("nyc", "10001"), "EU", new Address("lon", "EC1"))
      );
      final var upper = Telescope.of(Company.class)
        .eachValue(Company::offices)
        .field(Address::city)
        .update(company, String::toUpperCase);

      assertEquals("NYC", upper.offices().get("US").city());
      assertEquals("LON", upper.offices().get("EU").city());
    }
  }

  @Nested
  @DisplayName("Lattice-routed DeepMap.assembleIso — structural Iso composition")
  class LatticeRoutedAssembly {

    record SrcRec(String name, int age) {}

    record TgtRec(String name, int age) {}

    @Test
    @DisplayName("same-shape record mapping round-trips via structuralIso composition")
    void sameShapeRoundTrip() {
      final var mapper = Telescope.mapper(SrcRec.class, TgtRec.class);
      final var src = new SrcRec("alice", 30);
      final var tgt = mapper.read(src);
      assertEquals(new TgtRec("alice", 30), tgt);
      assertEquals(src, mapper.backward(tgt));
    }

    @Test
    @DisplayName("null source passes through to null (preserved by the assembleIso null-wrap)")
    void nullPassThrough() {
      final var mapper = Telescope.mapper(SrcRec.class, TgtRec.class);
      assertTrue(mapper.read(null) == null);
      assertTrue(mapper.backward(null) == null);
    }
  }
}
