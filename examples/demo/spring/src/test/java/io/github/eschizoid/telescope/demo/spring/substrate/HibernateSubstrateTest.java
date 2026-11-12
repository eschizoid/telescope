package io.github.eschizoid.telescope.demo.spring.substrate;

import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.SETTERS;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBeans;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.internal.Beans;
import io.github.eschizoid.telescope.internal.MetadataHolderProbe;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.hibernate.Hibernate;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.collection.spi.PersistentBag;
import org.hibernate.collection.spi.PersistentMap;
import org.hibernate.collection.spi.PersistentSet;
import org.hibernate.proxy.HibernateProxy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Telescope's runtime path over Hibernate's own classes: proxies, {@code PersistentBag}, {@code
 * PersistentSet}, {@code PersistentMap} and a value cycle Hibernate links. The order service's
 * entity mappers are generated bridges, which never reach this code, so these checks run over small
 * entities of their own, in a Hibernate session with no Spring around it. This module is the only
 * one in the build with Hibernate on its classpath.
 */
class HibernateSubstrateTest {

  private static SessionFactory sessions;

  @BeforeAll
  static void open() {
    final var registry = new StandardServiceRegistryBuilder()
      .applySetting("hibernate.connection.url", "jdbc:h2:mem:substrate;DB_CLOSE_DELAY=-1")
      .applySetting("hibernate.hbm2ddl.auto", "create-drop")
      .build();
    sessions = new MetadataSources(registry)
      .addAnnotatedClass(Employee.class)
      .addAnnotatedClass(Shopper.class)
      .buildMetadata()
      .buildSessionFactory();
  }

  @AfterAll
  static void close() {
    sessions.close();
  }

  private static <T> T inSession(final Function<Session, T> work) {
    return sessions.fromTransaction(work::apply);
  }

  private static Long saveShopper() {
    return inSession(session -> {
      final var shopper = new Shopper();
      shopper.setName("Ada");
      shopper.getTags().addAll(List.of("vip", "newsletter"));
      shopper.getPreferences().putAll(Map.of("size", "M", "colour", "teal"));
      session.persist(shopper);
      return shopper.getId();
    });
  }

  @Test
  @DisplayName("a proxy unwraps to its persistent class, so the probe finds the holder generated for that class")
  void aProxyUnwrapsToItsPersistentClass() {
    final var id = saveShopper();
    inSession(session -> {
      final var proxy = session.getReference(Shopper.class, id);
      assertThat(proxy).isInstanceOf(HibernateProxy.class);
      assertThat(Hibernate.isInitialized(proxy)).isFalse();
      assertThat(Beans.persistentClassOf(proxy)).isEqualTo(Shopper.class);
      assertThat(Hibernate.isInitialized(proxy)).as("unwrapping does not load the row").isFalse();
      assertThat(MetadataHolderProbe.probeFor(Beans.persistentClassOf(proxy))).isPresent();
      assertThat(MetadataHolderProbe.probeFor(proxy.getClass())).as("no holder for the proxy subclass").isEmpty();
      return null;
    });
  }

  @Test
  @DisplayName("backward over a loaded PersistentSet and PersistentMap gives the values they hold")
  void backwardOverPersistentCollections() {
    final var id = saveShopper();
    final var mapper = Telescope.mapper(ShopperRecord.class, Shopper.class, writeBeans(SETTERS));
    final var record = inSession(session -> {
      final var loaded = session.find(Shopper.class, id);
      assertThat(loaded.getTags()).isInstanceOf(PersistentSet.class);
      assertThat(loaded.getPreferences()).isInstanceOf(PersistentMap.class);
      return mapper.backward(loaded);
    });
    assertThat(record.tags()).containsExactlyInAnyOrder("vip", "newsletter");
    assertThat(record.preferences()).containsExactlyInAnyOrderEntriesOf(Map.of("size", "M", "colour", "teal"));
    assertThat(record.tags()).isNotInstanceOf(PersistentSet.class);
  }

  @Test
  @DisplayName("an empty PersistentSet and PersistentMap read back as empty, not null")
  void emptyPersistentCollectionsReadBackEmpty() {
    final var id = inSession(session -> {
      final var shopper = new Shopper();
      shopper.setName("Bea");
      session.persist(shopper);
      return shopper.getId();
    });
    final var mapper = Telescope.mapper(ShopperRecord.class, Shopper.class, writeBeans(SETTERS));
    final var record = inSession(session -> mapper.backward(session.find(Shopper.class, id)));
    assertThat(record.tags()).isEmpty();
    assertThat(record.preferences()).isEmpty();
  }

  @Test
  @DisplayName("a value cycle across a PersistentBag back-edge is cut, and the cut manager lifts to Optional.empty()")
  void aValueCycleAcrossAPersistentBagIsCut() {
    final var aliceId = inSession(session -> {
      final var alice = new Employee("Alice");
      session.persist(alice);
      final var bob = new Employee("Bob");
      bob.setManager(alice);
      session.persist(bob);
      final var carol = new Employee("Carol");
      carol.setManager(bob);
      session.persist(carol);
      return alice.getId();
    });
    final var mapper = Telescope.mapper(EmployeeRecord.class, Employee.class, writeBeans(SETTERS));
    final var alice = inSession(session -> {
      session.clear();
      final var loaded = session.find(Employee.class, aliceId);
      // Hibernate links both directions: alice's reports hold bob, whose manager is alice,
      // so the
      // graph it hands over is a cycle of values.
      final var bob = loaded.getReports().get(0);
      assertThat(loaded.getReports()).isInstanceOf(PersistentBag.class);
      assertThat(bob.getManager()).isSameAs(loaded);
      assertThat(bob.getReports()).hasSize(1);
      return mapper.backward(loaded);
    });
    assertThat(alice.name()).isEqualTo("Alice");
    assertThat(alice.manager()).isEmpty();
    final var bob = alice.reports().get(0);
    assertThat(bob.name()).isEqualTo("Bob");
    assertThat(bob.manager()).as("the back-edge to alice is cut").isEqualTo(Optional.empty());
    final var carol = bob.reports().get(0);
    assertThat(carol.name()).isEqualTo("Carol");
    assertThat(carol.manager()).as("the back-edge to bob is cut").isEqualTo(Optional.empty());
    assertThat(carol.reports()).isEmpty();
  }

  @Test
  @DisplayName("a path hops from records to beans through a sealed bridge, narrows to a case and reads a bean field")
  void aPathNarrowsAfterASealedBridgeHop() {
    final var purchase = new Purchase("P-1", new CardTender("4111111111111111", "Ada"));
    final Telescope<Purchase, String> cardNumber = Telescope.of(Purchase.class)
      .field(Purchase::tender)
      .then(TenderBridge.BRIDGE)
      .as(CardTenderEntity.class)
      .field(CardTenderEntity::getNumber);

    assertThat(cardNumber.find(purchase)).contains("4111111111111111");
    final var masked = cardNumber.update(purchase, number -> "**** " + number.substring(number.length() - 4));
    assertThat(masked.tender()).isEqualTo(new CardTender("**** 1111", "Ada"));

    final var cashCurrency = Telescope.of(Purchase.class)
      .field(Purchase::tender)
      .then(TenderBridge.BRIDGE)
      .as(CashTenderEntity.class)
      .field(CashTenderEntity::getCurrency);
    assertThat(cashCurrency.find(purchase)).isEmpty();
    assertThat(cashCurrency.update(purchase, currency -> "EUR")).isEqualTo(purchase);
    assertThat(TenderBridge.BRIDGE.read(new CashTender("GBP"))).isInstanceOf(CashTenderEntity.class);
  }
}
