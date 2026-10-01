package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.MapExtractStep.extract;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A POJO whose only write path is its private fields — a no-arg constructor and getters, with no
 * public setter, no static {@code builder()} and no other constructor — is refused by every runtime
 * entry point that has to build one, with a message that names the class and the fix. Reading needs
 * no writer, so an {@code ofBean} path still reads such a class, directly or as an intermediate
 * hop, and only a write through it is refused. The {@code @Bridge} processor refuses the same
 * shape; {@code BeanRebuildCorpusTest} in the codegen module pins that both paths refuse it.
 */
class PrivateFieldsOnlyRefusalTest {

  /** Writable only by injecting into {@code name}: nothing the class declares assigns it. */
  static final class FieldsOnly {

    private String name;

    public FieldsOnly() {}

    public String getName() {
      return name;
    }
  }

  record Named(String name) {}

  /** A read-only entity: protected no-arg constructor, getters only. */
  static final class ReadOnlyCustomer {

    private String name;

    protected ReadOnlyCustomer() {}

    static ReadOnlyCustomer named(final String name) {
      final var c = new ReadOnlyCustomer();
      c.name = name;
      return c;
    }

    public String getName() {
      return name;
    }
  }

  /** A read-only entity holding another, so a read crosses one as an intermediate hop. */
  static final class ReadOnlyOrder {

    private ReadOnlyCustomer customer;

    protected ReadOnlyOrder() {}

    static ReadOnlyOrder of(final ReadOnlyCustomer customer) {
      final var o = new ReadOnlyOrder();
      o.customer = customer;
      return o;
    }

    public ReadOnlyCustomer getCustomer() {
      return customer;
    }
  }

  private static void assertNamesClassAndFix(final IllegalStateException ex) {
    assertNamesClassAndFix(ex, FieldsOnly.class);
  }

  private static void assertNamesClassAndFix(final IllegalStateException ex, final Class<?> refused) {
    final var message = ex.getMessage();
    assertTrue(message.contains(refused.getName()), message);
    assertTrue(message.contains("does not write private fields"), message);
    assertTrue(message.contains("Add public setters, an all-args constructor"), message);
    assertTrue(message.contains("static builder()"), message);
    assertTrue(message.contains("explicit Mapping row"), message);
  }

  @Test
  @DisplayName("Telescope.mapper refuses a target writable only through its private fields")
  void mapperRefuses() {
    final var ex = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(Named.class, FieldsOnly.class).forward(new Named("alice"))
    );
    assertNamesClassAndFix(ex);
  }

  @Test
  @DisplayName("Telescope.fromMap refuses a target writable only through its private fields")
  void fromMapRefuses() {
    final var ex = assertThrows(IllegalStateException.class, () ->
      Telescope.fromMap(FieldsOnly.class, extract("name", FieldsOnly::getName, Object::toString)).forward(
        Map.of("name", "alice")
      )
    );
    assertNamesClassAndFix(ex);
  }

  @Test
  @DisplayName("an ofBean path refuses to write a POJO writable only through its private fields")
  void ofBeanWriteRefuses() {
    final var ex = assertThrows(IllegalStateException.class, () ->
      Telescope.ofBean(FieldsOnly.class).field(FieldsOnly::getName).set(new FieldsOnly(), "alice")
    );
    assertNamesClassAndFix(ex);
  }

  @Test
  @DisplayName("an ofBean path reads a read-only entity directly")
  void ofBeanReadsReadOnlyEntity() {
    final var customer = ReadOnlyCustomer.named("alice");

    assertEquals("alice", Telescope.ofBean(ReadOnlyCustomer.class).field(ReadOnlyCustomer::getName).read(customer));
    assertEquals("alice", Telescope.ofBean(ReadOnlyCustomer.class).fieldByName("name").read(customer));
  }

  @Test
  @DisplayName("an ofBean path reads through a read-only entity as an intermediate hop")
  void ofBeanReadsThroughReadOnlyHop() {
    final var order = ReadOnlyOrder.of(ReadOnlyCustomer.named("alice"));

    final var name = Telescope.ofBean(ReadOnlyOrder.class)
      .field(ReadOnlyOrder::getCustomer)
      .field(ReadOnlyCustomer::getName)
      .read(order);

    assertEquals("alice", name);
  }

  @Test
  @DisplayName("an ofBean write through a read-only entity is refused with the message")
  void ofBeanWriteThroughReadOnlyHopRefuses() {
    final var order = ReadOnlyOrder.of(ReadOnlyCustomer.named("alice"));
    final var path = Telescope.ofBean(ReadOnlyOrder.class)
      .field(ReadOnlyOrder::getCustomer)
      .field(ReadOnlyCustomer::getName);

    final var ex = assertThrows(IllegalStateException.class, () -> path.set(order, "bob"));
    assertNamesClassAndFix(ex, ReadOnlyCustomer.class);
  }
}
