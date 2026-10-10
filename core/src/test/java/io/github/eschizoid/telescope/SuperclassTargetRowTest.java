package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.compute;
import static io.github.eschizoid.telescope.mapping.Mapping.constant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@code constant} or {@code compute} row whose accessor names a superclass of the mapper's
 * target writes the property on the target class itself, so the result keeps that class and every
 * property the superclass does not declare. Each base shape is driven through both rows, on the
 * bidirectional mapper's forward direction and on a forward-only mapper.
 */
class SuperclassTargetRowTest {

  private static final SuperRowSource SOURCE = new SuperRowSource(7);

  @Test
  @DisplayName("constant through a concrete base's getter keeps the subclass and its own property")
  void constantThroughAConcreteBase() {
    final var result = Telescope.mapper(
      SuperRowSource.class,
      SuperRowConcreteSub.class,
      constant(SuperRowConcreteBase::getName, "fixed")
    ).forward(SOURCE);
    assertSame(SuperRowConcreteSub.class, result.getClass());
    assertEquals("fixed", result.getName());
    assertEquals(7, result.getN());
  }

  @Test
  @DisplayName("compute through a concrete base's getter keeps the subclass and its own property")
  void computeThroughAConcreteBase() {
    final var result = Telescope.mapper(
      SuperRowSource.class,
      SuperRowConcreteSub.class,
      compute(SuperRowConcreteBase::getName, () -> "made")
    ).forward(SOURCE);
    assertSame(SuperRowConcreteSub.class, result.getClass());
    assertEquals("made", result.getName());
    assertEquals(7, result.getN());
  }

  @Test
  @DisplayName("constant through an abstract base's getter writes on the concrete subclass")
  void constantThroughAnAbstractBase() {
    final var result = Telescope.mapper(
      SuperRowSource.class,
      SuperRowAbstractSub.class,
      constant(SuperRowAbstractBase::getName, "fixed")
    ).forward(SOURCE);
    assertSame(SuperRowAbstractSub.class, result.getClass());
    assertEquals("fixed", result.getName());
    assertEquals(7, result.getN());
  }

  @Test
  @DisplayName("compute through an abstract base's getter writes on the concrete subclass")
  void computeThroughAnAbstractBase() {
    final var result = Telescope.mapper(
      SuperRowSource.class,
      SuperRowAbstractSub.class,
      compute(SuperRowAbstractBase::getName, () -> "made")
    ).forward(SOURCE);
    assertSame(SuperRowAbstractSub.class, result.getClass());
    assertEquals("made", result.getName());
    assertEquals(7, result.getN());
  }

  @Test
  @DisplayName("constant through a generic base's getter writes on the subclass that fixes its type")
  void constantThroughAGenericBase() {
    final var result = Telescope.mapper(
      SuperRowSource.class,
      SuperRowGenericSub.class,
      constant(SuperRowGenericBase<String>::getName, "fixed")
    ).forward(SOURCE);
    assertSame(SuperRowGenericSub.class, result.getClass());
    assertEquals("fixed", result.getName());
    assertEquals(7, result.getN());
  }

  @Test
  @DisplayName("compute through a generic base's getter writes on the subclass that fixes its type")
  void computeThroughAGenericBase() {
    final var result = Telescope.mapper(
      SuperRowSource.class,
      SuperRowGenericSub.class,
      compute(SuperRowGenericBase<String>::getName, () -> "made")
    ).forward(SOURCE);
    assertSame(SuperRowGenericSub.class, result.getClass());
    assertEquals("made", result.getName());
    assertEquals(7, result.getN());
  }

  @Test
  @DisplayName("mapperForward: constant through a concrete base's getter keeps the subclass")
  void forwardOnlyConstantThroughAConcreteBase() {
    final var result = Telescope.mapperForward(
      SuperRowSource.class,
      SuperRowConcreteSub.class,
      constant(SuperRowConcreteBase::getName, "fixed")
    ).forward(SOURCE);
    assertSame(SuperRowConcreteSub.class, result.getClass());
    assertEquals("fixed", result.getName());
    assertEquals(7, result.getN());
  }

  @Test
  @DisplayName("mapperForward: compute through a concrete base's getter keeps the subclass")
  void forwardOnlyComputeThroughAConcreteBase() {
    final var result = Telescope.mapperForward(
      SuperRowSource.class,
      SuperRowConcreteSub.class,
      compute(SuperRowConcreteBase::getName, () -> "made")
    ).forward(SOURCE);
    assertSame(SuperRowConcreteSub.class, result.getClass());
    assertEquals("made", result.getName());
    assertEquals(7, result.getN());
  }

  @Test
  @DisplayName("mapperForward: constant through an abstract base's getter writes on the subclass")
  void forwardOnlyConstantThroughAnAbstractBase() {
    final var result = Telescope.mapperForward(
      SuperRowSource.class,
      SuperRowAbstractSub.class,
      constant(SuperRowAbstractBase::getName, "fixed")
    ).forward(SOURCE);
    assertSame(SuperRowAbstractSub.class, result.getClass());
    assertEquals("fixed", result.getName());
    assertEquals(7, result.getN());
  }

  @Test
  @DisplayName("mapperForward: compute through an abstract base's getter writes on the subclass")
  void forwardOnlyComputeThroughAnAbstractBase() {
    final var result = Telescope.mapperForward(
      SuperRowSource.class,
      SuperRowAbstractSub.class,
      compute(SuperRowAbstractBase::getName, () -> "made")
    ).forward(SOURCE);
    assertSame(SuperRowAbstractSub.class, result.getClass());
    assertEquals("made", result.getName());
    assertEquals(7, result.getN());
  }

