package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Cases.cases;
import static io.github.eschizoid.telescope.mapping.Mapping.compute;
import static io.github.eschizoid.telescope.mapping.Mapping.constant;
import static io.github.eschizoid.telescope.mapping.Mapping.drop;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.toOneWay;
import static io.github.eschizoid.telescope.mapping.Mapping.via;
import static io.github.eschizoid.telescope.mapping.Mapping.when;
import static io.github.eschizoid.telescope.mapping.Mapping.zip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.mapping.MapStep;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A mapper given a row keyed below its own pair walks the pairs it visits before it builds
 * anything, with every other row it is given in the walk. Each row kind is read as the build
 * applies it, so the walk reaches the nested row's pair, or refuses the row, as the build would.
 */
class RowWalkTest {

  record Inner(String name, String label) {}

  record InnerDto(String name, String label) {}

  record Part(String v) {}

  record PartDto(String v) {}

  record Other(String name) {}

  record Outer(Inner inner, Part part, String note, String extra) {}

  record OuterDto(InnerDto inner, PartDto part, String note, String extra) {}

  private static final Outer SOURCE = new Outer(new Inner("n", "l"), new Part("p"), "note", "extra");

  /** The nested row every mapper here is given: it fills a target name from the source label. */
  private static final MapStep NESTED = to(Inner::label, InnerDto::name);

  private static OuterDto forward(final MapStep row) {
    return Telescope.mapperForward(Outer.class, OuterDto.class, NESTED, row).forward(SOURCE);
  }

  @Nested
  @DisplayName("Beside each row kind on the mapper's own pair, the nested row's pair is reached")
  class Reached {

    @Test
    @DisplayName("a row with a conversion of its own on another property")
    void convertingRow() {
      assertEquals("l", forward(to(Outer::note, OuterDto::note, s -> s + "!", s -> s)).inner().name());
    }

    @Test
    @DisplayName("a forward-only row with a conversion of its own")
    void forwardOnlyRow() {
      assertEquals("note!", forward(toOneWay(Outer::note, OuterDto::note, s -> s + "!")).note());
    }

    @Test
    @DisplayName("a via row on another property")
    void viaRow() {
      final var dto = forward(via(Outer::part, OuterDto::part, Telescope.mapper(Part.class, PartDto.class)));
      assertEquals("l", dto.inner().name());
      assertEquals("p", dto.part().v());
    }

    @Test
    @DisplayName("a drop row")
    void dropRow() {
      assertEquals("l", forward(drop(Outer::extra)).inner().name());
    }

    @Test
    @DisplayName("rows through telescope paths on either side or both")
    void pathRows() {
      final var target = Telescope.of(OuterDto.class).field(OuterDto::extra);
      final var source = Telescope.of(Outer.class).field(Outer::note);
      assertEquals("note", forward(to(Outer::note, target)).extra());
      assertEquals("note", forward(to(source, OuterDto::extra)).extra());
      assertEquals("note", forward(to(source, target)).extra());
      assertEquals("note", forward(zip(source, target)).extra());
    }

    @Test
    @DisplayName("constant and compute rows")
    void injectedRows() {
      assertEquals("c", forward(constant(OuterDto::extra, "c")).extra());
      assertEquals("s", forward(compute(OuterDto::extra, () -> "s")).extra());
    }

    @Test
    @DisplayName("a when row")
    void whenRow() {
      final var dto = forward(when(o -> true, to(Outer::note, Telescope.of(OuterDto.class).field(OuterDto::extra))));
      assertEquals("l", dto.inner().name());
      assertEquals("note", dto.extra());
    }
  }

