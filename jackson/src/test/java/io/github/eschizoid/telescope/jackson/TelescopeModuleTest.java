package io.github.eschizoid.telescope.jackson;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.json.JsonMapper;

class TelescopeModuleTest {

  /** A contact as the API writes it. */
  public record ContactWire(String fullName, String mail) {}

  /** A contact as the service holds it. */
  public record Contact(String name, String email) {}

  /** A type the module does not map, holding the domain type at several depths. */
  public record Directory(List<Contact> contacts, Map<String, Contact> byId, Contact owner) {}

  /** A wire type that itself holds a domain type. */
  public record TeamWire(String title, Contact lead) {}

  /** The domain type {@link TeamWire} is read into. */
  public record Team(String name, Contact lead) {}

  /** A domain interface. */
  public interface Animal {}

  /** The wire shape of an {@link Animal}. */
  public record AnimalWire(String kind) {}

  /** An {@link Animal}. */
  public record Dog(String kind) implements Animal {}

  /** A domain class that a subclass extends, as a persistence proxy extends its entity. */
  public static class Account {

    private final String id;

    public Account(final String id) {
      this.id = id;
    }

    public String getId() {
      return id;
    }
  }

  /** A subclass of the domain class. */
  public static final class AccountProxy extends Account {

    public AccountProxy(final String id) {
      super(id);
    }
  }

  /** The wire shape of an {@link Account}. */
  public record AccountWire(String ref) {}

  /** A status, which as a domain type Jackson would read through a lookup the module skips. */
  public enum Level {
    LOW,
  }

  /** A domain type with a creator of its own. */
  public record Created(String value) {
    @JsonCreator
    public static Created of(final String value) {
      return new Created(value);
    }
  }

  /** A domain type naming its own deserializer settings. */
  @JsonDeserialize
  public record Configured(String value) {}

  private static final AtomicInteger FORWARDS = new AtomicInteger();

  private static final Mapper<ContactWire, Contact> CONTACTS = Telescope.mapper(
    ContactWire.class,
    Contact.class,
    to(ContactWire::fullName, Contact::name),
    to(ContactWire::mail, Contact::email)
  ).beforeForward(wire -> {
    FORWARDS.incrementAndGet();
    return wire;
  });

  private static final Mapper<TeamWire, Team> TEAMS = Telescope.mapper(
    TeamWire.class,
    Team.class,
    to(TeamWire::title, Team::name)
  );

  private static final Mapper<AnimalWire, Animal> ANIMALS = Mapper.create(
    wire -> new Dog(wire.kind()),
    animal -> new AnimalWire(((Dog) animal).kind()),
    AnimalWire.class,
    Animal.class,
    Map.of()
  );

  private static final Mapper<AccountWire, Account> ACCOUNTS = Mapper.create(
    wire -> new Account(wire.ref()),
    account -> new AccountWire(account.getId()),
    AccountWire.class,
    Account.class,
    Map.of()
  );

  private static JsonMapper json(final TelescopeModule... modules) {
    final var builder = JsonMapper.builder();
    for (final var module : modules) {
      builder.addModule(module);
    }
    return builder.build();
  }

  private static <W, D> Mapper<W, D> plain(final Class<W> wire, final Class<D> domain) {
    return Mapper.create(w -> null, d -> null, wire, domain, Map.of());
  }

  private static String refusal(final Mapper<?, ?>... mappers) {
    return assertThrows(IllegalStateException.class, () -> TelescopeModule.of(mappers)).getMessage();
  }

  @Nested
  class Registered {

    @Test
    @DisplayName("a domain type reads from its wire shape and writes back to it")
    void readsAndWritesThroughTheMapper() {
      final var mapper = json(TelescopeModule.of(CONTACTS));
      final var contact = mapper.readValue("{\"fullName\":\"Ada\",\"mail\":\"ada@x\"}", Contact.class);
      assertEquals(new Contact("Ada", "ada@x"), contact);
      assertEquals("{\"fullName\":\"Ada\",\"mail\":\"ada@x\"}", mapper.writeValueAsString(contact));
    }

    @Test
    @DisplayName("a domain type maps at every depth: in a list, a map, and a type the module does not map")
    void mapsAtEveryDepth() {
      final var mapper = json(TelescopeModule.of(CONTACTS));
      final var body =
        "{\"contacts\":[{\"fullName\":\"A\",\"mail\":\"a\"}],\"byId\":{\"k\":{\"fullName\":\"B\",\"mail\":\"b\"}}," +
        "\"owner\":{\"fullName\":\"C\",\"mail\":\"c\"}}";
      final var directory = mapper.readValue(body, Directory.class);
      assertEquals(
        new Directory(List.of(new Contact("A", "a")), Map.of("k", new Contact("B", "b")), new Contact("C", "c")),
        directory
      );
      assertEquals(body, mapper.writeValueAsString(directory));
    }

