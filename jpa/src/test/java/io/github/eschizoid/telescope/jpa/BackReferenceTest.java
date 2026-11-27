package io.github.eschizoid.telescope.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.EditMapper;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.jpa.fixtures.Bottle;
import io.github.eschizoid.telescope.jpa.fixtures.Crate;
import io.github.eschizoid.telescope.jpa.fixtures.CrateForm;
import io.github.eschizoid.telescope.jpa.fixtures.ItemForm;
import io.github.eschizoid.telescope.jpa.fixtures.ItemPatch;
import io.github.eschizoid.telescope.jpa.fixtures.Jar;
import io.github.eschizoid.telescope.jpa.fixtures.Pallet;
import io.github.eschizoid.telescope.jpa.fixtures.PalletForm;
import io.github.eschizoid.telescope.jpa.fixtures.PalletPatch;
import io.github.eschizoid.telescope.jpa.fixtures.Rack;
import io.github.eschizoid.telescope.jpa.fixtures.RackForm;
import io.github.eschizoid.telescope.jpa.fixtures.Sack;
import io.github.eschizoid.telescope.jpa.fixtures.Shop;
import io.github.eschizoid.telescope.jpa.fixtures.ULine;
import io.github.eschizoid.telescope.jpa.fixtures.UOrder;
import io.github.eschizoid.telescope.jpa.fixtures.UOrderForm;
import io.github.eschizoid.telescope.jpa.fixtures.YardMappers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A bidirectional {@code @OneToMany} written in place, with no row naming how: each new element is
 * pointed at its owner before the flush, through the owner's adder where it has one and through the
 * property {@code mappedBy} names where it has none, and each element taken out goes through the
 * owner's remover, or has that property cleared. Hibernate's statistics count what the flush
 * issues: a new element is one INSERT carrying its foreign key, an orphan one DELETE and nothing
 * else, and an element of an association without orphan removal one UPDATE nulling its key. The
 * generated and the runtime mapper are held to the same counts.
 */
class BackReferenceTest {

  private static Shop shop;

  /** The runtime mappers, built here, where no {@code @Bridge} field declares the pairs. */
  private static final Mapper<CrateForm, Crate> CRATE = Telescope.mapper(CrateForm.class, Crate.class);

  private static final Mapper<RackForm, Rack> RACK = Telescope.mapper(RackForm.class, Rack.class);

  private static final Mapper<PalletForm, Pallet> PALLET = Telescope.mapper(PalletForm.class, Pallet.class);

  private static final EditMapper<PalletPatch, Pallet> PALLET_PATCH = Telescope.edits(PalletPatch.class, Pallet.class);

  @BeforeAll
  static void open() {
    shop = Shop.open("back-reference");
  }

  @AfterAll
  static void close() {
    shop.close();
  }

  /** Each path's edit mapper of a pair, by the path's name. */
  private static <A, B> Map<String, EditMapper<A, B>> editPaths(
    final EditMapper<A, B> generated,
    final EditMapper<A, B> runtime
  ) {
    return Map.of("generated", generated, "runtime", runtime);
  }

