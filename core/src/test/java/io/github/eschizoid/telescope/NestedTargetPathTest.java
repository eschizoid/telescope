package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A row writing a flat source property into the target through a path that steps through
 * properties: forward builds a null object on the way when the value is not null, and backward and
 * patch read a null object on the way as the leaf's null value.
 */
class NestedTargetPathTest {

  public record Address(String city, int zip) {}

  public record Customer(Address address, String name) {}

  public record Order(Customer customer, int qty) {}

  public record OrderDto(String city, int zip, int qty) {}

  public record Phone(String number) {}

  public record Contact(Phone phone, String name) {}

  public record Holder(Contact contact, int qty) {}

  public record Flat(String name, int qty) {}

  private static final Telescope<Order, String> CITY = Telescope.of(Order.class)
    .field(Order::customer)
    .field(Customer::address)
    .field(Address::city);
  private static final Telescope<Order, Integer> ZIP = Telescope.of(Order.class)
    .field(Order::customer)
    .field(Customer::address)
    .field(Address::zip);

  private static Telescope<Holder, String> name() {
    return Telescope.of(Holder.class).field(Holder::contact).field(Contact::name);
  }

  @Test
  @DisplayName("forward builds each null object on a path of three steps, with its other members at their null value")
  void forwardBuildsTheObjectsOnThePath() {
    final var mapper = Telescope.mapper(OrderDto.class, Order.class, to(OrderDto::city, CITY), to(OrderDto::zip, ZIP));
    assertEquals(
      new Order(new Customer(new Address("Paris", 75), null), 3),
      mapper.forward(new OrderDto("Paris", 75, 3))
    );
  }

  @Test
  @DisplayName("forward leaves the object on the path null when the value written through it is null")
  void forwardLeavesANullObjectForANullValue() {
    final var mapper = Telescope.mapper(Flat.class, Holder.class, to(Flat::name, name()));
    assertEquals(new Holder(null, 2), mapper.forward(new Flat(null, 2)));
    assertEquals(new Holder(new Contact(null, "n"), 2), mapper.forward(new Flat("n", 2)));
    assertEquals(new Flat(null, 2), mapper.backward(mapper.forward(new Flat(null, 2))));
  }

  @Test
  @DisplayName("backward reads a null object on the path as the leaf's null value, a primitive's default")
  void backwardReadsANullObjectAsTheNullValue() {
    final var mapper = Telescope.mapper(OrderDto.class, Order.class, to(OrderDto::city, CITY), to(OrderDto::zip, ZIP));
    assertEquals(new OrderDto("R", 5, 1), mapper.backward(new Order(new Customer(new Address("R", 5), "n"), 1)));
    assertEquals(new OrderDto(null, 0, 1), mapper.backward(new Order(null, 1)));
    assertEquals(new OrderDto(null, 0, 1), mapper.backward(new Order(new Customer(null, "n"), 1)));
  }

  @Test
  @DisplayName("patch takes a partial's value through the path, and keeps the base's where the path reads null")
  void patchReadsThroughThePath() {
    final var mapper = Telescope.mapper(OrderDto.class, Order.class, to(OrderDto::city, CITY), to(OrderDto::zip, ZIP));
    assertEquals(
      new OrderDto("R", 5, 1),
      mapper.patch(new OrderDto("P", 75, 3), new Order(new Customer(new Address("R", 5), "n"), 1))
    );
    assertEquals(new OrderDto("P", 75, 1), mapper.patch(new OrderDto("P", 75, 3), new Order(null, 1)));
  }

  @Test
  @DisplayName("into writes a target property a path fills, and skips the patch entry that reads through the path")
  void intoWritesTheTargetsOwnProperties() {
    final var mapper = Telescope.mapper(
      Flat.class,
      Box.class,
      to(Flat::name, Telescope.ofBean(Box.class).field(Box::getContact).field(Contact::name))
    );
    final var box = new Box();
    mapper.into(box, new Flat("n", 4));
    assertEquals(List.of(new Contact(null, "n"), 4), List.of(box.getContact(), box.getQty()));
  }

  public static class Box {

    private Contact contact;
    private int qty;

    public Contact getContact() {
      return contact;
    }

    public void setContact(final Contact contact) {
      this.contact = contact;
    }

    public int getQty() {
      return qty;
    }

    public void setQty(final int qty) {
      this.qty = qty;
    }
  }

  public interface Shape {
    String getLabel();
  }

  public record Framed(Shape shape, int qty) {}

  @Test
  @DisplayName("any mapper refuses a target path through a class no write strategy builds")
  void targetPathThroughAnUnbuildableClassIsRefused() {
    final var path = Telescope.of(Framed.class).field(Framed::shape).field(Shape::getLabel);
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapperForward(Flat.class, Framed.class, to(Flat::name, path))
    );
    assertEquals(
      PairingMessages.pathIntermediateUnbuildable("Flat", "Framed", "shape.label", "Shape"),
      refused.getMessage()
    );
  }

  @Test
  @DisplayName("a source property written into two target paths by a two-way mapper is refused by name")
  void fanOutIntoTwoTargetPathsIsRefused() {
    final var other = Telescope.of(Holder.class).field(Holder::contact).field(Contact::phone).field(Phone::number);
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(Flat.class, Holder.class, to(Flat::name, name()), to(Flat::name, other))
    );
    assertEquals(
      PairingMessages.fanOutBothWays("Flat", "Holder", "name", List.of("contact.name", "contact.phone.number")),
      refused.getMessage()
    );
    final var forward = Telescope.mapperForward(
      Flat.class,
      Holder.class,
      to(Flat::name, name()),
      to(Flat::name, other)
    );
    assertEquals(new Holder(new Contact(new Phone("n"), "n"), 1), forward.forward(new Flat("n", 1)));
    assertNull(forward.forward(new Flat(null, 1)).contact());
  }
}
