package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UnpairedTest {

  private static final Unpaired.Sides SIDES = new Unpaired.Sides("Src", "Tgt", "field", "field");

  // No property of either side lacks a member that writes it.
  private static final Predicate<String> NONE = name -> false;

  private static PairingRules.MatchResult match(
    final List<String> unmatchedTargets,
    final List<String> unmatchedSources
  ) {
    return new PairingRules.MatchResult(List.of("a"), unmatchedTargets, unmatchedSources);
  }

  @Test
  @DisplayName("a target nothing fills is refused, then a source nothing reads, each in the order the match gives")
  void refusesTargetsThenSources() {
    final var refusals = Unpaired.refusals(
      SIDES,
      List.of("a", "y"),
      match(List.of("b", "c"), List.of("y")),
      Set.of(),
      Set.of(),
      NONE,
      NONE
    );
    assertEquals(
      List.of(
        PairingMessages.noSameNameSource("Src", "Tgt", "field", "field", "b"),
        PairingMessages.noSameNameSource("Src", "Tgt", "field", "field", "c"),
        PairingMessages.noSameNameTarget("Src", "Tgt", "field", "field", "y")
      ),
      refusals
    );
  }

  @Test
  @DisplayName("a target a path, constant or compute row fills and a source a path row reads are not refused")
  void filledAndReadPropertiesPass() {
    final var refusals = Unpaired.refusals(
      SIDES,
      List.of("a", "y"),
      match(List.of("b"), List.of("y")),
      Set.of("b"),
      Set.of("y"),
      NONE,
      NONE
    );
    assertEquals(List.of(), refusals);
  }

  @Test
  @DisplayName("an unfilled target whose same-named source a row reads names that source as read elsewhere")
  void namesakeReadElsewhere() {
    final var refusals = Unpaired.refusals(
      SIDES,
      List.of("a", "b"),
      match(List.of("b"), List.of()),
      Set.of(),
      Set.of(),
      NONE,
      NONE
    );
    assertEquals(List.of(PairingMessages.sameNameSourceRead("Src", "Tgt", "field", "field", "b")), refusals);
  }

  @Test
  @DisplayName("a path starting at an unknown property leaves its whole side unrefused, and only that side")
  void unknownPathStartSparesItsSide() {
    final var unpaired = match(List.of("b"), List.of("y"));
    assertEquals(
      List.of(PairingMessages.noSameNameTarget("Src", "Tgt", "field", "field", "y")),
      Unpaired.refusals(SIDES, List.of("a", "y"), unpaired, null, Set.of(), NONE, NONE)
    );
    assertEquals(
      List.of(PairingMessages.noSameNameSource("Src", "Tgt", "field", "field", "b")),
      Unpaired.refusals(SIDES, List.of("a", "y"), unpaired, Set.of(), null, NONE, NONE)
    );
  }

  @Test
  @DisplayName("each side's refusal names that side's word for a property")
  void slotsFollowTheirSide() {
    final var sides = new Unpaired.Sides("Bean", "Rec", "property", "field");
    assertEquals(
      List.of(
        PairingMessages.noSameNameSource("Bean", "Rec", "field", "property", "b"),
        PairingMessages.noSameNameTarget("Bean", "Rec", "property", "field", "y")
      ),
      Unpaired.refusals(sides, List.of("a"), match(List.of("b"), List.of("y")), Set.of(), Set.of(), NONE, NONE)
    );
  }

  @Test
  @DisplayName("a property its class has no member to write is not refused, on the side whose class it is")
  void aDerivedPropertyIsNotRefused() {
    final var unpaired = match(List.of("b", "c"), List.of("y", "z"));
    final Predicate<String> derived = name -> name.equals("c") || name.equals("z");
    assertEquals(
      List.of(
        PairingMessages.noSameNameSource("Src", "Tgt", "field", "field", "b"),
        PairingMessages.noSameNameTarget("Src", "Tgt", "field", "field", "y")
      ),
      Unpaired.refusals(SIDES, List.of("a", "y", "z"), unpaired, Set.of(), Set.of(), derived, derived)
    );
    assertEquals(
      List.of(
        PairingMessages.noSameNameSource("Src", "Tgt", "field", "field", "b"),
        PairingMessages.noSameNameSource("Src", "Tgt", "field", "field", "c"),
        PairingMessages.noSameNameTarget("Src", "Tgt", "field", "field", "y")
      ),
      Unpaired.refusals(SIDES, List.of("a", "y", "z"), unpaired, Set.of(), Set.of(), NONE, derived)
    );
  }

  @Test
  @DisplayName("a target derived getter whose namesake another row reads is not refused either")
  void aDerivedNamesakeReadElsewhereIsNotRefused() {
    assertEquals(
      List.of(),
      Unpaired.refusals(SIDES, List.of("a", "b"), match(List.of("b"), List.of()), Set.of(), Set.of(), "b"::equals, NONE)
    );
  }

  @Test
  @DisplayName("a kept source property its class has no member to write is not taken, and one it has is")
  void keptNamesLeaveADerivedPropertyOut() {
    assertEquals(List.of("a"), KeptNames.taken(List.of("a", "b", "c"), Set.of("a", "b"), "b"::equals));
    assertEquals(List.of("a", "b"), KeptNames.taken(List.of("a", "b", "c"), Set.of("a", "b"), NONE));
    assertEquals(List.of(), KeptNames.taken(List.of("a"), Set.of(), NONE));
  }
}