  /** Each path's mapper of a pair, by the path's name. */
  private static <A, B> Map<String, Mapper<A, B>> paths(final Mapper<A, B> generated, final Mapper<A, B> runtime) {
    return Map.of("generated", generated, "runtime", runtime);
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

  /** The foreign key each item of {@code entity} with {@code label} carries in the database. */
  private static List<Object> keysOf(final String entity, final String owner, final String label) {
    return shop.inSession(session ->
      session
        .createQuery(
          "select o.id from " + entity + " i left join i." + owner + " o where i.label = :label",
          Object.class
        )
        .setParameter("label", label)
        .getResultList()
    );
  }

  /** The number of rows of {@code entity} with {@code label}, whatever their foreign key. */
  private static long rowsOf(final String entity, final String label) {
    return shop.inSession(session ->
      session
        .createQuery("select count(i) from " + entity + " i where i.label = :label", Long.class)
        .setParameter("label", label)
        .getSingleResult()
    );
  }

  private static Long seedCrate(final String... labels) {
    return shop.inSession(session -> {
      final var crate = new Crate();
      crate.setName("crate");
      for (final var label : labels) {
        final var bottle = new Bottle(label);
        bottle.setCrate(crate);
        crate.getBottles().add(bottle);
      }
      session.persist(crate);
      return crate.getId();
    });
  }

  private static Long seedRack(final String... labels) {
    return shop.inSession(session -> {
      final var rack = new Rack();
      rack.setName("rack");
      for (final var label : labels) rack.addJar(new Jar(label));
      session.persist(rack);
      return rack.getId();
    });
  }

  private static Long seedPallet(final String... labels) {
    return shop.inSession(session -> {
      final var pallet = new Pallet();
      pallet.setName("pallet");
      for (final var label : labels) {
        final var sack = new Sack(label);
        sack.setPallet(pallet);
        pallet.getSacks().add(sack);
      }
      session.persist(pallet);
      return pallet.getId();
    });
  }

  private static List<ItemForm> sentBottles(final Crate crate) {
    final var sent = new ArrayList<ItemForm>();
    for (final var bottle : crate.getBottles()) sent.add(new ItemForm(bottle.getId(), bottle.getLabel()));
    return sent;
  }

  private static List<ItemForm> sentJars(final Rack rack) {
    final var sent = new ArrayList<ItemForm>();
    for (final var jar : rack.getJars()) sent.add(new ItemForm(jar.getId(), jar.getLabel()));
    return sent;
  }

  private static List<ItemForm> sentSacks(final Pallet pallet) {
    final var sent = new ArrayList<ItemForm>();
    for (final var sack : pallet.getSacks()) sent.add(new ItemForm(sack.getId(), sack.getLabel()));
    return sent;
  }

  @Test
  @DisplayName("a new element of a list with no adder is pointed at its owner from mappedBy, in one INSERT")
  void aNewElementWithNoAdderCarriesItsKey() {
    for (final var path : paths(YardMappers.CRATE, CRATE).entrySet()) {
      final var id = seedCrate("red", "white");
      final var label = "rose-" + path.getKey();
      shop.withEntity(
        Crate.class,
        id,
        crate -> crate.getBottles().size(),
        crate -> {
          final var sent = sentBottles(crate);
          sent.add(new ItemForm(null, label));
          path.getValue().into(crate, new CrateForm(crate.getId(), crate.getName(), sent));
          for (final var bottle : crate.getBottles()) assertSame(crate, bottle.getCrate(), path.getKey());
          return null;
        }
      );
      assertEquals("1 INSERT, 0 UPDATE, 0 DELETE", entities(), path.getKey());
      assertEquals(List.of(id), keysOf("Bottle", "crate", label), path.getKey());
    }
  }

  @Test
  @DisplayName("an element taken out of a list with orphan removal is deleted, and nothing else is issued")
  void anOrphanIsDeletedAndNothingElse() {
    for (final var path : paths(YardMappers.CRATE, CRATE).entrySet()) {
      final var label = "white-" + path.getKey();
      final var id = seedCrate("red", label);
      shop.withEntity(
        Crate.class,
        id,
        crate -> crate.getBottles().size(),
        crate -> {
          final var sent = sentBottles(crate);
          sent.removeLast();
          path.getValue().into(crate, new CrateForm(crate.getId(), crate.getName(), sent));
          return null;
        }
      );
      assertEquals("0 INSERT, 0 UPDATE, 1 DELETE", entities(), path.getKey());
      assertEquals(0, rowsOf("Bottle", label), path.getKey());
    }
  }

  @Test
  @DisplayName("a new element of a list with an adder goes through it, in one INSERT carrying its key")
  void aNewElementThroughTheAdderCarriesItsKey() {
    for (final var path : paths(YardMappers.RACK, RACK).entrySet()) {
      final var id = seedRack("jam", "honey");
      final var label = "pickles-" + path.getKey();
      shop.withEntity(
        Rack.class,
        id,
        rack -> rack.getJars().size(),
        rack -> {
          final var sent = sentJars(rack);
          sent.add(new ItemForm(null, label));
          path.getValue().into(rack, new RackForm(rack.getId(), rack.getName(), sent));
          for (final var jar : rack.getJars()) assertSame(rack, jar.getRack(), path.getKey());
          return null;
        }
      );
      assertEquals("1 INSERT, 0 UPDATE, 0 DELETE", entities(), path.getKey());
      assertEquals(List.of(id), keysOf("Jar", "rack", label), path.getKey());
    }
  }

  @Test
  @DisplayName("an element taken out of a list with a remover goes through it, and is deleted with nothing else issued")
  void anElementTakenOutGoesThroughTheRemover() {
    for (final var path : paths(YardMappers.RACK, RACK).entrySet()) {
      final var label = "honey-" + path.getKey();
      final var id = seedRack("jam", label);
      final var dropped = shop.withEntity(
        Rack.class,
        id,
        rack -> rack.getJars().size(),
        rack -> {
          final var held = rack.getJars().getLast();
          final var sent = sentJars(rack);
          sent.removeLast();
          path.getValue().into(rack, new RackForm(rack.getId(), rack.getName(), sent));
          return held;
        }
      );
      assertNull(dropped.getRack(), path.getKey());
      assertEquals("0 INSERT, 0 UPDATE, 1 DELETE", entities(), path.getKey());
      assertEquals(0, rowsOf("Jar", label), path.getKey());
    }
  }

  @Test
  @DisplayName("an element taken out of a list with no orphan removal has its key cleared, in one UPDATE")
  void anElementTakenOutWithoutOrphanRemovalIsUnlinked() {
    for (final var path : paths(YardMappers.PALLET, PALLET).entrySet()) {
      final var label = "oats-" + path.getKey();
      final var id = seedPallet("rice", label);
      shop.withEntity(
        Pallet.class,
        id,
        pallet -> pallet.getSacks().size(),
        pallet -> {
          final var sent = sentSacks(pallet);
          sent.removeLast();
          path.getValue().into(pallet, new PalletForm(pallet.getId(), pallet.getName(), sent));
          return null;
        }
      );
      assertEquals("0 INSERT, 1 UPDATE, 0 DELETE", entities(), path.getKey());
      assertEquals(1, rowsOf("Sack", label), path.getKey());
      assertEquals(Collections.singletonList(null), keysOf("Sack", "pallet", label), path.getKey());
    }
  }

  @Test
  @DisplayName(
    "a unidirectional list without mappedBy sets no back-reference, even on an element's property of the owner's type"
  )
  void aUnidirectionalListSetsNoBackReference() {
    // Runtime only: a generated bridge refuses to leave the line's replacedFrom unpaired, since
    // nothing declares it a pointer at the owner.
    final Mapper<UOrderForm, UOrder> mapper = Telescope.mapper(UOrderForm.class, UOrder.class);
    final Long id = shop.inSession(session -> {
      final var order = new UOrder();
      order.setName("o");
      final var line = new ULine();
      line.setLabel("held");
      order.getLines().add(line);
      session.persist(order);
      return order.getId();
    });
    shop.withEntity(
      UOrder.class,
      id,
      order -> order.getLines().size(),
      order -> {
        final var sent = new ArrayList<ItemForm>();
        for (final var line : order.getLines()) sent.add(new ItemForm(line.getId(), line.getLabel()));
        sent.add(new ItemForm(null, "added"));
        mapper.into(order, new UOrderForm(order.getId(), order.getName(), sent));
        for (final var line : order.getLines()) assertNull(line.getReplacedFrom());
        return null;
      }
    );
    assertEquals("1 INSERT, 0 UPDATE, 0 DELETE", entities());
    assertEquals(Collections.singletonList(null), keysOf("ULine", "replacedFrom", "held"));
    assertEquals(Collections.singletonList(null), keysOf("ULine", "replacedFrom", "added"));
    assertEquals(
      List.of("UOrder.lines: refilled in place"),
      mapper.explain().collections().stream().map(Object::toString).toList()
    );
  }

  @Test
  @DisplayName("a PATCH that drops an element and adds one unlinks the first and links the second")
  void aPatchUnlinksAndLinks() {
    for (final var path : editPaths(YardMappers.PALLET_PATCH, PALLET_PATCH).entrySet()) {
      final var dropped = "corn-" + path.getKey();
      final var added = "barley-" + path.getKey();
      final var id = seedPallet("rice", dropped);
      shop.withEntity(
        Pallet.class,
        id,
        pallet -> pallet.getSacks().size(),
        pallet -> {
          final var sent = new ArrayList<ItemPatch>();
          for (final var sack : pallet.getSacks()) sent.add(new ItemPatch(sack.getId(), sack.getLabel()));
          sent.removeLast();
          sent.add(new ItemPatch(null, added));
          path.getValue().patchInto(pallet, new PalletPatch(null, null, sent));
          return null;
        }
      );
      assertEquals("1 INSERT, 1 UPDATE, 0 DELETE", entities(), path.getKey());
      assertEquals(Collections.singletonList(null), keysOf("Sack", "pallet", dropped), path.getKey());
      assertEquals(List.of(id), keysOf("Sack", "pallet", added), path.getKey());
    }
  }
}
