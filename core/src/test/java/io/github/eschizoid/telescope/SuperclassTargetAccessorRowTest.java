package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@code to(sourceTelescope, targetAccessor)} row applies at the mapper's own pair, so a target
 * accessor declared on a superclass or interface of the mapper's target writes the property on the
 * target class, and an accessor on a class the target neither is nor extends is refused when the
 * mapper is built.
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
  @DisplayName("to(telescope, accessor) through a class the target does not extend is refused when the mapper is built")
  void accessorOnAnUnrelatedClassIsRefused() {
    final var expected = PairingMessages.targetRowOffTarget(
      "SuperRowLabelSource",
      "SuperRowConcreteSub",
      "to",
      "SuperRowNamedBean",
      "name"
    );
    final var strict = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(SuperRowLabelSource.class, SuperRowConcreteSub.class, to(LABEL, SuperRowNamedBean::getName))
    );
    assertEquals(expected, strict.getMessage());
    final var forwardOnly = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapperForward(
        SuperRowLabelSource.class,
        SuperRowConcreteSub.class,
        to(LABEL, SuperRowNamedBean::getName)
      )
    );
    assertEquals(expected, forwardOnly.getMessage());
  }

  @Test
  @DisplayName("when(to(telescope, accessor)) through a class the target does not extend is refused as the bare row is")
  void conditionalAccessorOnAnUnrelatedClassIsRefused() {
    final var failure = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapperForward(
        SuperRowLabelSource.class,
        SuperRowConcreteSub.class,
        when(s -> s.n() > 0, to(LABEL, SuperRowNamedBean::getName))
      )
    );
    assertEquals(
      PairingMessages.targetRowOffTarget(
        "SuperRowLabelSource",
        "SuperRowConcreteSub",
        "to",
        "SuperRowNamedBean",
        "name"
      ),
      failure.getMessage()
    );
  }
}