    @Test
    @DisplayName("a wire type that holds a domain type reads that domain type through its own mapper")
    void wireHoldingADomainType() {
      final var mapper = json(TelescopeModule.of(CONTACTS, TEAMS));
      final var body = "{\"title\":\"core\",\"lead\":{\"fullName\":\"Ada\",\"mail\":\"a\"}}";
      final var team = mapper.readValue(body, Team.class);
      assertEquals(new Team("core", new Contact("Ada", "a")), team);
      assertEquals(body, mapper.writeValueAsString(team));
    }

    @Test
    @DisplayName("a JSON null never reaches the mapper")
    void nullNeverReachesTheMapper() {
      final var mapper = json(TelescopeModule.of(CONTACTS));
      final var before = FORWARDS.get();
      assertNull(mapper.readValue("{\"owner\":null}", Directory.class).owner());
      assertEquals(before, FORWARDS.get());
    }

    @Test
    @DisplayName("a value is written through the registration of its superclass or an interface it implements")
    void writesThroughASupertype() {
      final var mapper = json(TelescopeModule.of(ANIMALS, ACCOUNTS));
      assertEquals("{\"kind\":\"dog\"}", mapper.writeValueAsString(new Dog("dog")));
      assertEquals("{\"ref\":\"acct-1\"}", mapper.writeValueAsString(new AccountProxy("acct-1")));
      assertEquals(new Dog("cat"), mapper.readValue("{\"kind\":\"cat\"}", Animal.class));
    }

    @Test
    @DisplayName("two modules on one JSON mapper are both installed")
    void twoModulesAreBothInstalled() {
      final var mapper = json(TelescopeModule.of(CONTACTS), TelescopeModule.of(ANIMALS));
      assertEquals(new Contact("A", "a"), mapper.readValue("{\"fullName\":\"A\",\"mail\":\"a\"}", Contact.class));
      assertEquals(new Dog("cat"), mapper.readValue("{\"kind\":\"cat\"}", Animal.class));
    }

    @Test
    @DisplayName("the builder carries exactly the mappers it is given")
    void builderCarriesWhatItIsGiven() {
      final var module = TelescopeModule.builder().add(CONTACTS).addAll(List.of(TEAMS)).build();
      assertEquals(List.of(CONTACTS, TEAMS), module.mappers());
      assertEquals("telescope", module.getModuleName());
      assertEquals("TelescopeModule[Contact, Team]", module.toString());
      assertTrue(TelescopeModule.of().mappers().isEmpty());
    }
  }

  @Nested
  class Refused {

    @Test
    @DisplayName("a lifted mapper is refused, since it would take over every list")
    void liftedMapper() {
      assertTrue(refusal(CONTACTS.liftList()).contains("converts a container, which a lifted mapper"));
    }

    @Test
    @DisplayName("a mapper between one type and itself is refused")
    void sameType() {
      assertTrue(refusal(plain(Contact.class, Contact.class)).contains("reads and writes one type"));
    }

    @Test
    @DisplayName("an enum domain type is refused, since Jackson would pass the mapper over")
    void enumDomain() {
      assertTrue(refusal(plain(String.class, Level.class)).contains("has an enum for its domain type"));
    }

    @Test
    @DisplayName("a domain type carrying Jackson's own reading or writing settings is refused")
    void annotatedDomain() {
      assertTrue(refusal(plain(String.class, Created.class)).contains("carries @JsonCreator on"));
      assertTrue(refusal(plain(String.class, Configured.class)).contains("carries @JsonDeserialize"));
    }

    @Test
    @DisplayName("two mappers sharing a domain type are refused")
    void sharedDomain() {
      assertEquals(
        "TelescopeModule: the mapper ContactWire → Contact and the mapper String → Contact both have Contact for" +
          " their domain type, and one JSON mapper reads a type one way. Give each its own JsonMapper.",
        refusal(CONTACTS, plain(String.class, Contact.class))
      );
    }

    @Test
    @DisplayName("a wire type that is, or extends, a registered domain type is refused")
    void wireIsADomain() {
      assertTrue(refusal(CONTACTS, plain(Contact.class, Team.class)).contains("is Contact, the domain type of"));
      assertTrue(refusal(plain(AccountProxy.class, Account.class)).contains("is an Account, the domain type of"));
    }
  }
}
