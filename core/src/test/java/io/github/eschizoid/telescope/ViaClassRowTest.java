package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.via;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.mapping.Mapping;
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

  public record Score(int n) {}

  public record ScoreDto(String n) {}

  public static final class Scores {

    private Scores() {}

    public static String forward(final long n) {
      return "long:" + n;
    }

    public static String forward(final Integer n) {
      return "boxed:" + n;
    }

    public static String forward(final int n) {
      return "int:" + n;
    }

    public static int backward(final String s) {
      return Integer.parseInt(s.substring(s.indexOf(':') + 1));
    }
  }

  @Test
  @DisplayName("a primitive binds the overload taking it before one it widens or boxes to")
  void aPrimitiveBindsItsOwnOverload() {
    final var mapper = Telescope.mapper(Score.class, ScoreDto.class, via(Score::n, ScoreDto::n, Scores.class));
    assertEquals(new ScoreDto("int:3"), mapper.forward(new Score(3)));
    assertEquals(new Score(4), mapper.backward(new ScoreDto("long:4")));
  }

  public record Named(String name) {}

  public record NamedDto(StringBuilder name) {}

  public static final class Generic {

    private Generic() {}

    public static <T extends CharSequence> StringBuilder forward(final T value) {
      return new StringBuilder(value).reverse();
    }

    public static <T extends CharSequence> String backward(final T value) {
      return new StringBuilder(value).reverse().toString();
    }
  }

  @Test
  @DisplayName("a value binds a generic overload whose type variable's bound admits it")
  void aGenericOverloadIsBound() {
    final var mapper = Telescope.mapper(Named.class, NamedDto.class, via(Named::name, NamedDto::name, Generic.class));
    assertEquals("cba", mapper.forward(new Named("abc")).name().toString());
    assertEquals(new Named("abc"), mapper.backward(new NamedDto(new StringBuilder("cba"))));
  }

  public record Tags(List<String> tags) {}

  public record TagsDto(List<String> tags) {}

  public static final class Same {

    private Same() {}

    public static <T> T forward(final T value) {
      return value;
    }

    public static <T> T backward(final T value) {
      return value;
    }
  }

  @Test
  @DisplayName("a container converts whole through methods whose result their own type variable types")
  void aGenericWholeOverloadConvertsTheContainer() {
    final var mapper = Telescope.mapper(Tags.class, TagsDto.class, via(Tags::tags, TagsDto::tags, Same.class));
    final var tags = new Tags(List.of("a", "b"));
    assertSame(tags.tags(), mapper.forward(tags).tags());
  }

  public record Holder(Shape shape) {}

  public record HolderDto(ShapeDto shape) {}

  public static final class CircleOnly {

    private CircleOnly() {}

    public static ShapeDto forward(final Circle circle) {
      return new ShapeDto(circle.name());
    }

    public static Circle backward(final ShapeDto dto) {
      return new Circle(dto.name());
    }
  }

  @Test
  @DisplayName("a value calls the one method it can be an instance of the parameter of, through a cast")
  void theOneReachableMethodIsCalledThroughACast() {
    final var mapper = Telescope.mapper(
      Holder.class,
      HolderDto.class,
      via(Holder::shape, HolderDto::shape, CircleOnly.class)
    );
    assertEquals(new HolderDto(new ShapeDto("a")), mapper.forward(new Holder(new Circle("a"))));
    assertEquals(new Holder(new Circle("b")), mapper.backward(new HolderDto(new ShapeDto("b"))));
  }

  public static final class PackageLevel {

    private PackageLevel() {}

    static ShapeDto forward(final Shape shape) {
      return new ShapeDto(shape.name() + "!");
    }

    static Circle backward(final ShapeDto dto) {
      return new Circle(dto.name());
    }
  }

  @Test
  @DisplayName("a class's package-private methods are chosen as its public ones are")
  void packagePrivateMethodsAreChosen() {
    final var mapper = Telescope.mapper(
      Circles.class,
      CirclesDto.class,
      via(Circles::shapes, CirclesDto::shapes, PackageLevel.class)
    );
    assertEquals(new CirclesDto(List.of(new ShapeDto("a!"))), mapper.forward(new Circles(List.of(new Circle("a")))));
  }

  public record Amount(Number n) {}

  public record AmountDto(String n) {}

  public static final class TwoNumberForwards {

    private TwoNumberForwards() {}

    public static String forward(final Integer n) {
      return "i" + n;
    }

    public static String forward(final Long n) {
      return "l" + n;
    }

    public static Integer backward(final String s) {
      return Integer.valueOf(s.substring(1));
    }
  }

  public record Text(String s) {}

  public record TextDto(Number s) {}

  public static final class TwoNumberBackwards {

    private TwoNumberBackwards() {}

    public static Integer forward(final String s) {
      return Integer.valueOf(s);
    }

    public static String backward(final Integer n) {
      return "i" + n;
    }

    public static String backward(final Long n) {
      return "l" + n;
    }
  }

  @Test
  @DisplayName("a direction whose overloads a value could reach several of is refused by name")
  void severalReachableOverloadsAreRefused() {
    final var forward = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(Amount.class, AmountDto.class, via(Amount::n, AmountDto::n, TwoNumberForwards.class))
    );
    assertTrue(
      forward.getMessage().endsWith("TwoNumberForwards's forward takes no value of java.lang.Number"),
      forward::getMessage
    );
    final var backward = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(Text.class, TextDto.class, via(Text::s, TextDto::s, TwoNumberBackwards.class))
    );
    assertTrue(
      backward.getMessage().endsWith("TwoNumberBackwards's backward takes no value of java.lang.Number"),
      backward::getMessage
    );
  }

  public record Bag<T extends Shape>(List<T> items) {}

  public record BagDto(List<ShapeDto> items) {}

  @Test
  @DisplayName("elements a type variable types convert through a forward taking a class within its bound")
  @SuppressWarnings({ "rawtypes", "unchecked" })
  void elementsOfATypeVariableConvertWithinItsBound() {
    final var mapper = Telescope.mapper(
      Bag.class,
      BagDto.class,
      Mapping.<Bag, BagDto>via(Bag::items, BagDto::items, ByShape.class)
    );
    final var bag = new Bag<>(List.of(new Circle("a")));
    assertEquals(new BagDto(List.of(new ShapeDto("A"))), mapper.forward(bag));
    assertEquals(bag, mapper.backward(new BagDto(List.of(new ShapeDto("A")))));
  }
}
