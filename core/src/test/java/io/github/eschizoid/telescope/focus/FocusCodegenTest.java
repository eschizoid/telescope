package io.github.eschizoid.telescope.focus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.eschizoid.telescope.Either;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * End-to-end probe of the {@code @Focus} fluent path navigator. The processor runs against this
 * test source set (see {@code testAnnotationProcessor(project(":telescope-codegen"))} in the build)
 * and generates a sibling {@code <X>Path<R>} class for each annotated top-level record ({@link
 * FocusPerson} &rarr; {@code FocusPersonPath}, {@link FocusAddress} &rarr; {@code
 * FocusAddressPath}, …). The tests here use those navigators, proving both that the processor ran
 * and that the emitted code is correct.
 */
class FocusCodegenTest {

  @Nested
  @DisplayName("Generated *Path navigators")
  class Generated {

    @Test
    @DisplayName("FocusPersonPath.start().name() reads and updates the name field")
    void nameNavigation() {
      final var alice = new FocusPerson("alice", 30, new FocusAddress("nyc", "10001"));
      final var name = FocusPersonPath.start().name();

      assertEquals("alice", name.read(alice));

      final var renamed = name.update(alice, String::toUpperCase);
      assertEquals("ALICE", renamed.name());
      assertEquals(30, renamed.age());
      assertEquals(alice.address(), renamed.address());
    }

    @Test
    @DisplayName("FocusPersonPath.start().age() boxes int → Integer; round-trips correctly")
    void primitiveBoxing() {
      final var alice = new FocusPerson("alice", 30, new FocusAddress("nyc", "10001"));
      final var age = FocusPersonPath.start().age();

      assertEquals(30, age.read(alice));

      final var aged = age.update(alice, n -> n + 1);
      assertEquals(31, aged.age());
    }

    @Test
    @DisplayName("Deep paths compose through sub-record Path returns, compile-checked end to end")
    void deepFieldPath() {
      final var alice = new FocusPerson("alice", 30, new FocusAddress("nyc", "10001"));

      // address() returns FocusAddressPath<FocusPerson>; city() returns Telescope<FocusPerson,
      // String>
      final var city = FocusPersonPath.start().address().city();

      assertEquals("nyc", city.read(alice));

      final var moved = city.set(alice, "BOSTON");
      assertEquals("BOSTON", moved.address().city());
      assertEquals("10001", moved.address().zip());
    }

    @Test
    @DisplayName("Container hop: List<Record> traverses elements via .each() into the element's Path")
    void listTraversal() {
      final var team = new FocusTeam(
        "eng",
        List.of(
          new FocusPerson("alice", 30, new FocusAddress("nyc", "10001")),
          new FocusPerson("bob", 25, new FocusAddress("sf", "94016"))
        )
      );

      // members() returns FocusTeamMembersStep<FocusTeam>; .each() returns
      // FocusPersonPath<FocusTeam>;
      // .name() returns Telescope<FocusTeam, String>. Whole path is compile-checked,
      // reflection-free.
      final var memberNames = FocusTeamPath.start().members().each().name();

      assertEquals(List.of("alice", "bob"), memberNames.toList(team));

      final var shouted = memberNames.update(team, String::toUpperCase);
      assertEquals("ALICE", shouted.members().get(0).name());
      assertEquals("BOB", shouted.members().get(1).name());
      assertEquals("eng", shouted.name());
      // original untouched (immutable rebuild)
      assertEquals("alice", team.members().getFirst().name());
    }

