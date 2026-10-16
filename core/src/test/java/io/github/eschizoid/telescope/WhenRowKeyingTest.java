package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.mapping.MapStep;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins that a {@code when(predicate, row)} row is keyed to the type pair {@code row} is keyed to:
 * it is refused when the mapper is built wherever {@code row} is refused unwrapped, and it applies
 * at the nested pair {@code row} names, with its predicate receiving that pair's source.
 */
class WhenRowKeyingTest {

  record Inner(String y) {}

  record InnerDto(String y) {}

  record Outer(Inner inner, String note) {}

  record OuterDto(InnerDto inner, String note) {}

  /** A source that nests a different class, which converts to the mapper's own target class. */
  record Twig(String label, String note, Twig child) {}

  record Tree(String label, String note, Twig child) {}

  record TreeDto(String label, String note, TreeDto child) {}

  private static String refusal(final MapStep row) {
    return assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(Outer.class, OuterDto.class, row)
    ).getMessage();
  }

  @Nested
  @DisplayName("A when row whose row is keyed to a pair the mapper never visits is refused at build")
  class Refused {

    @Test
    @DisplayName("to(src, targetPath) whose source accessor reads a class the mapper converts elsewhere")
    void accessorOntoTargetPath() {
      final var path = Telescope.of(OuterDto.class).field(OuterDto::note);
      assertEquals(refusal(to(Inner::y, path)), refusal(when(i -> true, to(Inner::y, path))));
    }

    @Test
    @DisplayName("to(sourcePath, tgt) whose target accessor reads a class the mapper converts elsewhere")
    void sourcePathOntoAccessor() {
      final var path = Telescope.of(Outer.class).field(Outer::note);
      assertEquals(refusal(to(path, InnerDto::y)), refusal(when(o -> true, to(path, InnerDto::y))));
    }
  }

  @Test
  @DisplayName("to(sourcePath, tgt) through a getter the mapper's target inherits applies at the mapper's own pair")
  void sourcePathOntoATargetSuperclass() {
    final var label = Telescope.of(SuperRowLabelSource.class).field(SuperRowLabelSource::label);
    final var result = Telescope.mapperForward(
      SuperRowLabelSource.class,
      SuperRowAbstractSub.class,
      when((SuperRowLabelSource s) -> s.n() > 0, to(label, SuperRowAbstractBase::getName))
    ).forward(new SuperRowLabelSource(7, "lbl"));
    assertEquals("lbl", result.getName());
    assertEquals(7, result.getN());
  }

  @Nested
  @DisplayName("A when row whose row is keyed to a nested pair applies at that pair")
  class AtANestedPair {

    @Test
    @DisplayName("to(sourcePath, tgt) onto a nested target: the predicate receives the nested source")
    void sourcePathOntoANestedTarget() {
      final var seen = new ArrayList<SuperRowOuter>();
      final var mapper = Telescope.mapperForward(
        SuperRowOuter.class,
        SuperRowOuterDto.class,
        when(
          (SuperRowOuter o) -> {
            seen.add(o);
            return true;
          },
          to(Telescope.of(SuperRowOuter.class).field(SuperRowOuter::n), SuperRowInnerDto::m)
        )
      );
      final var nested = new SuperRowOuter("in", null);
      assertEquals(
        new SuperRowOuterDto("top", new SuperRowInnerDto("in")),
        mapper.forward(new SuperRowOuter("top", nested))
      );
      assertEquals(List.of(nested), seen);
    }

    @Test
    @DisplayName("to(sourcePath, tgt) onto a nested target: a rejecting predicate leaves the nested default")
    void sourcePathOntoANestedTargetRejected() {
      final var seen = new ArrayList<SuperRowOuter>();
      final var mapper = Telescope.mapperForward(
        SuperRowOuter.class,
        SuperRowOuterDto.class,
        when(
          (SuperRowOuter o) -> {
            seen.add(o);
            return false;
          },
          to(Telescope.of(SuperRowOuter.class).field(SuperRowOuter::n), SuperRowInnerDto::m)
        )
      );
      final var nested = new SuperRowOuter("in", null);
      assertEquals(
        new SuperRowOuterDto("top", new SuperRowInnerDto(null)),
        mapper.forward(new SuperRowOuter("top", nested))
      );
      assertEquals(List.of(nested), seen);
    }

    @Test
    @DisplayName("to(src, targetPath) from a nested source class: the predicate gates each nested level")
    void accessorFromANestedSource() {
      final var mapper = Telescope.mapper(
        Tree.class,
        TreeDto.class,
        when((Twig t) -> t.label().startsWith("x"), to(Twig::label, Telescope.of(TreeDto.class).field(TreeDto::note)))
      );
      final var result = mapper.forward(new Tree("root", "r", new Twig("x1", "a", new Twig("y2", "b", null))));
      assertEquals(new TreeDto("root", "r", new TreeDto("x1", "x1", new TreeDto("y2", "b", null))), result);
    }
  }
}
