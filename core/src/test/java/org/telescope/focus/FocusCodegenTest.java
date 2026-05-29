package org.telescope.focus;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        java.util.List.of(
          new FocusPerson("alice", 30, new FocusAddress("nyc", "10001")),
          new FocusPerson("bob", 25, new FocusAddress("sf", "94016"))
        )
      );

      // members() returns FocusTeamMembersStep<FocusTeam>; .each() returns
      // FocusPersonPath<FocusTeam>;
      // .name() returns Telescope<FocusTeam, String>. Whole path is compile-checked,
      // reflection-free.
      final var memberNames = FocusTeamPath.start().members().each().name();

      assertEquals(java.util.List.of("alice", "bob"), memberNames.toList(team));

      final var shouted = memberNames.update(team, String::toUpperCase);
      assertEquals("ALICE", shouted.members().get(0).name());
      assertEquals("BOB", shouted.members().get(1).name());
      assertEquals("eng", shouted.name());
      // original untouched (immutable rebuild)
      assertEquals("alice", team.members().get(0).name());
    }

    @Test
    @DisplayName("Container hops: Map values via .eachValue() (keys preserved) and Optional via .whenPresent()")
    void mapAndOptionalTraversal() {
      final var bag = new FocusBag(
        java.util.Map.of("a", "x", "b", "y"),
        java.util.Optional.of("hi"),
        java.util.List.of("p", "q")
      );

      // Map<String, String> values: eachValue() returns terminal Telescope<FocusBag, String>.
      final var labelValues = FocusBagPath.start().labels().eachValue();
      final var upperValues = labelValues.update(bag, String::toUpperCase);
      assertEquals(java.util.Map.of("a", "X", "b", "Y"), upperValues.labels());

      // Optional<String>: whenPresent() returns terminal Telescope<FocusBag, String>.
      final var noteValue = FocusBagPath.start().note().whenPresent();
      final var upperNote = noteValue.update(bag, String::toUpperCase);
      assertEquals(java.util.Optional.of("HI"), upperNote.note());

      // List<String>: each() returns terminal Telescope<FocusBag, String>.
      assertEquals(java.util.List.of("p", "q"), FocusBagPath.start().tags().each().toList(bag));
    }

    @Test
    @DisplayName(".get() returns the current Telescope at any hop (terminal use of a step)")
    void stepGetReturnsCurrentTelescope() {
      final var team = new FocusTeam(
        "eng",
        java.util.List.of(new FocusPerson("alice", 30, new FocusAddress("nyc", "10001")))
      );

      // The members() step exposes the whole List as a Telescope<FocusTeam, List<FocusPerson>>.
      final var membersList = FocusTeamPath.start().members().get();
      assertEquals(1, membersList.read(team).size());
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
          java.util.concurrent.CompletableFuture.completedFuture(
            new FocusAddress(addr.city().toUpperCase(), addr.zip())
          )
        );
      assertEquals("NYC", movedFuture.get().address().city());

      // updateEither at an intermediate Step → Path chain.
      final var team = new FocusTeam(
        "eng",
        java.util.List.of(
          new FocusPerson("alice", 30, new FocusAddress("nyc", "10001")),
          new FocusPerson("bob", 25, new FocusAddress("sf", "94016"))
        )
      );
      final org.telescope.Either<String, FocusTeam> ok = FocusTeamPath.start()
        .members()
        .each()
        .updateEither(team, p ->
          org.telescope.Either.right(new FocusPerson(p.name().toUpperCase(), p.age(), p.address()))
        );
      assertEquals(
        "ALICE",
        ok.fold(
          err -> {
            throw new AssertionError(err);
          },
          t -> t.members().get(0).name()
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
