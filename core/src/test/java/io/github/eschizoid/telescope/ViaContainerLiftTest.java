package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.via;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@code via} row lifts its element mapper over a container whose element is a type variable only
 * when the variable's bound admits the mapper's class; otherwise the mapper is handed the whole
 * container, as it is for any other element it does not convert.
 */
class ViaContainerLiftTest {

  record Ch(String name) {}

  record ChDto(String name) {}

  record Numbers<X extends Number>(List<X> children) {}

  record Names(List<ChDto> children) {}

  record Nested<B extends Ch, X extends B>(List<X> children) {}

  record Lists(List<List<Ch>> children) {}

  record NameLists(List<List<ChDto>> children) {}

  @Test
  @DisplayName("a mapper whose class the element variable's bound excludes is handed the whole list")
  void boundExcludingTheMapperIsNotLifted() {
    final var child = Telescope.mapper(Ch.class, ChDto.class, to(Ch::name, ChDto::name));
    @SuppressWarnings({ "rawtypes", "unchecked" })
    final var mapper = Telescope.mapper(
      Numbers.class,
      Names.class,
      via((Telescope.Accessor<Numbers, Object>) Numbers::children, Names::children, child)
    );
    final var failure = assertThrows(ClassCastException.class, () -> mapper.forward(new Numbers<>(List.of(1))));
    assertTrue(failure.getMessage().contains("java.util.ImmutableCollections"), failure::getMessage);
  }

  @Test
  @DisplayName("a variable bounded by another variable that admits the mapper's class is lifted")
  void boundThroughAnotherVariableIsLifted() {
    final var child = Telescope.mapper(Ch.class, ChDto.class, to(Ch::name, ChDto::name));
    @SuppressWarnings({ "rawtypes", "unchecked" })
    final var mapper = Telescope.mapper(
      Nested.class,
      Names.class,
      via((Telescope.Accessor<Nested, Object>) Nested::children, Names::children, child)
    );
    assertEquals(new Names(List.of(new ChDto("a"))), mapper.forward(new Nested<>(List.of(new Ch("a")))));
  }

  @Test
  @DisplayName("a list whose element is itself a parameterized list is not lifted by a mapper of the inner element")
  void parameterizedElementIsNotLifted() {
    final var child = Telescope.mapper(Ch.class, ChDto.class, to(Ch::name, ChDto::name));
    final var mapper = Telescope.mapper(Lists.class, NameLists.class, via(Lists::children, NameLists::children, child));
    final var failure = assertThrows(ClassCastException.class, () ->
      mapper.forward(new Lists(List.of(List.of(new Ch("a")))))
    );
    assertTrue(failure.getMessage().contains("java.util.ImmutableCollections"), failure::getMessage);
  }
}