  @Test
  @DisplayName("a nested row below a property a row with a conversion of its own claims is refused before the build")
  void belowAConvertingRow() {
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapperForward(
        Outer.class,
        OuterDto.class,
        to(Outer::inner, OuterDto::inner, i -> new InnerDto(i.name(), i.label()), d -> null),
        NESTED
      )
    );
    assertEquals(
      PairingMessages.rowsNeverVisited(
        Outer.class.getName(),
        OuterDto.class.getName(),
        List.of(
          PairingMessages.unvisitedPair(Inner.class.getName(), InnerDto.class.getName(), List.of("label -> name"))
        )
      ),
      refused.getMessage()
    );
  }

  sealed interface Shape permits Circle, Square {}

  record Circle(Inner inner) implements Shape {}

  record Square(String side) implements Shape {}

  sealed interface ShapeDto permits CircleDto, SquareDto {}

  record CircleDto(InnerDto inner) implements ShapeDto {}

  record SquareDto(String side) implements ShapeDto {}

  record Drawing(Shape shape) {}

  record DrawingDto(ShapeDto shape) {}

  record Framed(Shape shape) {}

  record FramedDto(String title, ShapeDto shape) {}

  record Wider(Inner inner, PartDto part, String note, String extra, String more) {}

  @Nested
  @DisplayName("A refusal the build raises is named before a row the walk never reaches")
  class BuildRefusalsFirst {

    @Test
    @DisplayName("a source field with no target counterpart")
    void fieldWithNoCounterpart() {
      final var refused = assertThrows(IllegalStateException.class, () ->
        Telescope.mapper(Wider.class, OuterDto.class, to(Inner::name, Other::name))
      );
      assertEquals(
        PairingMessages.noSameNameTarget("Wider", "OuterDto", "field", "field", "more"),
        refused.getMessage()
      );
    }

    @Test
    @DisplayName("a case of a sealed field that no cases row pairs")
    void unpairedCase() {
      final var refused = assertThrows(IllegalStateException.class, () ->
        Telescope.mapper(
          Drawing.class,
          DrawingDto.class,
          cases(Circle.class, CircleDto.class),
          to(Inner::name, Other::name)
        )
      );
      assertEquals(PairingMessages.unpairedCase("Shape", "ShapeDto", "Square"), refused.getMessage());
    }

    @Test
    @DisplayName("a target field with no source counterpart, met before a sealed field whose cases do not pair")
    void fieldBeforeAnUnpairedCase() {
      final var refused = assertThrows(IllegalStateException.class, () ->
        Telescope.mapper(
          Framed.class,
          FramedDto.class,
          cases(Circle.class, CircleDto.class),
          to(Inner::name, Other::name)
        )
      );
      assertEquals(
        PairingMessages.noSameNameSource("Framed", "FramedDto", "field", "field", "title"),
        refused.getMessage()
      );
    }
  }

  @Nested
  @DisplayName("Below a sealed field the walk follows the cases as the mapper pairs them")
  class BelowSealedCases {

    @Test
    @DisplayName("and reaches a pair one case leads to")
    void reached() {
      final var mapper = Telescope.mapper(
        Drawing.class,
        DrawingDto.class,
        cases(Circle.class, CircleDto.class),
        cases(Square.class, SquareDto.class),
        to(Inner::label, InnerDto::label)
      );
      assertEquals(
        new DrawingDto(new CircleDto(new InnerDto("n", "l"))),
        mapper.forward(new Drawing(new Circle(new Inner("n", "l"))))
      );
    }

    @Test
    @DisplayName("and refuses a row keyed to a pair no case leads to")
    void refused() {
      final var refused = assertThrows(IllegalArgumentException.class, () ->
        Telescope.mapper(
          Drawing.class,
          DrawingDto.class,
          cases(Circle.class, CircleDto.class),
          cases(Square.class, SquareDto.class),
          to(Inner::name, Other::name)
        )
      );
      assertEquals(
        PairingMessages.rowsNeverVisited(
          Drawing.class.getName(),
          DrawingDto.class.getName(),
          List.of(PairingMessages.unvisitedPair(Inner.class.getName(), Other.class.getName(), List.of("name -> name")))
        ),
        refused.getMessage()
      );
    }
  }
}
