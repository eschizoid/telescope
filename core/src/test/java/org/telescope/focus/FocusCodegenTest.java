package org.telescope.focus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * End-to-end probe of the {@code @Focus} annotation processor. The processor runs against this test
 * source set (see {@code testAnnotationProcessor(project(":telescope-codegen"))} in the build) and
 * generates a sibling {@code *Focus} class for each annotated top-level record ({@link FocusPerson}
 * → {@code FocusPersonFocus}, {@link FocusAddress} → {@code FocusAddressFocus}). The tests here use
 * those generated constants, proving both that the processor ran and that the emitted code is
 * correct.
 */
class FocusCodegenTest {

  @Nested
  @DisplayName("Generated *Focus classes")
  class Generated {

    @Test
    @DisplayName("FocusPersonFocus.name reads and writes the name field")
    void nameLens() {
      final var alice = new FocusPerson("alice", 30, new FocusAddress("nyc", "10001"));

      assertEquals("alice", FocusPersonFocus.name.read(alice));

      final var renamed = FocusPersonFocus.name.update(alice, String::toUpperCase);
      assertEquals("ALICE", renamed.name());
      assertEquals(30, renamed.age());
      assertEquals(alice.address(), renamed.address());
    }

    @Test
    @DisplayName("FocusPersonFocus.age boxes int → Integer; round-trips correctly")
    void primitiveBoxing() {
      final var alice = new FocusPerson("alice", 30, new FocusAddress("nyc", "10001"));

      assertEquals(30, FocusPersonFocus.age.read(alice));

      final var aged = FocusPersonFocus.age.update(alice, n -> n + 1);
      assertEquals(31, aged.age());
    }

    @Test
    @DisplayName("Generated lenses compose with the rest of the DSL")
    void composesWithDsl() {
      final var alice = new FocusPerson("alice", 30, new FocusAddress("nyc", "10001"));

      // Nest: FocusPersonFocus.address composed with FocusAddressFocus.city
      final var city = FocusPersonFocus.address.then(FocusAddressFocus.city);

      assertEquals("nyc", city.read(alice));

      final var moved = city.set(alice, "BOSTON");
      assertEquals("BOSTON", moved.address().city());
      assertEquals("10001", moved.address().zip());
    }

    @Test
    @DisplayName("Generated constants are singletons (same instance returned)")
    void singleton() {
      assertSame(FocusPersonFocus.name, FocusPersonFocus.name);
    }

    @Test
    @DisplayName("Generated each<Component> traversal descends into a List, reflection-free, and composes")
    void traversalConstant() {
      final var team = new FocusTeam(
        "eng",
        java.util.List.of(
          new FocusPerson("alice", 30, new FocusAddress("nyc", "10001")),
          new FocusPerson("bob", 25, new FocusAddress("sf", "94016"))
        )
      );

      // eachMembers : Telescope<FocusTeam, FocusPerson> — composed with a member field lens. The
      // element type is generator-proven, so this whole path is compile-checked and
      // reflection-free.
      final var memberNames = FocusTeamFocus.eachMembers.then(FocusPersonFocus.name);

      assertEquals(java.util.List.of("alice", "bob"), memberNames.toList(team));

      final var shouted = memberNames.update(team, String::toUpperCase);
      assertEquals("ALICE", shouted.members().get(0).name());
      assertEquals("BOB", shouted.members().get(1).name());
      assertEquals("eng", shouted.name());
      // original untouched (immutable rebuild)
      assertEquals("alice", team.members().get(0).name());
    }
  }
}
