package io.github.eschizoid.telescope.jpa;

import static io.github.eschizoid.telescope.Telescope.edits;
import static io.github.eschizoid.telescope.mapping.Mapping.compute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.conversion.EditMapper;
import io.github.eschizoid.telescope.introspection.OpticNode;
import io.github.eschizoid.telescope.jpa.fixtures.Cart;
import io.github.eschizoid.telescope.jpa.fixtures.CartChanges;
import io.github.eschizoid.telescope.jpa.fixtures.CartLine;
import io.github.eschizoid.telescope.jpa.fixtures.CartMappers;
import io.github.eschizoid.telescope.jpa.fixtures.CartPrices;
import io.github.eschizoid.telescope.jpa.fixtures.LineChanges;
import io.github.eschizoid.telescope.jpa.fixtures.Shop;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An edit mapper whose lines convert through the edit mapper its class declares for their pair,
 * with no row naming it and declared after it, merges a managed cart's lines by identifier, on the
 * generated path and the runtime path alike: a line sent as it is held issues nothing, a changed
 * one exactly one UPDATE, and a held line sent again is priced again by the line mapper's {@code
 * compute} row. The generated identifiers and the back-reference need no row.
 */
class EditMergeByIdTest {

  // The owner's mapper comes first: its lines convert through LINE, found by its pair.
  private static final EditMapper<CartChanges, Cart> EDITS = edits(CartChanges.class, Cart.class);

  private static final EditMapper<LineChanges, CartLine> LINE = edits(
    LineChanges.class,
    CartLine.class,
    compute(CartLine::getUnitPrice, CartPrices::of)
  );

  private static Shop shop;

  @BeforeAll
  static void open() {
    shop = Shop.open("edit-merge-by-id");
  }

  @AfterAll
  static void close() {
    shop.close();
  }

  /** Each path's edit mapper of a cart, by the path's name. */
  private static Map<String, EditMapper<CartChanges, Cart>> paths() {
    return Map.of("generated", CartMappers.EDITS, "runtime", EDITS);
  }

  /** A cart of two lines, the second priced as the catalog no longer prices it, and its id. */
  private static Long seed() {
    return shop.inSession(session -> {
      final var cart = new Cart();
      cart.setName("weekly");
      for (final var line : List.of(new CartLine("ab", 1, 200), new CartLine("cd", 2, 999))) {
        line.setCart(cart);
        cart.getLines().add(line);
      }
      session.persist(cart);
      return cart.getId();
    });
  }

  /** What the cart holds, sent back as it is held. */
  private static List<LineChanges> sent(final Cart cart) {
    final var sent = new ArrayList<LineChanges>();
    for (final var line : cart.getLines()) sent.add(new LineChanges(line.getId(), line.getSku(), line.getQuantity()));
    return sent;
  }

  /** The entities the write and the flush inserted, updated and deleted. */
  private static String entities() {
    final var statistics = shop.statistics();
    return (
      statistics.getEntityInsertCount() +
      " INSERT, " +
      statistics.getEntityUpdateCount() +
      " UPDATE, " +
      statistics.getEntityDeleteCount() +
      " DELETE"
    );
  }

  @Test
  @DisplayName("a line left out is deleted, and a changed line is updated in place, each one statement")
  void mergesTheLinesById() {
    for (final var path : paths().entrySet()) {
      final var id = seed();
      shop.withEntity(
        Cart.class,
        id,
        cart -> cart.getLines().size(),
        cart -> {
          path.getValue().patchInto(cart, new CartChanges(null, List.of(sent(cart).getFirst())));
          return null;
        }
      );
      assertEquals("0 INSERT, 0 UPDATE, 1 DELETE", entities(), path.getKey() + ": the line left out is removed");
      final var other = seed();
      shop.withEntity(
        Cart.class,
        other,
        cart -> cart.getLines().size(),
        cart -> {
          final var first = cart.getLines().getFirst();
          final var sent = sent(cart);
          sent.set(0, new LineChanges(first.getId(), "ab", 5));
          path.getValue().patchInto(cart, new CartChanges(null, sent));
          assertSame(first, cart.getLines().getFirst(), path.getKey() + ": the held line is written in place");
          assertEquals(5, first.getQuantity(), path::getKey);
          assertSame(cart, first.getCart(), path::getKey);
          return null;
        }
      );
      assertEquals(
        "0 INSERT, 2 UPDATE, 0 DELETE",
        entities(),
        path.getKey() + ": the changed line and the line priced again, and nothing else"
      );
    }
  }

