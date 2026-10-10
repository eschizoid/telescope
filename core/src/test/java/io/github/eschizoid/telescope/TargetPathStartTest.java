package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.constant;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A target path a row writes the mapper's target through either writes on the target class or is
 * refused when the mapper is built. Steps that keep the focus on the root value, observers and
 * narrowings, are carried through when the path is started again at the target; a later step that
 * records no component, such as a one-sided iso or {@code fieldByName}, is kept after the root
 * component hop. A path that cannot be started again at the target, because its first step off the
 * root names no component, is refused unless it starts at the target.
 */
class TargetPathStartTest {

  private static final SuperRowSource SOURCE = new SuperRowSource(7);

  private static String refusal(final Runnable build) {
    return assertThrows(IllegalArgumentException.class, build::run).getMessage();
  }

  @Nested
  @DisplayName("observe")
  class Observe {

    @Test
    @DisplayName("an observer after a concrete base's field keeps the subclass and still observes the write")
    void observerAfterAConcreteBaseField() {
      final var seen = new ArrayList<String>();
      final var result = Telescope.mapperForward(
        SuperRowSource.class,
        SuperRowConcreteSub.class,
        constant(Telescope.of(SuperRowConcreteBase.class).field(SuperRowConcreteBase::getName).observe(seen::add), "x")
      ).forward(SOURCE);
      assertSame(SuperRowConcreteSub.class, result.getClass());
      assertEquals("x", result.getName());
      assertEquals(7, result.getN());
      assertEquals(List.of("x"), seen);
    }

    @Test
    @DisplayName("an observer after an abstract base's field writes on the subclass")
    void observerAfterAnAbstractBaseField() {
      final var result = Telescope.mapperForward(
        SuperRowSource.class,
        SuperRowAbstractSub.class,
        constant(
          Telescope.of(SuperRowAbstractBase.class)
            .field(SuperRowAbstractBase::getName)
            .observe(v -> {}),
          "x"
        )
      ).forward(SOURCE);
      assertSame(SuperRowAbstractSub.class, result.getClass());
      assertEquals("x", result.getName());
      assertEquals(7, result.getN());
    }

    @Test
    @DisplayName("an observer on the root value is carried through and sees the subclass")
    void observerOnTheRootValue() {
      final var seen = new ArrayList<Object>();
      final var result = Telescope.mapperForward(
        SuperRowSource.class,
        SuperRowAbstractSub.class,
        constant(Telescope.of(SuperRowAbstractBase.class).observe(seen::add).field(SuperRowAbstractBase::getName), "x")
      ).forward(SOURCE);
      assertSame(SuperRowAbstractSub.class, result.getClass());
      assertEquals("x", result.getName());
      assertEquals(1, seen.size());
      assertSame(SuperRowAbstractSub.class, seen.getFirst().getClass());
    }

    @Test
    @DisplayName("to(src, path.observe) rooted at a concrete base keeps the subclass, and maps back")
    void accessorToAnObservedBasePath() {
      final var source = new SuperRowLabelSource(7, "lbl");
      final var mapper = Telescope.mapper(
        SuperRowLabelSource.class,
        SuperRowConcreteSub.class,
        to(
          SuperRowLabelSource::label,
          Telescope.of(SuperRowConcreteBase.class)
            .field(SuperRowConcreteBase::getName)
            .observe(v -> {})
        )
      );
      final var result = mapper.forward(source);
      assertSame(SuperRowConcreteSub.class, result.getClass());
      assertEquals("lbl", result.getName());
      assertEquals(source, mapper.backward(result));
    }

    @Test
    @DisplayName("an observed path through a class the target does not extend is refused when the mapper is built")
    void observedPathOnAnUnrelatedClassIsRefused() {
      assertEquals(
        PairingMessages.targetRowOffTarget(
          "SuperRowSource",
          "SuperRowConcreteSub",
          "constant",
          "SuperRowNamedBean",
          "name"
        ),
        refusal(() ->
          Telescope.mapperForward(
            SuperRowSource.class,
            SuperRowConcreteSub.class,
            constant(
              Telescope.of(SuperRowNamedBean.class)
                .field(SuperRowNamedBean::getName)
                .observe(v -> {}),
              "x"
            )
          )
        )
      );
    }
  }

  @Nested
  @DisplayName("as(...)")
  class Narrow {

    @Test
    @DisplayName("a narrowing to the concrete base before its field keeps the subclass")
    void narrowingToTheConcreteBase() {
      final var result = Telescope.mapperForward(
        SuperRowSource.class,
        SuperRowConcreteSub.class,
        constant(
          Telescope.of(SuperRowConcreteBase.class).as(SuperRowConcreteBase.class).field(SuperRowConcreteBase::getName),
          "x"
        )
      ).forward(SOURCE);
      assertSame(SuperRowConcreteSub.class, result.getClass());
      assertEquals("x", result.getName());
      assertEquals(7, result.getN());
    }

    @Test
    @DisplayName("a narrowing to the abstract base before its field writes on the subclass")
    void narrowingToTheAbstractBase() {
      final var result = Telescope.mapperForward(
        SuperRowSource.class,
        SuperRowAbstractSub.class,
        constant(
          Telescope.of(SuperRowAbstractBase.class).as(SuperRowAbstractBase.class).field(SuperRowAbstractBase::getName),
          "x"
        )
      ).forward(SOURCE);
      assertSame(SuperRowAbstractSub.class, result.getClass());
      assertEquals("x", result.getName());
      assertEquals(7, result.getN());
    }

