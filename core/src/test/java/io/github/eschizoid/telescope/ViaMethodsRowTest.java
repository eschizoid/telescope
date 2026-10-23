package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.via;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

  @Test
  @DisplayName("a lambda is refused by name, since the row reads the classes from a method reference")
  void aLambdaIsRefused() {
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      via(Box::items, BoxDto::items, (Item i) -> i.toDto(), ItemDto::toItem)
    );
    assertTrue(refused.getMessage().contains("not a lambda"), refused::getMessage);
  }
}
