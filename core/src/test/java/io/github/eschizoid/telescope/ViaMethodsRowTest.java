package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.via;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.mapping.Mapping;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@code via} row converting through two methods: the classes the row converts between are read
 * from {@code forward}'s method, and the row converts each element of a container or the whole
 * property as that method takes.
 */
class ViaMethodsRowTest {

  public record Item(String name) {
    public ItemDto toDto() {
      return new ItemDto(name.toUpperCase());
    }
  }

  public record ItemDto(String label) {
    public Item toItem() {
      return new Item(label.toLowerCase());
    }
  }

  public record Box(List<Item> items) {}

  public record BoxDto(List<ItemDto> items) {}

  public record Keyed(Map<String, Item> items) {}

  public record Wrapped(Item item) {}

  public record KeyedDto(Map<String, Wrapped> items) {}

  public record Counted(int count) {}

  public record Counts2(List<Integer> counts) {}

  public record Counts2Dto(List<Long> counts) {}

  public record CountedDto(long count) {}

  public static final class Whole {

    private Whole() {}

    public static List<ItemDto> forward(final List<Item> items) {
      return List.of(new ItemDto("all:" + items.size()));
    }

    public static List<Item> backward(final List<ItemDto> items) {
      return List.of(new Item("back:" + items.size()));
    }
  }

  public static final class Counts {

    private Counts() {}

    public static long forward(final int count) {
      return count * 10L;
    }

    public static int backward(final long count) {
      return (int) (count / 10);
    }
  }

  @Test
  @DisplayName("an unbound instance method on each element converts a List, nulls left as they are")
  void anUnboundMethodConvertsEachElement() {
    final var mapper = Telescope.mapper(
      Box.class,
      BoxDto.class,
      via(Box::items, BoxDto::items, Item::toDto, ItemDto::toItem)
    );
    final var box = new Box(Arrays.asList(new Item("a"), null));
    final var dto = mapper.forward(box);
    assertEquals(new BoxDto(Arrays.asList(new ItemDto("A"), null)), dto);
    assertEquals(box, mapper.backward(dto));
  }

  @Test
  @DisplayName("a constructor reference converts the values of a Map")
  void aConstructorConvertsAMapsValues() {
    final var mapper = Telescope.mapper(
      Keyed.class,
      KeyedDto.class,
      via(Keyed::items, KeyedDto::items, Wrapped::new, Wrapped::item)
    );
    final var keyed = new Keyed(new TreeMap<>(Map.of("k", new Item("a"))));
    final var dto = mapper.forward(keyed);
    assertEquals(new KeyedDto(Map.of("k", new Wrapped(new Item("a")))), dto);
    assertEquals(keyed, mapper.backward(dto));
  }

  @Test
  @DisplayName("a forward taking the whole container converts the property, not each element")
  void aForwardTakingTheContainerConvertsTheProperty() {
    final var mapper = Telescope.mapper(
      Box.class,
      BoxDto.class,
      via(Box::items, BoxDto::items, Whole::forward, Whole::backward)
    );
    final var dto = mapper.forward(new Box(List.of(new Item("a"), new Item("b"))));
    assertEquals(new BoxDto(List.of(new ItemDto("all:2"))), dto);
    assertEquals(new Box(List.of(new Item("back:1"))), mapper.backward(dto));
  }

  @Test
  @DisplayName("a method taking and returning primitives converts a primitive property")
  void primitiveMethodsConvertAPrimitiveProperty() {
    final var mapper = Telescope.mapper(
      Counted.class,
      CountedDto.class,
      via(Counted::count, CountedDto::count, Counts::forward, Counts::backward)
    );
    assertEquals(new CountedDto(30L), mapper.forward(new Counted(3)));
    assertEquals(new Counted(3), mapper.backward(new CountedDto(30L)));
  }

  @Test
  @DisplayName("a method taking a primitive converts each element of a List of its wrapper")
  void aPrimitiveMethodConvertsEachWrappedElement() {
    final var mapper = Telescope.mapper(
      Counts2.class,
      Counts2Dto.class,
      via(Counts2::counts, Counts2Dto::counts, Counts::forward, Counts::backward)
    );
    final var dto = mapper.forward(new Counts2(Arrays.asList(1, null, 3)));
    assertEquals(new Counts2Dto(Arrays.asList(10L, null, 30L)), dto);
    assertEquals(new Counts2(Arrays.asList(1, null, 3)), mapper.backward(dto));
  }

  public static final class Inferred {

    private Inferred() {}

    @SuppressWarnings("unchecked")
    public static <D> D forward(final Item item) {
      return (D) item.toDto();
    }

    public static Item backward(final ItemDto dto) {
      return dto.toItem();
    }
  }

  @Test
  @DisplayName("a forward returning its own type variable converts each element into the target's element class")
  void anInferredResultConvertsEachElement() {
    final var mapper = Telescope.mapper(
      Box.class,
      BoxDto.class,
      via(Box::items, BoxDto::items, Inferred::forward, Inferred::backward)
    );
    final var box = new Box(Arrays.asList(new Item("a"), null));
    final var dto = mapper.forward(box);
    assertEquals(new BoxDto(Arrays.asList(new ItemDto("A"), null)), dto);
    assertEquals(box, mapper.backward(dto));
  }

