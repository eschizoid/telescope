package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@code to(sourceTelescope, targetAccessor)} row whose target accessor is applied to a
 * superclass or interface of the mapper's target applies at the mapper's own pair and writes the
 * property on the target class. A row whose accessor names any other class keeps the pair that
 * class names, which may be a nested pair the mapper visits.
 */
class SuperclassTargetAccessorRowTest {

  private static final SuperRowLabelSource SOURCE = new SuperRowLabelSource(7, "lbl");
  private static final Telescope<SuperRowLabelSource, String> LABEL = Telescope.of(SuperRowLabelSource.class).field(
    SuperRowLabelSource::label
  );

  @Test
  @DisplayName("to(telescope, accessor) through a concrete base's getter keeps the subclass, and maps back")
  void accessorOnAConcreteBase() {
    final var mapper = Telescope.mapper(
      SuperRowLabelSource.class,
      SuperRowConcreteSub.class,
      to(LABEL, SuperRowConcreteBase::getName)
    );
    final var result = mapper.forward(SOURCE);
    assertSame(SuperRowConcreteSub.class, result.getClass());
    assertEquals("lbl", result.getName());
    assertEquals(7, result.getN());
    assertEquals(SOURCE, mapper.backward(result));
  }

  @Test
  @DisplayName("to(telescope, accessor) through an abstract base's getter writes on the subclass")
  void accessorOnAnAbstractBase() {
    final var result = Telescope.mapperForward(
      SuperRowLabelSource.class,
      SuperRowAbstractSub.class,
      to(LABEL, SuperRowAbstractBase::getName)
    ).forward(SOURCE);
    assertSame(SuperRowAbstractSub.class, result.getClass());
    assertEquals("lbl", result.getName());
    assertEquals(7, result.getN());
  }

  @Test
  @DisplayName("to(telescope, accessor) through an interface the record target implements writes the component")
  void accessorOnAnInterfaceOfARecordTarget() {
    final var result = Telescope.mapperForward(
      SuperRowLabelSource.class,
      SuperRowNamedRecord.class,
      to(LABEL, SuperRowNamed::name)
    ).forward(SOURCE);
    assertEquals(new SuperRowNamedRecord(7, "lbl"), result);
  }

  @Test
  @DisplayName("to(telescope, accessor) through a class on a nested pair still applies on that nested pair")
  void accessorOnANestedPairKeepsItsPair() {
    final var result = Telescope.mapperForward(
      SuperRowOuter.class,
      SuperRowOuterDto.class,
      to(Telescope.of(SuperRowOuter.class).field(SuperRowOuter::n), SuperRowInnerDto::m)
    ).forward(new SuperRowOuter("top", new SuperRowOuter("in", null)));
    assertEquals(new SuperRowOuterDto("top", new SuperRowInnerDto("in")), result);
  }
}