  @Test
  @DisplayName("a held line sent again unchanged is priced again by compute, which is its one UPDATE")
  void pricesAHeldLineAgain() {
    for (final var path : paths().entrySet()) {
      final var id = seed();
      final int price = shop.withEntity(
        Cart.class,
        id,
        cart -> cart.getLines().size(),
        cart -> {
          final var stale = cart.getLines().get(1);
          path.getValue().patchInto(cart, new CartChanges(null, sent(cart)));
          assertSame(stale, cart.getLines().get(1), path::getKey);
          return stale.getUnitPrice();
        }
      );
      assertEquals(200, price, path.getKey() + ": the price comes from the catalog");
      assertEquals("0 INSERT, 1 UPDATE, 0 DELETE", entities(), path.getKey() + ": only the stale line is updated");
    }
  }

  @Test
  @DisplayName("a line sent as it is held, and priced as the catalog prices it, issues no statement at all")
  void issuesNothingForAnUnchangedLine() {
    for (final var path : paths().entrySet()) {
      final var id = seed();
      shop.withEntity(
        Cart.class,
        id,
        cart -> cart.getLines().size(),
        cart -> {
          cart.getLines().get(1).setUnitPrice(200);
          return null;
        }
      );
      shop.withEntity(
        Cart.class,
        id,
        cart -> cart.getLines().size(),
        cart -> {
          path.getValue().patchInto(cart, new CartChanges(null, sent(cart)));
          return null;
        }
      );
      assertEquals("0 INSERT, 0 UPDATE, 0 DELETE", entities(), path::getKey);
    }
  }

  @Test
  @DisplayName("a new line is inserted with its back-reference, through the cart's collection")
  void insertsANewLine() {
    for (final var path : paths().entrySet()) {
      final var id = seed();
      shop.withEntity(
        Cart.class,
        id,
        cart -> cart.getLines().size(),
        cart -> {
          final var sent = sent(cart);
          sent.add(new LineChanges(null, "efg", 1));
          path.getValue().patchInto(cart, new CartChanges(null, sent));
          final var added = cart.getLines().getLast();
          assertSame(cart, added.getCart(), path::getKey);
          assertEquals(300, added.getUnitPrice(), path::getKey);
          return null;
        }
      );
      assertEquals("1 INSERT, 1 UPDATE, 0 DELETE", entities(), path::getKey);
    }
  }

  @Test
  @DisplayName("explain() names the edit mapper the lines convert through, on both paths")
  void explainsWhichMapperTheLinesConvertThrough() {
    final var named = Map.of("generated", "CartMappers.LINE", "runtime", "EditMergeByIdTest.LINE");
    for (final var path : paths().entrySet()) {
      final var report = path.getValue().explain();
      assertTrue(
        report.nodes().contains(new OpticNode.ElementsThrough(Cart.class, "lines", named.get(path.getKey()))),
        () -> path.getKey() + ": " + report
      );
    }
  }

  @Test
  @DisplayName("the generated identifiers are accounted for with no row, and forward never writes one")
  void accountsForTheGeneratedIdentifiers() {
    assertTrue(EDITS.explain().nodes().contains(new OpticNode.Skipped("id", OpticNode.Reason.GENERATED)), () ->
      EDITS.explain().toString()
    );
    assertTrue(EDITS.explain().toString().contains("generated, never written by an edit"), () ->
      EDITS.explain().toString()
    );
    final var trace = EDITS.trace(new CartChanges("weekly", List.of())).toString();
    assertTrue(trace.contains("generated, never written by an edit"), trace);
    final var report = LINE.explain();
    assertTrue(report.nodes().contains(new OpticNode.Skipped("id", OpticNode.Reason.GENERATED)), report::toString);
    assertTrue(
      report
        .nodes()
        .stream()
        .noneMatch(node -> node instanceof OpticNode.Mapped mapped && mapped.to().equals("id")),
      () -> "a generated identifier is never shown as mapped: " + report
    );
    for (final var path : List.of(CartMappers.LINE, LINE)) {
      final var line = new CartLine("ab", 1, 0);
      line.setId(7L);
      path.into(line, new LineChanges(9L, "ab", 1));
      assertEquals(7L, line.getId(), "into never writes a generated identifier into a held line");
      assertNull(path.forward(new LineChanges(9L, "ab", 1)).getId(), "forward builds a line with no identifier");
    }
  }
}
