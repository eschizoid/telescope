package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.via;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@code via} row naming a class: the runtime path chooses the class's {@code forward} and {@code
 * backward} for the property's types, as the {@code @Bridge} processor chooses them for a
 * {@code @ViaMapper}, and refuses by name where it cannot choose.
 */
class ViaClassRowTest {

  public interface Shape {
    String name();
  }

  public interface Labelled {
    String name();
  }

  public record Circle(String name) implements Shape, Labelled {}

  public record ShapeDto(String name) {}

  public record Circles(List<Circle> shapes) {}

  public record CirclesDto(List<ShapeDto> shapes) {}

  public static final class ByShape {

    private ByShape() {}

    public static ShapeDto forward(final Shape shape) {
      return new ShapeDto(shape.name().toUpperCase());
    }

    public static Circle backward(final ShapeDto dto) {
      return new Circle(dto.name().toLowerCase());
    }
  }

  @Test
  @DisplayName("each element converts through a forward taking a class the elements are one of")
  void aSupertypesForwardConvertsEachElement() {
    final var mapper = Telescope.mapper(
      Circles.class,
      CirclesDto.class,
      via(Circles::shapes, CirclesDto::shapes, ByShape.class)
    );
    final var circles = new Circles(Arrays.asList(new Circle("a"), null));
    final var dto = mapper.forward(circles);
    assertEquals(new CirclesDto(Arrays.asList(new ShapeDto("A"), null)), dto);
    assertEquals(circles, mapper.backward(dto));
  }

  public static final class ByCircleOrShape {

    private ByCircleOrShape() {}

    public static ShapeDto forward(final Circle circle) {
      return new ShapeDto("circle:" + circle.name());
    }

    public static ShapeDto forward(final Shape shape) {
      return new ShapeDto("shape:" + shape.name());
    }

    public static Circle backward(final ShapeDto dto) {
      return new Circle(dto.name());
    }
  }

  @Test
  @DisplayName("an element's own class is chosen before a class it is one of")
  void theElementsOwnClassIsChosenFirst() {
    final var mapper = Telescope.mapper(
      Circles.class,
      CirclesDto.class,
      via(Circles::shapes, CirclesDto::shapes, ByCircleOrShape.class)
    );
    assertEquals(
      new CirclesDto(List.of(new ShapeDto("circle:a"))),
      mapper.forward(new Circles(List.of(new Circle("a"))))
    );
  }

  public record Count(Integer n) {}

  public record CountDto(long n) {}

  public static final class Counts {

    private Counts() {}

    public static long forward(final long n) {
      return n * 10;
    }

    public static long forward(final Integer n) {
      return n * 100L;
    }

    public static Integer backward(final long n) {
      return (int) (n / 100);
    }
  }

  @Test
  @DisplayName("a value binds the overload Java binds it to, a wrapper's before unboxing")
  void aValueBindsTheMostSpecificOverload() {
    final var mapper = Telescope.mapper(Count.class, CountDto.class, via(Count::n, CountDto::n, Counts.class));
    assertEquals(new CountDto(300L), mapper.forward(new Count(3)));
    assertEquals(new Count(3), mapper.backward(new CountDto(300L)));
  }

  public record Items(List<String> items) {}

  public record ItemsDto(List<Integer> items) {}

  public static final class Wholes {

    private Wholes() {}

    public static Integer forward(final String s) {
      return s.length();
    }

    public static List<Integer> forward(final List<String> all) {
      return List.of(-1);
    }

    public static String backward(final Integer n) {
      return "x".repeat(n);
    }
  }

  @Test
  @DisplayName("elements convert one by one unless both directions have a whole-container overload")
  void aWholeOverloadInOneDirectionStillLifts() {
    final var mapper = Telescope.mapper(Items.class, ItemsDto.class, via(Items::items, ItemsDto::items, Wholes.class));
    assertEquals(new ItemsDto(List.of(2, 3)), mapper.forward(new Items(List.of("ab", "cde"))));
  }

  public static final class NoForward {

    private NoForward() {}

    public static Circle backward(final ShapeDto dto) {
      return new Circle(dto.name());
    }
  }

  public static final class TwoElementForwards {

    private TwoElementForwards() {}

    public static ShapeDto forward(final Shape shape) {
      return new ShapeDto(shape.name());
    }

    public static ShapeDto forward(final Labelled labelled) {
      return new ShapeDto("l");
    }

    public static Circle backward(final ShapeDto dto) {
      return new Circle(dto.name());
    }
  }

  public static final class Unrelated {

    private Unrelated() {}

    public static ShapeDto forward(final Integer n) {
      return new ShapeDto("n");
    }

    public static Circle backward(final ShapeDto dto) {
      return new Circle(dto.name());
    }
  }

  @Test
  @DisplayName("a class the row cannot choose methods of is refused by name when the mapper is built")
  void aClassItCannotChooseFromIsRefused() {
    final var none = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(Circles.class, CirclesDto.class, via(Circles::shapes, CirclesDto::shapes, NoForward.class))
    );
    assertTrue(none.getMessage().endsWith("NoForward declares no forward"), none::getMessage);
    final var several = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(
        Circles.class,
        CirclesDto.class,
        via(Circles::shapes, CirclesDto::shapes, TwoElementForwards.class)
      )
    );
    assertTrue(
      several.getMessage().contains("TwoElementForwards's forward has several overloads"),
      several::getMessage
    );
    final var never = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(Circles.class, CirclesDto.class, via(Circles::shapes, CirclesDto::shapes, Unrelated.class))
    );
    assertTrue(never.getMessage().contains("to a mapper of Integer, but it never holds a Integer"), never::getMessage);
  }
}