  @Test
  @DisplayName("a lambda is refused by name, since the row reads the classes from a method reference")
  void aLambdaIsRefused() {
    final var row = via(Box::items, BoxDto::items, (Item i) -> i.toDto(), ItemDto::toItem);
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(Box.class, BoxDto.class, row)
    );
    assertTrue(refused.getMessage().contains("not a lambda"), refused::getMessage);
  }

  /** A class two subclasses implement, whose default method an unbound reference names. */
  public interface Shape {
    String name();

    default ShapeDto toDto() {
      return new ShapeDto(name(), getClass().getSimpleName());
    }
  }

  public record Circle(String name) implements Shape {}

  public record Square(String name) implements Shape {}

  public record ShapeDto(String name, String kind) {
    public Shape toShape() {
      return kind.equals("Circle") ? new Circle(name) : new Square(name);
    }

    public Circle toCircle() {
      return new Circle(name);
    }
  }

  public static final class Shapes2 {

    private Shapes2() {}

    public static ShapeDto forward(final Shape shape) {
      return shape.toDto();
    }

    public static Shape backward(final ShapeDto dto) {
      return dto.toShape();
    }

    public static Circle backwardToCircle(final ShapeDto dto) {
      return dto.toCircle();
    }
  }

  public record Shapes(List<Shape> shapes) {}

  public record ShapesDto(List<ShapeDto> shapes) {}

  private static final Shapes MIXED = new Shapes(List.of(new Circle("a"), new Square("b")));

  @Test
  @DisplayName(
    "a list of two subclasses converts through a static method and a default one, backward returning the interface"
  )
  void aMixedListConverts() {
    final var expected = new ShapesDto(List.of(new ShapeDto("a", "Circle"), new ShapeDto("b", "Square")));
    final var byStatic = Telescope.mapper(
      Shapes.class,
      ShapesDto.class,
      via(Shapes::shapes, ShapesDto::shapes, Shapes2::forward, Shapes2::backward)
    );
    assertEquals(expected, byStatic.forward(MIXED));
    assertEquals(MIXED, byStatic.backward(expected));
    final var byDefault = Telescope.mapper(
      Shapes.class,
      ShapesDto.class,
      via(Shapes::shapes, ShapesDto::shapes, Shape::toDto, ShapeDto::toShape)
    );
    assertEquals(expected, byDefault.forward(MIXED));
    assertEquals(MIXED, byDefault.backward(expected));
  }

  @Test
  @DisplayName("a backward returning one subclass narrows forward to it, and the row is refused by name, never cast")
  void aNarrowedForwardIsRefused() {
    for (final var row : List.<Mapping<Shapes, ShapesDto>>of(
      via(Shapes::shapes, ShapesDto::shapes, Shapes2::forward, Shapes2::backwardToCircle),
      via(Shapes::shapes, ShapesDto::shapes, Shape::toDto, ShapeDto::toCircle)
    )) {
      final var refused = assertThrows(IllegalArgumentException.class, () ->
        Telescope.mapper(Shapes.class, ShapesDto.class, row)
      );
      assertTrue(
        refused.getMessage().contains("taking only Circle, and the source's 'shapes' holds Shape"),
        refused::getMessage
      );
      assertTrue(
        refused
          .getMessage()
          .endsWith("which is not one; have backward return Shape, or name the class: via(src, tgt, Hw.class)"),
        refused::getMessage
      );
    }
  }

  /** A generic supertype whose inherited method returns its type variable. */
  public interface Carries<D> {
    D carried();

    default D dto() {
      return carried();
    }
  }

  public record Tagged(String name) implements Carries<TagDto> {
    @Override
    public TagDto carried() {
      return new TagDto(name);
    }
  }

  public record TagDto(String label) {
    public Tagged tagged() {
      return new Tagged(label);
    }
  }

  public record Tags(List<Tagged> tags) {}

  public record TagsDto(List<TagDto> tags) {}

  @Test
  @DisplayName("a method a generic supertype declares returns the class the receiver gives its variable")
  void aGenericSupertypesMethodReturnsTheReceiversClass() {
    final var mapper = Telescope.mapper(
      Tags.class,
      TagsDto.class,
      via(Tags::tags, TagsDto::tags, Tagged::dto, TagDto::tagged)
    );
    final var dto = mapper.forward(new Tags(List.of(new Tagged("a"))));
    assertEquals(new TagsDto(List.of(new TagDto("a"))), dto);
    assertEquals(new Tags(List.of(new Tagged("a"))), mapper.backward(dto));
  }

  /** A method returning its own class's type variable, which nothing at the call site names. */
  public record Holder<T>(T value) {
    public T get() {
      return value;
    }
  }

  public record Held(List<Holder<String>> held) {}

  public record HeldDto(List<String> held) {}

  public static Holder<String> hold(final String value) {
    return new Holder<>(value);
  }

  @Test
  @DisplayName("a method whose return a type variable hides is refused by name when the mapper is built")
  @SuppressWarnings({ "unchecked", "rawtypes" })
  void aHiddenReturnIsRefused() {
    final Telescope.Accessor raw = (Telescope.Accessor<Holder, Object>) Holder::get;
    final var row = via(
      Held::held,
      HeldDto::held,
      raw,
      (Telescope.Accessor<String, Holder<String>>) ViaMethodsRowTest::hold
    );
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(Held.class, HeldDto.class, row)
    );
    assertTrue(refused.getMessage().contains("returns, which a type variable of its class names"), refused::getMessage);
  }
}