    @Test
    @DisplayName("a narrowing to a class the target does not extend is refused when the mapper is built")
    void narrowingToAnUnrelatedClassIsRefused() {
      assertEquals(
        PairingMessages.targetPathNarrowsOffTarget(
          "SuperRowSource",
          "SuperRowConcreteSub",
          "constant",
          "SuperRowNamedBean"
        ),
        refusal(() ->
          Telescope.mapperForward(
            SuperRowSource.class,
            SuperRowConcreteSub.class,
            constant(
              Telescope.of(SuperRowNamedBean.class).as(SuperRowNamedBean.class).field(SuperRowNamedBean::getName),
              "x"
            )
          )
        )
      );
    }

    @Test
    @DisplayName("a narrowing to a subclass of the target never matches the target and is refused")
    void narrowingToASubclassOfTheTargetIsRefused() {
      assertEquals(
        PairingMessages.targetPathNarrowsOffTarget(
          "SuperRowSource",
          "SuperRowConcreteBase",
          "constant",
          "SuperRowConcreteSub"
        ),
        refusal(() ->
          Telescope.mapperForward(
            SuperRowSource.class,
            SuperRowConcreteBase.class,
            constant(
              Telescope.of(SuperRowConcreteBase.class).as(SuperRowConcreteSub.class).field(SuperRowConcreteSub::getN),
              5
            )
          )
        )
      );
    }
  }

  @Nested
  @DisplayName("steps that record no component")
  class Unrecorded {

    @Test
    @DisplayName("a one-sided iso after a concrete base's field keeps the subclass")
    void onesidedIsoAfterABaseField() {
      final var result = Telescope.mapperForward(
        SuperRowSource.class,
        SuperRowConcreteSub.class,
        constant(
          Telescope.of(SuperRowConcreteBase.class)
            .field(SuperRowConcreteBase::getName)
            .after(v -> v),
          "x"
        )
      ).forward(SOURCE);
      assertSame(SuperRowConcreteSub.class, result.getClass());
      assertEquals("x", result.getName());
      assertEquals(7, result.getN());
    }

    @Test
    @DisplayName("fieldByName after an abstract base's field writes on the subclass")
    void fieldByNameAfterAnAbstractBaseField() {
      final var result = Telescope.mapperForward(
        SuperRowHolderSource.class,
        SuperRowHolderSub.class,
        constant(
          Telescope.of(SuperRowHolderBase.class).field(SuperRowHolderBase::getChild).fieldByName("n", Integer.class),
          9
        )
      ).forward(new SuperRowHolderSource(7, new SuperRowSource(1), List.of("a"), "lbl"));
      assertSame(SuperRowHolderSub.class, result.getClass());
      assertEquals(new SuperRowSource(9), result.getChild());
      assertEquals(7, result.getN());
    }

    @Test
    @DisplayName("fieldByName as the first step reads the property on the target and is kept")
    void fieldByNameFirstIsKept() {
      final var result = Telescope.mapperForward(
        SuperRowSource.class,
        SuperRowAbstractSub.class,
        constant(Telescope.ofBean(SuperRowAbstractBase.class).fieldByName("name", String.class), "x")
      ).forward(SOURCE);
      assertSame(SuperRowAbstractSub.class, result.getClass());
      assertEquals("x", result.getName());
      assertEquals(7, result.getN());
    }

    @Test
    @DisplayName("an iso as the first step off a base root is refused when the mapper is built")
    void isoFirstOffABaseRootIsRefused() {
      assertEquals(
        PairingMessages.targetPathNotOnTarget(
          "SuperRowSource",
          "SuperRowConcreteSub",
          "constant",
          "SuperRowConcreteBase"
        ),
        refusal(() ->
          Telescope.mapperForward(
            SuperRowSource.class,
            SuperRowConcreteSub.class,
            constant(
              Telescope.of(SuperRowConcreteBase.class)
                .then(Telescope.<SuperRowConcreteBase, SuperRowConcreteBase>iso(b -> b, b -> b))
                .field(SuperRowConcreteBase::getName),
              "x"
            )
          )
        )
      );
    }

    @Test
    @DisplayName("a lens on a base's getter is refused when the mapper is built")
    void lensOnABaseIsRefused() {
      assertEquals(
        PairingMessages.targetPathNotOnTarget(
          "SuperRowSource",
          "SuperRowConcreteSub",
          "constant",
          "SuperRowConcreteBase"
        ),
        refusal(() ->
          Telescope.mapperForward(
            SuperRowSource.class,
            SuperRowConcreteSub.class,
            constant(
              Telescope.lens(SuperRowConcreteBase::getName, (final SuperRowConcreteBase b, final String v) -> b),
              "x"
            )
          )
        )
      );
    }

    @Test
    @DisplayName("a lens started at the target itself is kept")
    void lensOnTheTargetIsKept() {
      final var result = Telescope.mapperForward(
        SuperRowSource.class,
        SuperRowConcreteSub.class,
        constant(
          Telescope.lens(SuperRowConcreteSub::getName, (final SuperRowConcreteSub b, final String v) -> {
            final var copy = new SuperRowConcreteSub();
            copy.setName(v);
            copy.setN(b.getN());
            return copy;
          }),
          "x"
        )
      ).forward(SOURCE);
      assertSame(SuperRowConcreteSub.class, result.getClass());
      assertEquals("x", result.getName());
      assertEquals(7, result.getN());
    }

    @Test
    @DisplayName("a path that writes a base root value itself is refused when the mapper is built")
    void wholeBaseRootIsRefused() {
      assertEquals(
        PairingMessages.targetPathNotOnTarget(
          "SuperRowSource",
          "SuperRowConcreteSub",
          "constant",
          "SuperRowConcreteBase"
        ),
        refusal(() ->
          Telescope.mapperForward(
            SuperRowSource.class,
            SuperRowConcreteSub.class,
            constant(Telescope.of(SuperRowConcreteBase.class), new SuperRowConcreteBase())
          )
        )
      );
    }
  }
}