    @Test
    @DisplayName("Container hops: Map values via .eachValue() (keys preserved) and Optional via .whenPresent()")
    void mapAndOptionalTraversal() {
      final var bag = new FocusBag(Map.of("a", "x", "b", "y"), Optional.of("hi"), List.of("p", "q"));

      // Map<String, String> values: eachValue() returns terminal Telescope<FocusBag, String>.
      final var labelValues = FocusBagPath.start().labels().eachValue();
      final var upperValues = labelValues.update(bag, String::toUpperCase);
      assertEquals(Map.of("a", "X", "b", "Y"), upperValues.labels());

      // Optional<String>: whenPresent() returns terminal Telescope<FocusBag, String>.
      final var noteValue = FocusBagPath.start().note().whenPresent();
      final var upperNote = noteValue.update(bag, String::toUpperCase);
      assertEquals(Optional.of("HI"), upperNote.note());

      // List<String>: each() returns terminal Telescope<FocusBag, String>.
      assertEquals(List.of("p", "q"), FocusBagPath.start().tags().each().toList(bag));
    }

    @Test
    @DisplayName(".get() returns the current Telescope at any hop (terminal use of a step)")
    void stepGetReturnsCurrentTelescope() {
      final var team = new FocusTeam("eng", List.of(new FocusPerson("alice", 30, new FocusAddress("nyc", "10001"))));

      // The members() step exposes the whole List as a Telescope<FocusTeam, List<FocusPerson>>.
      final var membersList = FocusTeamPath.start().members().get();
      assertEquals(1, membersList.read(team).size());
    }

    @Test
    @DisplayName("Bridge hop: as<Target>() chains @Bridge into the navigator, returning the target's Path")
    void bridgeHop() {
      final var entity = new FocusEntity("u1", "Alice@Example.com");

      // Direct read across the bridge: FocusEntity -> FocusDto.
      final FocusDto dto = FocusEntityPath.start().asFocusDto().read(entity);
      assertEquals("u1", dto.id());
      assertEquals("Alice@Example.com", dto.email());

      // Compose through the bridge into a target field and update — the Iso round-trips back to
      // FocusEntity, so we get an updated entity back.
      final var lowered = FocusEntityPath.start().asFocusDto().email().update(entity, String::toLowerCase);
      assertEquals("alice@example.com", lowered.email());
      assertEquals("u1", lowered.id());
    }

    @Test
    @DisplayName("Every Path and Step forwards the full Telescope op surface (incl. effects) at any hop")
    void forwardersAtIntermediateHops() throws Exception {
      final var alice = new FocusPerson("alice", 30, new FocusAddress("nyc", "10001"));

      // Sync ops directly on the root Path — no .get() unwrap.
      final var renamed = FocusPersonPath.start().update(alice, p -> new FocusPerson("ALICE", p.age(), p.address()));
      assertEquals("ALICE", renamed.name());
      assertEquals("alice", FocusPersonPath.start().read(alice).name());

      // Effect at an intermediate sub-record Path hop: updateAsync on
      // FocusAddressPath<FocusPerson>.
      final var movedFuture = FocusPersonPath.start()
        .address()
        .updateAsync(alice, addr ->
          CompletableFuture.completedFuture(new FocusAddress(addr.city().toUpperCase(), addr.zip()))
        );
      assertEquals("NYC", movedFuture.get().address().city());

      // updateEither at an intermediate Step → Path chain.
      final var team = new FocusTeam(
        "eng",
        List.of(
          new FocusPerson("alice", 30, new FocusAddress("nyc", "10001")),
          new FocusPerson("bob", 25, new FocusAddress("sf", "94016"))
        )
      );
      final Either<String, FocusTeam> ok = FocusTeamPath.start()
        .members()
        .each()
        .updateEither(team, p -> Either.right(new FocusPerson(p.name().toUpperCase(), p.age(), p.address())));
      assertEquals(
        "ALICE",
        ok.fold(
          err -> {
            throw new AssertionError(err);
          },
          t -> t.members().getFirst().name()
        )
      );

      // updateIndexed forwarded from a Step → Path chain.
      final var bumped = FocusTeamPath.start()
        .members()
        .each()
        .updateIndexed(team, (i, p) -> new FocusPerson(p.name() + ":" + i, p.age(), p.address()));
      assertEquals("alice:0", bumped.members().get(0).name());
      assertEquals("bob:1", bumped.members().get(1).name());
    }
  }
}
