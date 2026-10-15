package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.constant;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.via;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.util.List;
import java.util.Map;
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

  record Other(String name) {}

  record Arrays<X>(X[] children) {}

  record NameArray(ChDto[] children) {}

  record Bounded<X extends Ch>(X children) {}

  record Name(ChDto children) {}

  record Quantity(int children) {}

  record Label(int n) {}

  record IntSource(int v) {}

  record IntegerSource(Integer v) {}

  record LabelTarget(Label v) {}

  record LabelSource(Label v) {}

  record IntTarget(int v) {}

  record IntegerTarget(Integer v) {}

  record NameLists(List<List<ChDto>> children) {}

  @Test
  @DisplayName("a mapper whose class the element variable's bound excludes is refused when the mapper is built")
  void boundExcludingTheMapperIsRefused() {
    final var child = Telescope.mapper(Ch.class, ChDto.class, to(Ch::name, ChDto::name));
    @SuppressWarnings({ "rawtypes", "unchecked" })
    final var row = via((Telescope.Accessor<Numbers, Object>) Numbers::children, Names::children, child);
    final var refusal = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(Numbers.class, Names.class, row)
    );
    assertEquals(
      PairingMessages.viaMapperNeverFits("Numbers", "Names", "source", "children", "Ch"),
      refusal.getMessage()
    );
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
  @DisplayName("a list of lists with a mapper of the inner element is refused when the mapper is built")
  void parameterizedElementIsRefused() {
    final var child = Telescope.mapper(Ch.class, ChDto.class, to(Ch::name, ChDto::name));
    final var refusal = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(Lists.class, NameLists.class, via(Lists::children, NameLists::children, child))
    );
    assertEquals(
      PairingMessages.viaMapperNeverFits("Lists", "NameLists", "source", "children", "Ch"),
      refusal.getMessage()
    );
  }

  @Test
  @DisplayName("an array of a variable with a mapper of the element is refused when the mapper is built")
  void arrayOfAVariableIsRefused() {
    final var child = Telescope.mapper(Ch.class, ChDto.class, to(Ch::name, ChDto::name));
    @SuppressWarnings({ "rawtypes", "unchecked" })
    final var row = via((Telescope.Accessor<Arrays, Object>) Arrays::children, NameArray::children, child);
    final var refusal = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(Arrays.class, NameArray.class, row)
    );
    assertEquals(
      PairingMessages.viaMapperNeverFits("Arrays", "NameArray", "source", "children", "Ch"),
      refusal.getMessage()
    );
  }

  @Test
  @DisplayName(
    "a property typed by a variable whose bound excludes the mapper's class is refused when the mapper is built"
  )
  void variableBoundExcludingTheMapperIsRefused() {
    final var other = Telescope.mapper(Other.class, ChDto.class, to(Other::name, ChDto::name));
    @SuppressWarnings({ "rawtypes", "unchecked" })
    final var row = via((Telescope.Accessor<Bounded, Object>) Bounded::children, Name::children, other);
    final var refusal = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(Bounded.class, Name.class, row)
    );
    assertEquals(
      PairingMessages.viaMapperNeverFits("Bounded", "Name", "source", "children", "Other"),
      refusal.getMessage()
    );
  }

  @Test
  @DisplayName("a mapper of the child into a property that never holds its result is refused on the target side")
  void targetThatNeverHoldsTheResultIsRefused() {
    final var child = Telescope.mapper(Ch.class, ChDto.class, to(Ch::name, ChDto::name));
    @SuppressWarnings({ "rawtypes", "unchecked" })
    final var row = via((Telescope.Accessor<Bounded, Object>) Bounded::children, Names::children, child);
    final var refusal = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(Bounded.class, Names.class, row)
    );
    assertEquals(
      PairingMessages.viaMapperNeverFits("Bounded", "Names", "target", "children", "ChDto"),
      refusal.getMessage()
    );
  }

  @Test
  @DisplayName("a primitive property is handed to a mapper of a class its wrapper implements")
  void primitivePropertyIsHandedOverBoxed() {
    final var comparable = Telescope.mapper(Comparable.class, ChDto.class, constant(ChDto::name, "c"));
    @SuppressWarnings({ "rawtypes", "unchecked" })
    final var row = via((Telescope.Accessor<Quantity, Object>) Quantity::children, Name::children, comparable);
    final var mapper = Telescope.mapperForward(Quantity.class, Name.class, row);
    assertEquals(new Name(new ChDto("c")), mapper.forward(new Quantity(3)));
  }

  /** A mapper whose source class is the primitive {@code int}, labelling a number. */
  private static Mapper<Integer, Label> fromInt() {
    return Mapper.create(Label::new, Label::n, int.class, Label.class, Map.of());
  }

  /** A mapper whose target class is the primitive {@code int}, reading a label's number. */
  private static Mapper<Label, Integer> toInt() {
    return Mapper.create(Label::n, Label::new, Label.class, int.class, Map.of());
  }

  @Test
  @DisplayName("a mapper of the primitive int is handed an int source property")
  void intMapperOnAnIntSource() {
    final var mapper = Telescope.mapperForward(
      IntSource.class,
      LabelTarget.class,
      via(IntSource::v, LabelTarget::v, fromInt())
    );
    assertEquals(new LabelTarget(new Label(3)), mapper.forward(new IntSource(3)));
  }

  @Test
  @DisplayName("a mapper of the primitive int is handed an Integer source property")
  void intMapperOnAnIntegerSource() {
    final var mapper = Telescope.mapperForward(
      IntegerSource.class,
      LabelTarget.class,
      via(IntegerSource::v, LabelTarget::v, fromInt())
    );
    assertEquals(new LabelTarget(new Label(3)), mapper.forward(new IntegerSource(3)));
  }

  @Test
  @DisplayName("a mapper to the primitive int writes an int target property")
  void intMapperOnAnIntTarget() {
    final var mapper = Telescope.mapperForward(
      LabelSource.class,
      IntTarget.class,
      via(LabelSource::v, IntTarget::v, toInt())
    );
    assertEquals(new IntTarget(3), mapper.forward(new LabelSource(new Label(3))));
  }

  @Test
  @DisplayName("a mapper to the primitive int writes an Integer target property")
  void intMapperOnAnIntegerTarget() {
    final var mapper = Telescope.mapperForward(
      LabelSource.class,
      IntegerTarget.class,
      via(LabelSource::v, IntegerTarget::v, toInt())
    );
    assertEquals(new IntegerTarget(3), mapper.forward(new LabelSource(new Label(3))));
  }
}