  @Test
  @DisplayName("mapperForward: constant through a generic base's getter writes on the subclass")
  void forwardOnlyConstantThroughAGenericBase() {
    final var result = Telescope.mapperForward(
      SuperRowSource.class,
      SuperRowGenericSub.class,
      constant(SuperRowGenericBase<String>::getName, "fixed")
    ).forward(SOURCE);
    assertSame(SuperRowGenericSub.class, result.getClass());
    assertEquals("fixed", result.getName());
    assertEquals(7, result.getN());
  }

  @Test
  @DisplayName("mapperForward: compute through a generic base's getter writes on the subclass")
  void forwardOnlyComputeThroughAGenericBase() {
    final var result = Telescope.mapperForward(
      SuperRowSource.class,
      SuperRowGenericSub.class,
      compute(SuperRowGenericBase<String>::getName, () -> "made")
    ).forward(SOURCE);
    assertSame(SuperRowGenericSub.class, result.getClass());
    assertEquals("made", result.getName());
    assertEquals(7, result.getN());
  }

  @Test
  @DisplayName("mapperBuilder: a constant row through a base's getter writes on the subclass")
  void builtMapperConstantThroughAnAbstractBase() {
    final var result = Telescope.mapperBuilder(SuperRowSource.class, SuperRowAbstractSub.class)
      .add(constant(SuperRowAbstractBase::getName, "fixed"))
      .build()
      .forward(SOURCE);
    assertSame(SuperRowAbstractSub.class, result.getClass());
    assertEquals("fixed", result.getName());
    assertEquals(7, result.getN());
  }

  @Test
  @DisplayName("constant through a base-rooted telescope writes on the subclass")
  void constantThroughABaseRootedTelescope() {
    final var result = Telescope.mapperForward(
      SuperRowSource.class,
      SuperRowAbstractSub.class,
      constant(Telescope.of(SuperRowAbstractBase.class).field(SuperRowAbstractBase::getName), "fixed")
    ).forward(SOURCE);
    assertSame(SuperRowAbstractSub.class, result.getClass());
    assertEquals("fixed", result.getName());
    assertEquals(7, result.getN());
  }

  @Test
  @DisplayName("constant through a base-rooted nested path keeps the subclass and the rest of the path")
  void constantThroughABaseRootedNestedPath() {
    final var result = Telescope.mapperForward(
      SuperRowHolderSource.class,
      SuperRowHolderSub.class,
      constant(Telescope.of(SuperRowHolderBase.class).field(SuperRowHolderBase::getChild).field(SuperRowSource::n), 9)
    ).forward(new SuperRowHolderSource(7, new SuperRowSource(1), List.of("a", "b")));
    assertSame(SuperRowHolderSub.class, result.getClass());
    assertEquals(new SuperRowSource(9), result.getChild());
    assertEquals(List.of("a", "b"), result.getTags());
    assertEquals(7, result.getN());
  }

  @Test
  @DisplayName("constant through every element of a base's list keeps the subclass")
  void constantThroughEveryElementOfABaseList() {
    final var result = Telescope.mapperForward(
      SuperRowHolderSource.class,
      SuperRowHolderSub.class,
      constant(Telescope.of(SuperRowHolderBase.class).each(SuperRowHolderBase::getTags), "x")
    ).forward(new SuperRowHolderSource(7, new SuperRowSource(1), List.of("a", "b")));
    assertSame(SuperRowHolderSub.class, result.getClass());
    assertEquals(List.of("x", "x"), result.getTags());
    assertEquals(new SuperRowSource(1), result.getChild());
    assertEquals(7, result.getN());
  }

  @Test
  @DisplayName("constant through an abstract base's getter converts as the generated bridge's @Constant does")
  void constantMatchesTheGeneratedBridge() {
    final var runtime = Telescope.mapper(
      SuperRowBridged.class,
      SuperRowAbstractSub.class,
      constant(SuperRowAbstractBase::getName, "fixed")
    ).forward(new SuperRowBridged(7));
    final var generated = SuperRowBridgedBridge.BRIDGE.read(new SuperRowBridged(7));
    assertSame(generated.getClass(), runtime.getClass());
    assertEquals(generated.getName(), runtime.getName());
    assertEquals(generated.getN(), runtime.getN());
  }

  @Test
  @DisplayName("compute through a generic base's getter converts as the generated bridge's @Compute does")
  void computeMatchesTheGeneratedBridge() {
    final var runtime = Telescope.mapper(
      SuperRowComputedBridged.class,
      SuperRowGenericSub.class,
      compute(SuperRowGenericBase<String>::getName, new SuperRowNameSupplier())
    ).forward(new SuperRowComputedBridged(7));
    final var generated = SuperRowComputedBridgedBridge.BRIDGE.read(new SuperRowComputedBridged(7));
    assertSame(generated.getClass(), runtime.getClass());
    assertEquals(generated.getName(), runtime.getName());
    assertEquals(generated.getN(), runtime.getN());
  }

  @Test
  @DisplayName("a constant row whose accessor's class is not the target or a superclass of it is refused by name")
  void constantOnAnUnrelatedClassIsRefused() {
    final var failure = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapperForward(
        SuperRowSource.class,
        SuperRowAbstractSub.class,
        constant(SuperRowConcreteBase::getName, "fixed")
      )
    );
    assertTrue(failure.getMessage().contains("SuperRowConcreteBase"), failure.getMessage());
    assertTrue(failure.getMessage().contains("SuperRowAbstractSub"), failure.getMessage());
    assertTrue(failure.getMessage().contains("name"), failure.getMessage());
  }
}
