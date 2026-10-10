package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.when;
import static io.github.eschizoid.telescope.mapping.Mapping.zip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@code to} or {@code zip} row whose target telescope starts at a superclass of the mapper's
 * target writes on the target class itself, so the result keeps that class and every property the
 * superclass does not declare.
 */
class SuperclassTargetTelescopeRowTest {

  private static final SuperRowLabelSource SOURCE = new SuperRowLabelSource(7, "lbl");

  @Test
  @DisplayName("to(src, telescope) rooted at a concrete base keeps the subclass, and maps back")
  void accessorToAConcreteBaseRootedTelescope() {
    final var mapper = Telescope.mapper(
      SuperRowLabelSource.class,
      SuperRowConcreteSub.class,
      to(SuperRowLabelSource::label, Telescope.of(SuperRowConcreteBase.class).field(SuperRowConcreteBase::getName))
    );
    final var result = mapper.forward(SOURCE);
    assertSame(SuperRowConcreteSub.class, result.getClass());
    assertEquals("lbl", result.getName());
    assertEquals(7, result.getN());
    assertEquals(SOURCE, mapper.backward(result));
  }

  @Test
  @DisplayName("to(src, telescope) rooted at an abstract base writes on the subclass")
  void accessorToAnAbstractBaseRootedTelescope() {
    final var result = Telescope.mapperForward(
      SuperRowLabelSource.class,
      SuperRowAbstractSub.class,
      to(SuperRowLabelSource::label, Telescope.of(SuperRowAbstractBase.class).field(SuperRowAbstractBase::getName))
    ).forward(SOURCE);
    assertSame(SuperRowAbstractSub.class, result.getClass());
    assertEquals("lbl", result.getName());
    assertEquals(7, result.getN());
  }

  @Test
  @DisplayName("when(to(src, telescope)) rooted at an abstract base writes on the subclass")
  void conditionalAccessorToABaseRootedTelescope() {
    final var result = Telescope.mapper(
      SuperRowLabelSource.class,
      SuperRowAbstractSub.class,
      when(
        s -> s.n() > 0,
        to(SuperRowLabelSource::label, Telescope.of(SuperRowAbstractBase.class).field(SuperRowAbstractBase::getName))
      )
    ).forward(SOURCE);
    assertSame(SuperRowAbstractSub.class, result.getClass());
    assertEquals("lbl", result.getName());
    assertEquals(7, result.getN());
  }

  @Test
  @DisplayName("to(src, telescope) through a base-rooted nested path keeps the subclass and the rest of the path")
  void accessorToABaseRootedNestedPath() {
    final var result = Telescope.mapperForward(
      SuperRowHolderSource.class,
      SuperRowHolderSub.class,
      to(
        SuperRowHolderSource::n,
        Telescope.of(SuperRowHolderBase.class).field(SuperRowHolderBase::getChild).field(SuperRowSource::n)
      )
    ).forward(new SuperRowHolderSource(7, new SuperRowSource(1), List.of("a"), "lbl"));
    assertSame(SuperRowHolderSub.class, result.getClass());
    assertEquals(new SuperRowSource(7), result.getChild());
    assertEquals(7, result.getN());
  }

  @Test
  @DisplayName("to(src, telescope) through every element of a base's list keeps the subclass")
  void accessorToEveryElementOfABaseList() {
    final var result = Telescope.mapperForward(
      SuperRowHolderSource.class,
      SuperRowHolderSub.class,
      to(SuperRowHolderSource::label, Telescope.of(SuperRowHolderBase.class).each(SuperRowHolderBase::getTags))
    ).forward(new SuperRowHolderSource(7, new SuperRowSource(1), List.of("a", "b"), "lbl"));
    assertSame(SuperRowHolderSub.class, result.getClass());
    assertEquals(List.of("lbl", "lbl"), result.getTags());
    assertEquals(7, result.getN());
  }

  @Test
  @DisplayName("to(telescope, telescope) rooted at an abstract base writes on the subclass, and maps back")
  void telescopeToABaseRootedTelescope() {
    final var mapper = Telescope.mapper(
      SuperRowLabelSource.class,
      SuperRowAbstractSub.class,
      to(
        Telescope.of(SuperRowLabelSource.class).field(SuperRowLabelSource::label),
        Telescope.of(SuperRowAbstractBase.class).field(SuperRowAbstractBase::getName)
      )
    );
    final var result = mapper.forward(SOURCE);
    assertSame(SuperRowAbstractSub.class, result.getClass());
    assertEquals("lbl", result.getName());
    assertEquals(7, result.getN());
    assertEquals(SOURCE, mapper.backward(result));
  }

  @Test
  @DisplayName("zip onto every element of an abstract base's list writes on the subclass")
  void zipOntoABaseRootedList() {
    final var result = Telescope.mapperForward(
      SuperRowHolderSource.class,
      SuperRowHolderSub.class,
      zip(
        Telescope.of(SuperRowHolderSource.class).each(SuperRowHolderSource::tags),
        Telescope.of(SuperRowHolderBase.class).each(SuperRowHolderBase::getTags)
      )
    ).forward(new SuperRowHolderSource(7, new SuperRowSource(1), List.of("a", "b"), "lbl"));
    assertSame(SuperRowHolderSub.class, result.getClass());
    assertEquals(List.of("a", "b"), result.getTags());
    assertEquals(7, result.getN());
  }

  @Test
  @DisplayName("to(src, telescope) rooted at a class the target does not extend is refused by name")
  void accessorToAnUnrelatedRootIsRefused() {
    final var failure = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(
        SuperRowLabelSource.class,
        SuperRowAbstractSub.class,
        to(SuperRowLabelSource::label, Telescope.of(SuperRowConcreteBase.class).field(SuperRowConcreteBase::getName))
      )
    );
    assertEquals(
      PairingMessages.targetRowOffTarget(
        "SuperRowLabelSource",
        "SuperRowAbstractSub",
        "to",
        "SuperRowConcreteBase",
        "name"
      ),
      failure.getMessage()
    );
  }
}
