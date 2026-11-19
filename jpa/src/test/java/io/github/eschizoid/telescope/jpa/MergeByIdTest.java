package io.github.eschizoid.telescope.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.jpa.fixtures.Basket;
import io.github.eschizoid.telescope.jpa.fixtures.BasketEdit;
import io.github.eschizoid.telescope.jpa.fixtures.BasketForm;
import io.github.eschizoid.telescope.jpa.fixtures.BasketMappers;
import io.github.eschizoid.telescope.jpa.fixtures.Line;
import io.github.eschizoid.telescope.jpa.fixtures.LineEdit;
import io.github.eschizoid.telescope.jpa.fixtures.LineForm;
import io.github.eschizoid.telescope.jpa.fixtures.Shop;
import io.github.eschizoid.telescope.jpa.fixtures.TagEdit;
import io.github.eschizoid.telescope.jpa.fixtures.TagForm;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * With this module on the class path, {@code into} and {@code patchInto} merge a managed basket's
 * lines and tags by identifier, on the generated path and the runtime path alike. Hibernate's
 * statistics count what the flush issues: an element sent as it is held issues nothing, a changed
 * one exactly one UPDATE, and only an element removed or added a DELETE or an INSERT.
 */
class MergeByIdTest {

  private static Shop shop;

  @BeforeAll
  static void open() {
    shop = Shop.open("merge-by-id");
  }

  @AfterAll
  static void close() {
    shop.close();
  }

  /** A line as a test describes it, whichever form a path sends it in. */
  record Sent(Long id, String sku, int quantity) {}

  /** A tag as a test describes it. */
  record Label(Long id, String label) {}

  /** The two paths a basket is written in place by, each through its own pair of mappers. */
  enum Path {
    GENERATED {
      @Override
      void into(final Basket basket, final String name, final List<Sent> lines, final Set<Label> tags) {
        BasketMappers.FORM.into(basket, new BasketForm(basket.getId(), name, forms(lines), tagForms(tags)));
      }

      @Override
      void patchInto(final Basket basket, final List<Sent> lines) {
        BasketMappers.PATCH.patchInto(basket, new BasketForm(null, null, forms(lines), null));
      }
    },
    RUNTIME {
      @Override
      void into(final Basket basket, final String name, final List<Sent> lines, final Set<Label> tags) {
        BasketMappers.EDIT.into(basket, new BasketEdit(basket.getId(), name, edits(lines), tagEdits(tags)));
      }

      @Override
      void patchInto(final Basket basket, final List<Sent> lines) {
        BasketMappers.EDIT_PATCH.patchInto(basket, new BasketEdit(null, null, edits(lines), null));
      }
    };

    abstract void into(Basket basket, String name, List<Sent> lines, Set<Label> tags);

    abstract void patchInto(Basket basket, List<Sent> lines);

    private static List<LineForm> forms(final List<Sent> lines) {
      return lines
        .stream()
        .map(line -> new LineForm(line.id(), line.sku(), line.quantity()))
        .toList();
    }

    private static List<LineEdit> edits(final List<Sent> lines) {
      return lines
        .stream()
        .map(line -> new LineEdit(line.id(), line.sku(), line.quantity()))
        .toList();
    }

    private static Set<TagForm> tagForms(final Set<Label> tags) {
      return tags
        .stream()
        .map(tag -> new TagForm(tag.id(), tag.label()))
        .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<TagEdit> tagEdits(final Set<Label> tags) {
      return tags
        .stream()
        .map(tag -> new TagEdit(tag.id(), tag.label()))
        .collect(Collectors.toCollection(LinkedHashSet::new));
    }
  }

  private static List<Sent> sent(final Basket basket) {
    return basket
      .getLines()
      .stream()
      .map(line -> new Sent(line.getId(), line.getSku(), line.getQuantity()))
      .collect(Collectors.toCollection(ArrayList::new));
  }

