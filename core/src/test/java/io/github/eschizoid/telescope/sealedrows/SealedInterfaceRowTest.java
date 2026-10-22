package io.github.eschizoid.telescope.sealedrows;

import static io.github.eschizoid.telescope.mapping.Cases.cases;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A row keyed to two sealed interfaces applies to every pair of their cases the mapper converts,
 * wherever the mapper meets that pair, and a case's own bridge gives way to it as it does to a row
 * keyed to the case pair itself. {@link SrCircle}'s bridge fills the title from the label, so a
 * title filled from the name shows the row applied.
 */
class SealedInterfaceRowTest {

  @Test
  @DisplayName("a case converts by the row rather than through its own bridge")
  void caseBridgeGivesWayToTheRow() {
    final var mapper = Telescope.mapperForward(
      SrShape.class,
      SrShapeDto.class,
      cases(SrSquare.class, SrSquareDto.class),
      to(SrShape::name, SrShapeDto::title)
    );
    assertEquals(new SrCircleDto("n", "n"), mapper.forward(new SrCircle("n", "l")));
    assertEquals(new SrSquareDto("s", "s"), mapper.forward(new SrSquare("s", "q")));
  }

  @Test
  @DisplayName("a pair of cases met as itself, outside the dispatch, takes the row too")
  void caseMetAsItself() {
    final var mapper = Telescope.mapperForward(
      SrFrame.class,
      SrFrameDto.class,
      cases(SrSquare.class, SrSquareDto.class),
      to(SrShape::name, SrShapeDto::title)
    );
    assertEquals(
      new SrFrameDto(new SrSquareDto("s", "s"), new SrCircleDto("c", "c")),
      mapper.forward(new SrFrame(new SrSquare("s", "q"), new SrCircle("c", "l")))
    );
  }

  @Test
  @DisplayName("a mapper holding only a case, and never the interfaces, takes the row")
  void onlyACase() {
    final var mapper = Telescope.mapper(SrOnly.class, SrOnlyDto.class, to(SrShape::name, SrShapeDto::title));
    assertEquals(new SrOnlyDto(new SrCircleDto("c", "c")), mapper.forward(new SrOnly(new SrCircle("c", "l"))));
  }

  @Test
  @DisplayName("a pair's own row converting the same target property stands in for the row")
  void ownRowWins() {
    final var mapper = Telescope.mapperForward(
      SrShape.class,
      SrShapeDto.class,
      cases(SrSquare.class, SrSquareDto.class),
      to(SrSquare::label, SrSquareDto::title),
      to(SrShape::name, SrShapeDto::title)
    );
    assertEquals(new SrSquareDto("s", "q"), mapper.forward(new SrSquare("s", "q")));
    assertEquals(new SrCircleDto("c", "c"), mapper.forward(new SrCircle("c", "l")));
  }

  @Test
  @DisplayName("a case whose accessor reads no property is refused by name")
  void caseWithNoSuchProperty() {
    final var refused = assertThrows(IllegalStateException.class, () ->
      Telescope.mapperForward(
        SrShape.class,
        SrOddDto.class,
        cases(SrSquare.class, SrOddSquareDto.class),
        to(SrShape::name, SrOddDto::title)
      )
    );
    assertEquals(
      PairingMessages.interfaceRowOffCase(
        "name -> title",
        SrShape.class.getName(),
        SrOddDto.class.getName(),
        SrSquare.class.getName(),
        SrOddSquareDto.class.getName(),
        SrOddSquareDto.class.getName(),
        "title"
      ),
      refused.getMessage()
    );
  }
}
