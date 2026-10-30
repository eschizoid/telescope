package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.drop;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A row reading a source path that steps through properties into a flat target property: a null
 * object on the way reads as the leaf's null value forward, and backward and patch build such an
 * object when the value written through it is not null.
 */
class NestedSourcePathTest {

  public record Address(String city, int zip) {}

  public record Customer(Address address, String name) {}

  public record Order(Customer customer, int qty) {}

  public record OrderDto(String city, int zip, int qty) {}

  public record Holder(Customer customer, int qty) {}

  public record HolderDto(String name, int qty) {}

  public record FanDto(String name, String alias, int qty) {}

  private static final Telescope<Order, String> CITY = Telescope.of(Order.class)
    .field(Order::customer)
    .field(Customer::address)
    .field(Address::city);
  private static final Telescope<Order, Integer> ZIP = Telescope.of(Order.class)
    .field(Order::customer)
    .field(Customer::address)
    .field(Address::zip);

  private static Telescope<Holder, String> name() {
    return Telescope.of(Holder.class).field(Holder::customer).field(Customer::name);
  }

  @Test
  @DisplayName("forward reads a null object on a path of three hops as the leaf's null value, a primitive's default")
  void forwardReadsANullObjectAsTheNullValue() {
    final var mapper = Telescope.mapper(Order.class, OrderDto.class, to(CITY, OrderDto::city), to(ZIP, OrderDto::zip));
    assertEquals(new OrderDto("Paris", 75, 3), mapper.forward(new Order(new Customer(new Address("Paris", 75), "n"), 3)));
    assertEquals(new OrderDto(null, 0, 3), mapper.forward(new Order(null, 3)));
    assertEquals(new OrderDto(null, 0, 3), mapper.forward(new Order(new Customer(null, "n"), 3)));
  }

  @Test
  @DisplayName("backward builds each null object on a path the value is written through, with its other members at their null value")
  void backwardBuildsTheObjectsOnThePath() {
    final var mapper = Telescope.mapper(Order.class, OrderDto.class, to(CITY, OrderDto::city), to(ZIP, OrderDto::zip));
    assertEquals(
      new Order(new Customer(new Address("Paris", 75), null), 3),
      mapper.backward(new OrderDto("Paris", 75, 3))
    );
    final var twoHops = Telescope.mapper(Holder.class, HolderDto.class, to(name(), HolderDto::name));
    assertEquals(new Holder(new Customer(null, "x"), 1), twoHops.backward(new HolderDto("x", 1)));
  }

  @Test
  @DisplayName("backward leaves a null object null when the value written through it is null")
  void backwardLeavesANullObjectForANullValue() {
    final var twoHops = Telescope.mapper(Holder.class, HolderDto.class, to(name(), HolderDto::name));
    assertEquals(new Holder(null, 1), twoHops.backward(new HolderDto(null, 1)));
    assertEquals(twoHops.backward(twoHops.forward(new Holder(null, 4))), new Holder(null, 4));
  }

  @Test
  @DisplayName("patch writes a partial's value through the path, building a null object, and leaves the path for a null value")
  void patchWritesThroughThePath() {
    final var mapper = Telescope.mapper(Order.class, OrderDto.class, to(CITY, OrderDto::city), to(ZIP, OrderDto::zip));
    assertEquals(
      new Order(new Customer(new Address("Rome", 5), null), 3),
      mapper.patch(new Order(null, 1), new OrderDto("Rome", 5, 3))
    );
    assertEquals(
      new Order(new Customer(new Address("Paris", 5), "n"), 3),
      mapper.patch(new Order(new Customer(new Address("Paris", 75), "n"), 1), new OrderDto(null, 5, 3))
    );
    final var twoHops = Telescope.mapper(Holder.class, HolderDto.class, to(name(), HolderDto::name));
    assertEquals(new Holder(null, 2), twoHops.patch(new Holder(null, 1), new HolderDto(null, 2)));
  }

  public static class Line {

    private String sku;
    private int count;

    public String getSku() {
      return sku;
    }

    public void setSku(final String sku) {
      this.sku = sku;
    }

    public int getCount() {
      return count;
    }

    public void setCount(final int count) {
      this.count = count;
    }
  }

  public static class Cart {

    private Line line;
    private int total;

    public Line getLine() {
      return line;
    }

    public void setLine(final Line line) {
      this.line = line;
    }

    public int getTotal() {
      return total;
    }

    public void setTotal(final int total) {
      this.total = total;
    }
  }

  public static class CartDto {

    private int count = 99;
    private int total;

    public int getCount() {
      return count;
    }

    public void setCount(final int count) {
      this.count = count;
    }

    public int getTotal() {
      return total;
    }

    public void setTotal(final int total) {
      this.total = total;
    }
  }

  @Test
  @DisplayName("a null object on a bean path leaves a primitive setter of the target unwritten, and backward builds the bean")
  void beanPathLeavesAPrimitiveSetterUnwritten() {
    final var mapper = Telescope.mapper(
      Cart.class,
      CartDto.class,
      to(Telescope.ofBean(Cart.class).field(Cart::getLine).field(Line::getCount), CartDto::getCount)
    );
    final var cart = new Cart();
    cart.setTotal(2);
    final var dto = mapper.forward(cart);
    assertEquals(List.of(99, 2), List.of(dto.getCount(), dto.getTotal()));
    final var back = mapper.backward(dto);
    assertEquals(List.of(99, 2), List.of(back.getLine().getCount(), back.getTotal()));
    assertNull(back.getLine().getSku());
  }

  public interface Shape {
    String label();
  }

  public record Framed(Shape shape, int qty) {}

  public record FramedDto(String label, int qty) {}

  @Test
  @DisplayName("a two-way mapper refuses a path through a class no write strategy builds, and a forward-only one reads it")
  void pathThroughAnUnbuildableClassIsRefusedBothWays() {
    final var path = Telescope.of(Framed.class).field(Framed::shape).field(Shape::label);
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(Framed.class, FramedDto.class, to(path, FramedDto::label))
    );
    assertEquals(
      PairingMessages.pathIntermediateUnbuildable("Framed", "FramedDto", "shape.label", "Shape"),
      refused.getMessage()
    );
    final var forward = Telescope.mapperForward(Framed.class, FramedDto.class, to(path, FramedDto::label));
    assertEquals(new FramedDto("s", 1), forward.forward(new Framed(() -> "s", 1)));
  }

  @Test
  @DisplayName("a path written into two targets of a two-way mapper, or read through a dropped property, is refused by name")
  void pathFanOutAndDropAreRefused() {
    final var fannedOut = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(Holder.class, FanDto.class, to(name(), FanDto::name), to(name(), FanDto::alias))
    );
    assertEquals(
      PairingMessages.fanOutBothWays("Holder", "FanDto", "customer.name", List.of("name", "alias")),
      fannedOut.getMessage()
    );
    final var forwardOnly = Telescope.mapperForward(
      Holder.class,
      FanDto.class,
      to(name(), FanDto::name),
      to(name(), FanDto::alias)
    );
    assertEquals(new FanDto("n", "n", 1), forwardOnly.forward(new Holder(new Customer(null, "n"), 1)));
    final var dropped = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(Holder.class, HolderDto.class, drop(Holder::customer), to(name(), HolderDto::name))
    );
    assertEquals(
      PairingMessages.droppedAndReadThrough("Holder", "HolderDto", "customer", "customer.name"),
      dropped.getMessage()
    );
  }
}