  private static Set<Label> labels(final Basket basket) {
    return basket
      .getTags()
      .stream()
      .map(tag -> new Label(tag.getId(), tag.getLabel()))
      .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * What the write and the flush issued: the statements prepared, then the entities inserted,
   * updated and deleted. An insert may also prepare the fetch of a new block of identifiers from
   * the sequence, depending on how many the session has used, so a write that inserts is described
   * by its entity counts alone.
   */
  private static String issued() {
    final var statistics = shop.statistics();
    return statistics.getPrepareStatementCount() + " statements: " + entities();
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
  @DisplayName("into a basket sent back as it is held issues no statement and keeps every element")
  void anUnchangedBasketIssuesNothing() {
    for (final var path : Path.values()) {
      final var id = shop.seed();
      shop.withBasket(id, basket -> {
        final var lines = List.copyOf(basket.getLines());
        final var tags = Set.copyOf(basket.getTags());
        path.into(basket, basket.getName(), sent(basket), labels(basket));
        for (var i = 0; i < lines.size(); i++) assertSame(lines.get(i), basket.getLines().get(i), path::name);
        assertEquals(tags, basket.getTags(), path::name);
        return null;
      });
      assertEquals("0 statements: 0 INSERT, 0 UPDATE, 0 DELETE", issued(), path::name);
    }
  }

  @Test
  @DisplayName(
    "into a basket with one line changed issues exactly one UPDATE, and keeps what the form has no property for"
  )
  void oneChangedLineIssuesOneUpdate() {
    for (final var path : Path.values()) {
      final var id = shop.seed();
      shop.withBasket(id, basket -> {
        final var bread = basket.getLines().get(1);
        final var lines = sent(basket);
        lines.set(1, new Sent(bread.getId(), "bread", 5));
        path.into(basket, basket.getName(), lines, labels(basket));
        assertSame(bread, basket.getLines().get(1), path::name);
        assertEquals(5, bread.getQuantity(), path::name);
        assertEquals("sliced", bread.note(), path::name);
        assertSame(basket, bread.basket(), path::name);
        return null;
      });
      assertEquals("1 statements: 0 INSERT, 1 UPDATE, 0 DELETE", issued(), path::name);
    }
  }

  @Test
  @DisplayName(
    "into a basket that drops a line and adds one issues one DELETE and one INSERT, and touches no kept line"
  )
  void aDroppedAndANewLineIssueOneDeleteAndOneInsert() {
    for (final var path : Path.values()) {
      final var id = shop.seed();
      shop.withBasket(id, basket -> {
        final var bread = basket.getLines().get(1);
        final var milk = basket.getLines().get(2);
        final var lines = sent(basket);
        lines.removeFirst();
        lines.add(new Sent(null, "eggs", 12));
        path.into(basket, basket.getName(), lines, labels(basket));
        assertEquals(
          List.of("bread", "milk", "eggs"),
          basket.getLines().stream().map(Line::getSku).toList(),
          path::name
        );
        assertSame(bread, basket.getLines().get(0), path::name);
        assertSame(milk, basket.getLines().get(1), path::name);
        assertSame(basket, basket.getLines().get(2).basket(), () -> path + ": the new line went through addLine");
        return null;
      });
      assertEquals("1 INSERT, 0 UPDATE, 1 DELETE", entities(), path::name);
    }
  }

  @Test
  @DisplayName("into a basket whose lines arrive in another order ends in that order and issues no statement")
  void reorderedLinesFollowTheSource() {
    for (final var path : Path.values()) {
      final var id = shop.seed();
      shop.withBasket(id, basket -> {
        final var lines = sent(basket);
        final var reversed = new ArrayList<>(lines.reversed());
        path.into(basket, basket.getName(), reversed, labels(basket));
        assertEquals(
          List.of("milk", "bread", "apple"),
          basket.getLines().stream().map(Line::getSku).toList(),
          path::name
        );
        return null;
      });
      assertEquals("0 statements: 0 INSERT, 0 UPDATE, 0 DELETE", issued(), path::name);
    }
  }

  @Test
  @DisplayName(
    "a tag written a new label in place is filed under its new hash: its UPDATE, and the join column the provider" +
      " writes again for an element its set snapshot holds under the old hash"
  )
  void aTagRelabelledInPlaceIsRehashed() {
    for (final var path : Path.values()) {
      final var id = shop.seed();
      shop.withBasket(id, basket -> {
        final var red = basket
          .getTags()
          .stream()
          .filter(tag -> tag.getLabel().equals("red"))
          .findFirst()
          .orElseThrow();
        final var tags = new LinkedHashSet<Label>();
        for (final var tag : basket.getTags())
          tags.add(new Label(tag.getId(), tag == red ? "crimson" : tag.getLabel()));
        path.into(basket, basket.getName(), sent(basket), tags);
        assertEquals("crimson", red.getLabel(), path::name);
        assertTrue(basket.getTags().contains(red), () -> path + ": the set finds the tag under its new hash");
        assertTrue(
          basket
            .getTags()
            .stream()
            .anyMatch(tag -> tag == red),
          path::name
        );
        assertEquals(2, basket.getTags().size(), path::name);
        return null;
      });
      assertEquals("2 statements: 0 INSERT, 1 UPDATE, 0 DELETE", issued(), path::name);
    }
  }

  @Test
  @DisplayName(
    "a line carrying a generated identifier the basket does not hold is refused by name before anything is written"
  )
  void anUnknownGeneratedIdentifierIsRefused() {
    for (final var path : Path.values()) {
      final var id = shop.seed();
      shop.withBasket(id, basket -> {
        final var lines = sent(basket);
        final var held = List.copyOf(basket.getLines());
        lines.add(new Sent(987_654L, "pears", 4));
        final var refused = assertThrows(IllegalArgumentException.class, () ->
          path.into(basket, "renamed", lines, labels(basket))
        );
        assertTrue(refused.getMessage().contains("'lines'"), refused::getMessage);
        assertTrue(refused.getMessage().contains("987654"), refused::getMessage);
        assertTrue(refused.getMessage().contains("the identifier of Line is generated"), refused::getMessage);
        assertEquals("weekly", basket.getName(), () -> path + ": nothing was written");
        assertEquals(held, basket.getLines(), path::name);
        return null;
      });
      assertEquals("0 statements: 0 INSERT, 0 UPDATE, 0 DELETE", issued(), path::name);
    }
  }

  @Test
  @DisplayName("two lines carrying one identifier are refused by name before anything is written")
  void oneIdentifierSentTwiceIsRefused() {
    for (final var path : Path.values()) {
      final var id = shop.seed();
      shop.withBasket(id, basket -> {
        final var lines = sent(basket);
        lines.add(new Sent(lines.getFirst().id(), "apple", 9));
        final var refused = assertThrows(IllegalArgumentException.class, () ->
          path.into(basket, "renamed", lines, labels(basket))
        );
        assertTrue(refused.getMessage().contains("Two converted elements carry the identifier"), refused::getMessage);
        assertEquals("weekly", basket.getName(), () -> path + ": nothing was written");
        assertEquals(1, basket.getLines().getFirst().getQuantity(), path::name);
        return null;
      });
    }
  }

  @Test
  @DisplayName(
    "a refusal in a collection written after another leaves the one before it unwritten, since both are checked first"
  )
  void aLaterRefusalLeavesAnEarlierCollectionUnwritten() {
    for (final var path : Path.values()) {
      final var id = shop.seed();
      shop.withBasket(id, basket -> {
        final var apple = basket.getLines().getFirst();
        final var lines = sent(basket);
        lines.set(0, new Sent(apple.getId(), "apple", 8));
        final var tags = labels(basket);
        final var red = tags.iterator().next();
        tags.add(new Label(red.id(), "another label"));
        final var refused = assertThrows(IllegalArgumentException.class, () ->
          path.into(basket, basket.getName(), lines, tags)
        );
        assertTrue(refused.getMessage().contains("'tags'"), refused::getMessage);
        assertEquals(1, apple.getQuantity(), () -> path + ": the lines, written before the tags, were not written");
        return null;
      });
      assertEquals("0 statements: 0 INSERT, 0 UPDATE, 0 DELETE", issued(), path::name);
    }
  }

  @Test
  @DisplayName(
    "an element written in place keeps a property the runtime path's nested pair leaves unpaired, which forward" +
      " leaves at its default"
  )
  void anUnpairedPropertyOfAnElementIsKept() {
    final var crate = new Crate();
    final var item = new Item();
    item.setId(1L);
    item.setSku("a");
    item.setMemo("kept");
    crate.getItems().add(item);
    CRATE.into(crate, new CrateEdit(List.of(new ItemEdit(1L, "b"))));
    assertSame(item, crate.getItems().getFirst());
    assertEquals("b", item.getSku());
    assertEquals("kept", item.getMemo());
  }

  /** A runtime mapper whose nested pair leaves {@link Item#getMemo} unpaired. */
  private static final Mapper<CrateEdit, Crate> CRATE = Telescope.mapper(CrateEdit.class, Crate.class);

  public record CrateEdit(List<ItemEdit> items) {}

  public record ItemEdit(Long id, String sku) {}

  /** A crate of entity items, a bean the runtime mapper writes in place. */
  public static class Crate {

    private List<Item> items = new ArrayList<>();

    public List<Item> getItems() {
      return items;
    }

    public void setItems(final List<Item> items) {
      this.items = items;
    }
  }

  /** An item whose memo no form carries. */
  @Entity
  public static class Item {

    @Id
    @GeneratedValue
    private Long id;

    private String sku;
    private String memo;

    public Long getId() {
      return id;
    }

    public void setId(final Long id) {
      this.id = id;
    }

    public String getSku() {
      return sku;
    }

    public void setSku(final String sku) {
      this.sku = sku;
    }

    public String getMemo() {
      return memo;
    }

    public void setMemo(final String memo) {
      this.memo = memo;
    }
  }

  @Test
  @DisplayName(
    "two tags sent with their own identifiers that convert equal are refused through the adder, and no tag row is deleted"
  )
  void sentTagsThatConvertEqualAreRefusedThroughTheAdder() {
    for (final var path : Path.values()) {
      final var id = shop.seed();
      shop.withBasket(id, basket -> {
        final var tags = new LinkedHashSet<Label>();
        for (final var tag : basket.getTags()) tags.add(new Label(tag.getId(), "same"));
        final var refused = assertThrows(IllegalArgumentException.class, () ->
          path.into(basket, basket.getName(), sent(basket), tags)
        );
        assertTrue(refused.getMessage().contains("converted into equal values of Tag"), refused::getMessage);
        assertEquals(2, basket.getTags().size(), path::name);
        return null;
      });
      assertEquals("0 statements: 0 INSERT, 0 UPDATE, 0 DELETE", issued(), path::name);
      final var rows = shop.inSession(session ->
        session
          .createSelectionQuery("select count(t) from Basket b join b.tags t where b.id = :id", Long.class)
          .setParameter("id", id)
          .getSingleResult()
      );
      assertEquals(2L, rows, path::name);
    }
  }

  @Test
  @DisplayName("patchInto merges the lines a partial sends as into does")
  void patchIntoMergesTheSentLines() {
    for (final var path : Path.values()) {
      final var id = shop.seed();
      shop.withBasket(id, basket -> {
        final var apple = basket.getLines().getFirst();
        final var lines = sent(basket);
        lines.set(0, new Sent(apple.getId(), "apple", 7));
        path.patchInto(basket, lines);
        assertSame(apple, basket.getLines().getFirst(), path::name);
        assertEquals(7, apple.getQuantity(), path::name);
        assertEquals("from the orchard", apple.note(), path::name);
        assertEquals("weekly", basket.getName(), () -> path + ": the partial did not send a name");
        return null;
      });
      assertEquals("1 statements: 0 INSERT, 1 UPDATE, 0 DELETE", issued(), path::name);
    }
  }

  @Test
  @DisplayName("lines sent with no identifier replace every held line, as the refill does")
  void linesWithNoIdentifierReplaceEveryLine() {
    for (final var path : Path.values()) {
      final var id = shop.seed();
      shop.withBasket(id, basket -> {
        path.into(basket, basket.getName(), List.of(new Sent(null, "tea", 1)), labels(basket));
        assertEquals(List.of("tea"), basket.getLines().stream().map(Line::getSku).toList(), path::name);
        return null;
      });
      assertEquals("1 INSERT, 0 UPDATE, 3 DELETE", entities(), path::name);
    }
  }
}
