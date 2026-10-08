package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.Edit.over;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Paths handed back by a generated navigator carry the same fusion identity as the hand-written
 * path through the same components, so {@code Telescope.all} fuses them exactly as it fuses
 * hand-written paths, and fuses one form with the other.
 *
 * <p>Fused and sequential application produce the same value by design, so the result alone cannot
 * tell them apart. These tests observe fusion directly: through the hop keys the fusion trie shares
 * by, through {@code Fusion.fuse} declining or not, and through how many times a shared container
 * is read.
 */
class NavigatorFusionTest {

  private static FusionCrew crew() {
    final var ann = new FusionMember("Ann", "ANN@X.IO", 30);
    final var bo = new FusionMember("Bo", "BO@X.IO", 25);
    return new FusionCrew("core", List.of(ann, bo), Map.of("lead", ann), Optional.of(bo), ann, new AtomicInteger());
  }

  private static List<Object> keys(final Telescope<?, ?> path) {
    assertNotNull(path.hops, "the path carries no hop record, so Telescope.all cannot fuse it");
    return path.hops.stream().map(Fusion.Hop::key).toList();
  }

  @SafeVarargs
  @SuppressWarnings("varargs")
  private static <S> boolean fuses(final Edit<S>... edits) {
    return Fusion.fuse(edits) != null;
  }

  @SafeVarargs
  private static <S> S sequential(final S source, final Edit<S>... edits) {
    var s = source;
    for (final var e : edits) s = e.apply(s);
    return s;
  }

  @Test
  @DisplayName("every navigator hop shape records the hop keys its hand-written twin records")
  void navigatorHopsMatchHandWrittenHops() {
    final var root = Telescope.of(FusionCrew.class);

    assertEquals(keys(root.field(FusionCrew::label)), keys(FusionCrewTelescope.of().label()));
    assertEquals(
      keys(root.field(FusionCrew::captain).field(FusionMember::name)),
      keys(FusionCrewTelescope.of().captain().name())
    );
    assertEquals(
      keys(root.each(FusionCrew::members).field(FusionMember::name)),
      keys(FusionCrewTelescope.of().members().each().name())
    );
    assertEquals(
      keys(root.eachValue(FusionCrew::byRole).field(FusionMember::email)),
      keys(FusionCrewTelescope.of().byRole().eachValue().email())
    );
    assertEquals(
      keys(root.whenPresent(FusionCrew::lead).field(FusionMember::age)),
      keys(FusionCrewTelescope.of().lead().whenPresent().age())
    );
  }

  @Test
  @DisplayName("two navigator edits under one each(...) read the list once, as the hand-written pair does")
  void navigatorEditsShareTheContainerWalk() {
    final var names = over(FusionCrewTelescope.of().members().each().name(), String::toLowerCase);
    final var emails = over(FusionCrewTelescope.of().members().each().email(), String::toLowerCase);
    final var handNames = over(
      Telescope.of(FusionCrew.class).each(FusionCrew::members).field(FusionMember::name),
      String::toLowerCase
    );
    final var handEmails = over(
      Telescope.of(FusionCrew.class).each(FusionCrew::members).field(FusionMember::email),
      String::toLowerCase
    );

    final var sequentialSource = crew();
    final var expected = sequential(sequentialSource, names, emails);
    assertEquals(2, sequentialSource.memberReads().get(), "the sequential fold walks the list once per edit");

    final var handSource = crew();
    Telescope.all(handNames, handEmails).apply(handSource);
    assertEquals(1, handSource.memberReads().get(), "control: the hand-written pair fuses into one walk");

    final var navigatorSource = crew();
    final var fused = Telescope.all(names, emails).apply(navigatorSource);
    assertEquals(1, navigatorSource.memberReads().get(), "the navigator pair fuses into one walk");
    assertEquals(expected.members(), fused.members());
  }

  @Test
  @DisplayName("a navigator edit and a hand-written edit on the same component fuse and keep edit order")
  void navigatorAndHandWrittenPathsFuseTogether() {
    final var first = over(FusionCrewTelescope.of().members().each().name(), (final String n) -> n + "1");
    final var second = over(
      Telescope.of(FusionCrew.class).each(FusionCrew::members).field(FusionMember::name),
      (final String n) -> n + "2"
    );

    assertTrue(fuses(first, second));
    final var source = crew();
    final var out = Telescope.all(first, second).apply(source);
    assertEquals(1, source.memberReads().get());
    assertEquals(List.of("Ann12", "Bo12"), out.members().stream().map(FusionMember::name).toList());
  }

  @Test
  @DisplayName("navigator edits across every component of a record fuse and match the sequential fold")
  void navigatorEditsAcrossOneRecordFuse() {
    final var label = over(FusionCrewTelescope.of().label(), String::toUpperCase);
    final var names = over(FusionCrewTelescope.of().members().each().name(), String::toUpperCase);
    final var emails = over(FusionCrewTelescope.of().byRole().eachValue().email(), String::toLowerCase);
    final var ages = over(FusionCrewTelescope.of().lead().whenPresent().age(), (final Integer a) -> a + 1);
    final var captain = over(FusionCrewTelescope.of().captain().name(), (final String n) -> n + "!");

    assertTrue(fuses(label, names, emails, ages, captain));
    final var source = crew();
    final var expected = sequential(source, label, names, emails, ages, captain);
    final var fused = Telescope.all(label, names, emails, ages, captain).apply(source);
    assertEquals(expected.label(), fused.label());
    assertEquals(expected.members(), fused.members());
    assertEquals(expected.byRole(), fused.byRole());
    assertEquals(expected.lead(), fused.lead());
    assertEquals(expected.captain(), fused.captain());
  }

  @Test
  @DisplayName("@BeanFocus navigator paths record the hand-written bean hop keys and fuse")
  void beanNavigatorPathsFuse() {
    assertEquals(
      keys(Telescope.ofBean(FusionRoster.class).field(FusionRoster::getTitle)),
      keys(FusionRosterTelescope.of().title())
    );

    final var title = over(FusionRosterTelescope.of().title(), String::toUpperCase);
    final var code = over(FusionRosterTelescope.of().code(), (final String c) -> c + "-1");
    assertTrue(fuses(title, code));

    final var roster = new FusionRoster();
    roster.setTitle("crew");
    roster.setCode("c");
    final var out = Telescope.all(title, code).apply(roster);
    assertEquals("CREW", out.getTitle());
    assertEquals("c-1", out.getCode());
  }

  @Test
  @DisplayName("a navigator path through a bridge hop records no hop keys, so its edits take the sequential fold")
  void bridgeHopStaysUnfused() {
    assertNull(FusionEntityTelescope.of().asFusionDto().email().hops);

    final var email = over(FusionEntityTelescope.of().asFusionDto().email(), String::toLowerCase);
    final var id = over(FusionEntityTelescope.of().asFusionDto().id(), (final String i) -> i + "!");
    assertFalse(fuses(email, id));

    final var entity = new FusionEntity("e1", "A@B.IO");
    assertEquals(sequential(entity, email, id), Telescope.all(email, id).apply(entity));
  }
}
