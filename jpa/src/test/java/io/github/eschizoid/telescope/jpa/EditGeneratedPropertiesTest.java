package io.github.eschizoid.telescope.jpa;

import static io.github.eschizoid.telescope.Telescope.edits;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.eschizoid.telescope.conversion.EditMapper;
import io.github.eschizoid.telescope.jpa.fixtures.Barrel;
import io.github.eschizoid.telescope.jpa.fixtures.BarrelMappers;
import io.github.eschizoid.telescope.jpa.fixtures.BarrelSent;
import io.github.eschizoid.telescope.jpa.fixtures.CaskMappers;
import io.github.eschizoid.telescope.jpa.fixtures.CaskSent;
import io.github.eschizoid.telescope.jpa.fixtures.Flask;
import io.github.eschizoid.telescope.jpa.fixtures.FlaskSent;
import io.github.eschizoid.telescope.jpa.fixtures.Ledger;
import io.github.eschizoid.telescope.jpa.fixtures.LedgerMappers;
import io.github.eschizoid.telescope.jpa.fixtures.LedgerSent;
import io.github.eschizoid.telescope.jpa.fixtures.Shop;
import io.github.eschizoid.telescope.jpa.fixtures.VialSent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins that no conversion of an edit mapper writes a generated identifier or a version, at any
 * level, on either path: an entity with no setter for them is edited, a held element keeps its
 * version through {@code patchInto} and {@code into}, a new element takes the version the database
 * gives it, and {@code forward} writes none into lines converted by name; the merge by identifier
 * still matches each sent element by the identifier its source sends.
 */
class EditGeneratedPropertiesTest {

  /** The runtime edit mappers of the same pairs. */
  static final class Runtime {

    static final EditMapper<LedgerSent, Ledger> LEDGER = edits(LedgerSent.class, Ledger.class);

    static final EditMapper<BarrelSent, Barrel> BARREL = edits(BarrelSent.class, Barrel.class);

    static final EditMapper<FlaskSent, Flask> FLASK = edits(FlaskSent.class, Flask.class);

    static final EditMapper<CaskSent, Barrel> CASK = edits(CaskSent.class, Barrel.class);
  }

  private static Shop shop;

  @BeforeAll
  static void open() {
    shop = Shop.open("edit-generated-properties");
  }

  @AfterAll
  static void close() {
    shop.close();
  }

  /** A barrel holding one flask, written in its own session, and the two identifiers. */
  private static long[] seed() {
    return shop.inSession(session -> {
      final var barrel = new Barrel();
      barrel.setLabel("red");
      final var flask = new Flask();
      flask.setWine("old");
      flask.setBarrel(barrel);
      barrel.getFlasks().add(flask);
      session.persist(barrel);
      session.flush();
      return new long[] { barrel.getId(), flask.getId() };
    });
  }

  @Test
  @DisplayName("an entity with no setter for its identifier and version is edited, neither written")
  void editsAnEntityWithNoSetterForItsGeneratedProperties() {
    for (final var path : Map.of("generated", LedgerMappers.LEDGER, "runtime", Runtime.LEDGER).entrySet()) {
      final var built = path.getValue().forward(new LedgerSent(4L, 3, "sent"));
      assertNull(built.getId(), path.getKey());
      assertEquals(0, built.getVersion(), path.getKey());
      assertEquals("sent", built.getNote(), path.getKey());
      final var held = new Ledger(7L, 2);
      path.getValue().patchInto(held, new LedgerSent(4L, 3, "changed"));
      path.getValue().into(held, new LedgerSent(5L, 9, "again"));
      assertEquals(7L, held.getId(), path.getKey());
      assertEquals(2, held.getVersion(), path.getKey());
      assertEquals("again", held.getNote(), path.getKey());
    }
  }

  @Test
  @DisplayName(
    "a held element keeps its version through patchInto and into, and a new one takes the database's," +
      " not the client's"
  )
  void versionsAreTheDatabases() {
    for (final var path : Map.of("generated", BarrelMappers.BARREL, "runtime", Runtime.BARREL).entrySet()) {
      for (final var operation : List.of("patchInto", "into")) {
        final var ids = seed();
        final var name = path.getKey() + " " + operation;
        shop.withEntity(
          Barrel.class,
          ids[0],
          barrel -> barrel.getFlasks().size(),
          barrel -> {
            final var held = barrel.getFlasks().getFirst();
            final var sent = new BarrelSent(
              ids[0],
              99,
              "red",
              List.of(new FlaskSent(ids[1], 99L, "rioja"), new FlaskSent(null, 55L, "new"))
            );
            if (operation.equals("into")) path.getValue().into(barrel, sent);
            else path.getValue().patchInto(barrel, sent);
            assertSame(held, barrel.getFlasks().getFirst(), name + ": the held flask is written in place");
            assertEquals(0L, held.getVersion(), name + ": the held version stays the loaded one");
            assertNull(barrel.getFlasks().get(1).getVersion(), name + ": a new flask carries no version");
            return null;
          }
        );
        shop.inSession(session -> {
          final var barrel = session.find(Barrel.class, ids[0]);
          final var held = barrel
            .getFlasks()
            .stream()
            .filter(f -> f.getId() == ids[1])
            .findFirst()
            .orElseThrow();
          final var added = barrel
            .getFlasks()
            .stream()
            .filter(f -> f.getId() != ids[1])
            .findFirst()
            .orElseThrow();
          assertEquals("rioja", held.getWine(), name);
          assertEquals(1L, held.getVersion(), name + ": the provider's increment, not the client's 99");
          assertEquals(0L, added.getVersion(), name + ": the provider's first version, not the client's 55");
          assertEquals(0, barrel.getVersion(), name + ": the barrel's own fields did not change");
          return null;
        });
      }
    }
  }

  @Test
  @DisplayName("lines converted by name carry no identifier or version on forward, and are still matched by it")
  void linesConvertedByNameCarryNone() {
    for (final var path : Map.of("generated", CaskMappers.CASK, "runtime", Runtime.CASK).entrySet()) {
      final var built = path.getValue().forward(new CaskSent(4L, 3, "red", List.of(new VialSent(9L, 5L, "rioja"))));
      assertNull(built.getFlasks().getFirst().getId(), path.getKey());
      assertNull(built.getFlasks().getFirst().getVersion(), path.getKey());
      assertEquals("rioja", built.getFlasks().getFirst().getWine(), path.getKey());
      final var barrel = new Barrel();
      final var held = new Flask();
      held.setId(9L);
      held.setVersion(1L);
      held.setBarrel(barrel);
      barrel.getFlasks().add(held);
      path.getValue().patchInto(barrel, new CaskSent(null, 0, null, List.of(new VialSent(9L, 5L, "rioja"))));
      assertSame(held, barrel.getFlasks().getFirst(), path.getKey() + ": matched by the identifier the source sends");
      assertEquals(1L, held.getVersion(), path.getKey());
      assertEquals("rioja", held.getWine(), path.getKey());
    }
  }
}
