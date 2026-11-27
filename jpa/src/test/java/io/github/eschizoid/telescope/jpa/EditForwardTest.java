package io.github.eschizoid.telescope.jpa;

import static io.github.eschizoid.telescope.Telescope.edits;
import static io.github.eschizoid.telescope.mapping.Mapping.compute;
import static io.github.eschizoid.telescope.mapping.Mapping.drop;
import static io.github.eschizoid.telescope.mapping.Mapping.via;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.eschizoid.telescope.conversion.EditMapper;
import io.github.eschizoid.telescope.jpa.fixtures.Barrel;
import io.github.eschizoid.telescope.jpa.fixtures.BarrelMappers;
import io.github.eschizoid.telescope.jpa.fixtures.BarrelSent;
import io.github.eschizoid.telescope.jpa.fixtures.Flask;
import io.github.eschizoid.telescope.jpa.fixtures.FlaskSent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins that an edit mapper's {@code forward} builds a new entity, at every level, with none of the
 * generated identifiers and versions the client sends, so saving it cannot overwrite a stored row,
 * while {@code patchInto} still matches each sent element to the held one by its identifier.
 */
class EditForwardTest {

  /** The runtime edit mappers of the same pairs. */
  static final class Runtime {

    static final EditMapper<BarrelSent, Barrel> BARREL = edits(BarrelSent.class, Barrel.class);

    static final EditMapper<FlaskSent, Flask> FLASK = edits(FlaskSent.class, Flask.class);
  }

  /**
   * Edit mappers whose element conversion calls {@code forward} of another edit mapper, on both
   * paths, and records the identifier each gives.
   */
  static final class Probing {

    static final List<Object> SEEN = new ArrayList<>();

    static final EditMapper<BarrelSent, Barrel> BARREL = edits(BarrelSent.class, Barrel.class);

    static final EditMapper<FlaskSent, Flask> FLASK = edits(
      FlaskSent.class,
      Flask.class,
      compute(Flask::getWine, Probing::wine),
      drop(FlaskSent::wine)
    );

    static String wine(final FlaskSent sent) {
      SEEN.add(BarrelMappers.FLASK.forward(new FlaskSent(9L, 1L, "probe")).getId());
      SEEN.add(Runtime.FLASK.forward(new FlaskSent(9L, 1L, "probe")).getId());
      return sent.wine();
    }
  }

  /** A barrel whose flasks convert through an edit mapper a via row names. */
  static final class Named {

    private static final EditMapper<FlaskSent, Flask> PRICED = edits(FlaskSent.class, Flask.class);

    static final EditMapper<BarrelSent, Barrel> BARREL = edits(
      BarrelSent.class,
      Barrel.class,
      via(BarrelSent::flasks, Barrel::getFlasks, PRICED)
    );
  }

  private static Map<String, EditMapper<BarrelSent, Barrel>> paths() {
    return Map.of("generated", BarrelMappers.BARREL, "runtime", Runtime.BARREL);
  }

  private static final BarrelSent SENT = new BarrelSent(4L, 3, "red", List.of(new FlaskSent(7L, 2L, "rioja")));

  @Test
  @DisplayName("forward writes no identifier and no version the client sends, the elements' included")
  void forwardWritesNoGeneratedProperty() {
    for (final var path : paths().entrySet()) {
      final var built = path.getValue().forward(SENT);
      assertNull(built.getId(), path.getKey());
      assertEquals(0, built.getVersion(), path.getKey());
      assertEquals("red", built.getLabel(), path.getKey());
      final var flask = built.getFlasks().getFirst();
      assertNull(flask.getId(), path.getKey());
      assertNull(flask.getVersion(), path.getKey());
      assertEquals("rioja", flask.getWine(), path.getKey());
    }
  }

  @Test
  @DisplayName("patchInto matches a sent element to the held one by its identifier, and writes neither")
  void patchIntoMatchesByTheIdentifier() {
    for (final var path : paths().entrySet()) {
      final var barrel = new Barrel();
      barrel.setId(4L);
      barrel.setVersion(1);
      final var held = new Flask();
      held.setId(7L);
      held.setVersion(1L);
      held.setWine("old");
      held.setBarrel(barrel);
      barrel.getFlasks().add(held);
      path.getValue().patchInto(barrel, SENT);
      assertSame(held, barrel.getFlasks().getFirst(), path.getKey() + ": the held flask is written in place");
      assertEquals("rioja", held.getWine(), path.getKey());
      assertEquals(1L, held.getVersion(), path.getKey() + ": the held version stays");
      assertEquals(1, barrel.getVersion(), path.getKey());
      assertEquals(4L, barrel.getId(), path.getKey());
    }
  }

  @Test
  @DisplayName("forward called while a patchInto converts an element writes no generated identifier either")
  void forwardInsideAWriteWritesNoGeneratedProperty() {
    final var barrel = new Barrel();
    final var held = new Flask();
    held.setId(7L);
    held.setWine("old");
    held.setBarrel(barrel);
    barrel.getFlasks().add(held);
    Probing.SEEN.clear();
    Probing.BARREL.patchInto(barrel, SENT);
    assertSame(held, barrel.getFlasks().getFirst(), "the write still matches the held flask by its identifier");
    assertEquals("rioja", held.getWine());
    assertEquals(Arrays.asList(null, null), Probing.SEEN, "each forward, generated and runtime, gives no identifier");
  }

  @Test
  @DisplayName("forward writes no generated identifier into elements a via row's edit mapper converts")
  void forwardThroughAViaRowWritesNoGeneratedProperty() {
    final var built = Named.BARREL.forward(SENT);
    assertNull(built.getId());
    assertNull(built.getFlasks().getFirst().getId());
    assertNull(built.getFlasks().getFirst().getVersion());
    assertEquals("rioja", built.getFlasks().getFirst().getWine());
  }
}
