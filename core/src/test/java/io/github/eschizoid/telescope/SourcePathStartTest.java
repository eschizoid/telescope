package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.when;
import static io.github.eschizoid.telescope.mapping.Mapping.zip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.mapping.MapStep;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A source path a row reads the mapper's source through either reads on the source class or is
 * refused when the mapper is built. A path whose first component hop reads the source or a class it
 * extends is started again at the source, so a write back through it rebuilds the source. One whose
 * first component hop reads an interface a subclass of the source may implement is kept, and each
 * source it is given is checked to implement it; one whose first component hop reads any other
 * class is refused. A narrowing to a subclass of the source is kept, since it reads only a source
 * of that subclass. A path that cannot be started again, because its first step off the root names
 * no component, is refused when no source can be an instance of its root class; started at a
 * subclass or at such an interface, each source it is given is checked to be an instance of it;
 * started at a superclass or at an unknown class, each write's result is checked to be a source
 * instance or null. A row under {@code when} is refused, or fails on a source its path cannot read,
 * with the message the unwrapped row gives, before its predicate runs.
 */
class SourcePathStartTest {

  /** A source that nests a different class, which converts to the mapper's own target class. */
  record Twig(String label, String note, Twig child) {}

  record Tree(String label, String note, Twig child) {}

  record TreeDto(String label, String note, TreeDto child) {}

  private static String refusal(final Runnable build) {
    return assertThrows(IllegalArgumentException.class, build::run).getMessage();
  }

  private static String failedCall(final Runnable call) {
    return assertThrows(IllegalStateException.class, call::run).getMessage();
  }

  private static String treeRefusal(final MapStep row) {
    return refusal(() -> Telescope.mapper(Tree.class, TreeDto.class, row));
  }

  private static SourceRootBase base(final String label) {
    final var base = new SourceRootBase();
    base.setLabel(label);
    return base;
  }

  @Nested
  @DisplayName("A source path rooted at a class the source neither is nor extends is refused at build")
  class Refused {

    private final Telescope<Twig, String> twigLabel = Telescope.of(Twig.class).field(Twig::label);
    private final Telescope<TreeDto, String> treeNote = Telescope.of(TreeDto.class).field(TreeDto::note);
    private final String toRefusal = PairingMessages.sourceRowOffSource("Tree", "TreeDto", "to", "Twig", "label");

    @Test
    @DisplayName("to(path, path) through an unrelated record")
    void pathOntoPath() {
      assertEquals(toRefusal, treeRefusal(to(twigLabel, treeNote)));
    }

    @Test
    @DisplayName("to(path, accessor) through an unrelated record")
    void pathOntoAccessor() {
      assertEquals(toRefusal, treeRefusal(to(twigLabel, TreeDto::note)));
    }

    @Test
    @DisplayName("zip through an unrelated record")
    void zipThroughAnUnrelatedRecord() {
      assertEquals(
        PairingMessages.sourceRowOffSource("Tree", "TreeDto", "zip", "Twig", "label"),
        treeRefusal(zip(twigLabel, treeNote))
      );
    }

    @Test
    @DisplayName("each of those rows under when is refused with the unwrapped row's message")
    void underWhen() {
      assertEquals(toRefusal, treeRefusal(when(t -> true, to(twigLabel, treeNote))));
      assertEquals(toRefusal, treeRefusal(when(t -> true, to(twigLabel, TreeDto::note))));
      assertEquals(treeRefusal(zip(twigLabel, treeNote)), treeRefusal(when(t -> true, zip(twigLabel, treeNote))));
    }

    @Test
    @DisplayName("a bean path through an unrelated class with a same-named property")
    void unrelatedBeanWithTheSameProperty() {
      assertEquals(
        PairingMessages.sourceRowOffSource("SourceRootSub", "SourceRootDto", "to", "SourceRootOther", "label"),
        refusal(() ->
          Telescope.mapperForward(
            SourceRootSub.class,
            SourceRootDto.class,
            to(Telescope.ofBean(SourceRootOther.class).field(SourceRootOther::getLabel), SourceRootDto::note)
          )
        )
      );
    }

    @Test
    @DisplayName("a path whose first component is read on a subclass of the source")
    void componentOfASubclass() {
      assertEquals(
        PairingMessages.sourceRowOffSource("SourceRootBase", "SourceRootNoteDto", "to", "SourceRootSub", "label"),
        refusal(() ->
          Telescope.mapperForward(
            SourceRootBase.class,
            SourceRootNoteDto.class,
            to(Telescope.ofBean(SourceRootSub.class).field(SourceRootSub::getLabel), SourceRootNoteDto::note)
          )
        )
      );
    }

    @Test
    @DisplayName("a narrowing to a class the source neither is, extends, nor is extended by")
    void narrowingToAnUnrelatedClass() {
      assertEquals(
        PairingMessages.sourcePathNarrowsOffSource("SourceRootSub", "SourceRootDto", "to", "SourceRootOther"),
        refusal(() ->
          Telescope.mapperForward(
            SourceRootSub.class,
            SourceRootDto.class,
            to(
              Telescope.of(Object.class).as(SourceRootOther.class).field(SourceRootOther::getLabel),
              SourceRootDto::note
            )
          )
        )
      );
    }

    @Test
    @DisplayName("a lens through an unrelated class")
    void lensThroughAnUnrelatedClass() {
      assertEquals(
        PairingMessages.sourcePathNotOnSource("SourceRootSub", "SourceRootDto", "to", "SourceRootOther"),
        refusal(() ->
          Telescope.mapperForward(
            SourceRootSub.class,
            SourceRootDto.class,
            to(Telescope.lens(SourceRootOther::getLabel, (SourceRootOther o, String v) -> o), SourceRootDto::note)
          )
        )
      );
    }
  }

  @Nested
  @DisplayName("A source path rooted at a superclass of the source maps back onto the source")
  class Superclass {

    @Test
    @DisplayName("a path through a concrete base's getter maps back to the subclass with every property")
    void concreteBase() {
      final var mapper = Telescope.mapper(
        SourceRootSub.class,
        SourceRootDto.class,
        to(Telescope.ofBean(SourceRootBase.class).field(SourceRootBase::getLabel), SourceRootDto::note)
      );
      assertEquals(new SourceRootDto("lbl", 3), mapper.forward(new SourceRootSub("lbl", 3)));
      final var back = mapper.backward(new SourceRootDto("nn", 4));
      assertSame(SourceRootSub.class, back.getClass());
      assertEquals("nn", back.getLabel());
      assertEquals(4, back.getN());
    }

    @Test
    @DisplayName("to(path, path) and zip through an abstract base's getter map back to the subclass")
    void abstractBase() {
      final var path = Telescope.ofBean(SourceRootAbstractBase.class).field(SourceRootAbstractBase::getLabel);
      final var note = Telescope.of(SourceRootDto.class).field(SourceRootDto::note);
      for (final var row : new MapStep[] { to(path, note), zip(path, note) }) {
        final var mapper = Telescope.mapper(SourceRootAbstractSub.class, SourceRootDto.class, row);
        assertEquals(new SourceRootDto("lbl", 3), mapper.forward(new SourceRootAbstractSub("lbl", 3)));
        final var back = mapper.backward(new SourceRootDto("nn", 4));
        assertSame(SourceRootAbstractSub.class, back.getClass());
        assertEquals("nn", back.getLabel());
        assertEquals(4, back.getN());
      }
    }

    @Test
    @DisplayName("a lens on a superclass whose write builds the superclass fails by name on the way back")
    void lensBuildingTheSuperclass() {
      final var mapper = Telescope.mapper(
        SourceRootSub.class,
        SourceRootDto.class,
        to(Telescope.lens(SourceRootBase::getLabel, (SourceRootBase b, String v) -> base(v)), SourceRootDto::note)
      );
      assertEquals(new SourceRootDto("lbl", 3), mapper.forward(new SourceRootSub("lbl", 3)));
      assertEquals(
        PairingMessages.sourcePathWroteOffSource("SourceRootSub", "SourceRootDto", "to", "SourceRootBase"),
        failedCall(() -> mapper.backward(new SourceRootDto("nn", 4)))
      );
    }
  }

  @Nested
  @DisplayName("A source path rooted at a subclass of the source reads only that subclass")
  class Subclass {

    private final Telescope<SourceRootSub, String> subLabel = Telescope.lens(
      SourceRootSub::getLabel,
      (SourceRootSub s, String v) -> {
        s.setLabel(v);
        return s;
      }
    );

    @Test
    @DisplayName("a lens on a subclass reads a source of that subclass")
    void readsTheSubclass() {
      final var mapper = Telescope.mapperForward(
        SourceRootBase.class,
        SourceRootNoteDto.class,
        to(subLabel, SourceRootNoteDto::note)
      );
      assertEquals(new SourceRootNoteDto("lbl"), mapper.forward(new SourceRootSub("lbl", 3)));
    }

    @Test
    @DisplayName("a lens on a subclass given a source of another class fails by name")
    void failsByNameOnAnotherClass() {
      final var mapper = Telescope.mapperForward(
        SourceRootBase.class,
        SourceRootNoteDto.class,
        to(subLabel, SourceRootNoteDto::note)
      );
      assertEquals(
        PairingMessages.sourcePathGivenOffRoot(
          "SourceRootBase",
          "SourceRootNoteDto",
          "to",
          "SourceRootSub",
          "SourceRootBase"
        ),
        failedCall(() -> mapper.forward(base("lbl")))
      );
    }

    @Test
    @DisplayName("a narrowing to a subclass before its getter is kept and reads a source of that subclass")
    void narrowingToASubclass() {
      final var mapper = Telescope.mapperForward(
        SourceRootBase.class,
        SourceRootNoteDto.class,
        to(
          Telescope.ofBean(SourceRootBase.class).as(SourceRootSub.class).field(SourceRootSub::getLabel),
          SourceRootNoteDto::note
        )
      );
      assertEquals(new SourceRootNoteDto("lbl"), mapper.forward(new SourceRootSub("lbl", 3)));
    }
  }

  @Nested
  @DisplayName("A source path rooted at an interface a source may implement")
  class Interface {

    @Test
    @DisplayName("a component of the interface on a non-final source reads a source that implements it")
    void componentOnANonFinalSource() {
      final var mapper = Telescope.mapperForward(
        SourceRootBase.class,
        SourceRootNoteDto.class,
        to(Telescope.ofBean(SourceRootNamed.class).field(SourceRootNamed::getLabel), SourceRootNoteDto::note)
      );
      final var named = new SourceRootNamedBase();
      named.setLabel("IL");
      assertEquals(new SourceRootNoteDto("IL"), mapper.forward(named));
      assertEquals(
        PairingMessages.sourcePathGivenOffRoot(
          "SourceRootBase",
          "SourceRootNoteDto",
          "to",
          "SourceRootNamed",
          "SourceRootBase"
        ),
        failedCall(() -> mapper.forward(base("b")))
      );
    }

    @Test
    @DisplayName("a lens on the interface on a non-final source reads a source that implements it")
    void lensOnANonFinalSource() {
      final var mapper = Telescope.mapperForward(
        SourceRootBase.class,
        SourceRootNoteDto.class,
        to(Telescope.lens(SourceRootNamed::getLabel, (SourceRootNamed n, String v) -> n), SourceRootNoteDto::note)
      );
      final var named = new SourceRootNamedBase();
      named.setLabel("IL");
      assertEquals(new SourceRootNoteDto("IL"), mapper.forward(named));
    }

    @Test
    @DisplayName("a component of the interface on a final source class is refused at build")
    void componentOnAFinalSourceIsRefused() {
      assertEquals(
        PairingMessages.sourceRowOffSource("SourceRootSub", "SourceRootDto", "to", "SourceRootNamed", "label"),
        refusal(() ->
          Telescope.mapperForward(
            SourceRootSub.class,
            SourceRootDto.class,
            to(Telescope.ofBean(SourceRootNamed.class).field(SourceRootNamed::getLabel), SourceRootDto::note)
          )
        )
      );
    }

    @Test
    @DisplayName("a lens on the interface on a record source is refused at build")
    void lensOnARecordSourceIsRefused() {
      assertEquals(
        PairingMessages.sourcePathNotOnSource("Tree", "TreeDto", "to", "SourceRootNamed"),
        treeRefusal(to(Telescope.lens(SourceRootNamed::getLabel, (SourceRootNamed n, String v) -> n), TreeDto::note))
      );
    }
  }

  @Nested
  @DisplayName("A when row checks its source path before its predicate")
  class When {

    private final Telescope<SourceRootSub, String> subLabel = Telescope.lens(
      SourceRootSub::getLabel,
      (SourceRootSub s, String v) -> s
    );

    @Test
    @DisplayName("a source the path cannot read fails with the bare row's message, not inside the predicate")
    void failsAsTheBareRow() {
      final var bare = Telescope.mapperForward(
        SourceRootBase.class,
        SourceRootNoteDto.class,
        to(subLabel, SourceRootNoteDto::note)
      );
      final var gated = Telescope.mapperForward(
        SourceRootBase.class,
        SourceRootNoteDto.class,
        when(s -> true, to(subLabel, SourceRootNoteDto::note))
      );
      assertEquals(failedCall(() -> bare.forward(base("b"))), failedCall(() -> gated.forward(base("b"))));
    }

    @Test
    @DisplayName("a source the path can read reaches the predicate and the row applies")
    void appliesOnASourceThePathReads() {
      final var gated = Telescope.mapperForward(
        SourceRootBase.class,
        SourceRootNoteDto.class,
        when(s -> s.getN() == 3, to(subLabel, SourceRootNoteDto::note))
      );
      assertEquals(new SourceRootNoteDto("lbl"), gated.forward(new SourceRootSub("lbl", 3)));
      assertEquals(new SourceRootNoteDto(null), gated.forward(new SourceRootSub("lbl", 4)));
    }
  }

  @Nested
  @DisplayName("Steps before the first component, and paths that never leave the root value")
  class LeadingSteps {

    @Test
    @DisplayName("a filter before a superclass getter is kept, and the path maps back to the subclass")
    void filterBeforeASuperclassGetter() {
      final var mapper = Telescope.mapper(
        SourceRootSub.class,
        SourceRootDto.class,
        to(
          Telescope.ofBean(SourceRootBase.class)
            .filter(b -> true)
            .field(SourceRootBase::getLabel),
          SourceRootDto::note
        )
      );
      final var back = mapper.backward(new SourceRootDto("nn", 4));
      assertSame(SourceRootSub.class, back.getClass());
      assertEquals("nn", back.getLabel());
      assertEquals(4, back.getN());
    }

    @Test
    @DisplayName("a narrowing to a superclass before its getter is kept, and the path maps back to the subclass")
    void narrowingToASuperclass() {
      final var mapper = Telescope.mapper(
        SourceRootSub.class,
        SourceRootDto.class,
        to(
          Telescope.ofBean(SourceRootBase.class).as(SourceRootBase.class).field(SourceRootBase::getLabel),
          SourceRootDto::note
        )
      );
      final var back = mapper.backward(new SourceRootDto("nn", 4));
      assertSame(SourceRootSub.class, back.getClass());
      assertEquals("nn", back.getLabel());
    }

    @Test
    @DisplayName("a narrowing that always matches does not hide a later one that never does")
    void alwaysMatchingNarrowingBeforeAnUnrelatedOne() {
      assertEquals(
        PairingMessages.sourcePathNarrowsOffSource("SourceRootSub", "SourceRootDto", "to", "SourceRootOther"),
        refusal(() ->
          Telescope.mapperForward(
            SourceRootSub.class,
            SourceRootDto.class,
            to(
              Telescope.of(Object.class).as(Object.class).as(SourceRootOther.class).field(SourceRootOther::getLabel),
              SourceRootDto::note
            )
          )
        )
      );
    }

    @Test
    @DisplayName("a path by fieldByName through an unrelated class reads the property by name")
    void fieldByNameReadsByName() {
      final var mapper = Telescope.mapperForward(
        SourceRootSub.class,
        SourceRootDto.class,
        to(Telescope.ofBean(SourceRootOther.class).fieldByName("label", String.class), SourceRootDto::note)
      );
      assertEquals(new SourceRootDto("lbl", 3), mapper.forward(new SourceRootSub("lbl", 3)));
    }

    @Test
    @DisplayName("a path whose first step is a one-sided iso on an unrelated class is refused at build")
    void oneSidedIsoOnAnUnrelatedClass() {
      assertEquals(
        PairingMessages.sourcePathNotOnSource("SourceRootSub", "SourceRootDto", "to", "SourceRootOther"),
        refusal(() ->
          Telescope.mapperForward(
            SourceRootSub.class,
            SourceRootDto.class,
            to(
              Telescope.ofBean(SourceRootOther.class)
                .after(o -> o)
                .field(SourceRootOther::getLabel),
              SourceRootDto::note
            )
          )
        )
      );
    }

    @Test
    @DisplayName("the root value of a superclass path reads the source, and a write back must be a source")
    void rootValueOfASuperclass() {
      final var mapper = Telescope.mapper(
        SourceRootSub.class,
        SourceRootWholeDto.class,
        to(Telescope.ofBean(SourceRootBase.class), SourceRootWholeDto::whole)
      );
      final var source = new SourceRootSub("lbl", 3);
      assertSame(source, mapper.forward(source).whole());
      final var back = new SourceRootSub("nn", 4);
      assertSame(back, mapper.backward(new SourceRootWholeDto(back)));
      assertEquals(
        PairingMessages.sourcePathWroteOffSource("SourceRootSub", "SourceRootWholeDto", "to", "SourceRootBase"),
        failedCall(() -> mapper.backward(new SourceRootWholeDto(base("b"))))
      );
    }
  }

  @Nested
  @DisplayName("A source path whose write back is checked against the source")
  class WriteChecked {

    @Test
    @DisplayName("a lambda lens, whose root is unknown, reads the source and may write null back")
    void lambdaLensWritingNull() {
      final var mapper = Telescope.mapper(
        SourceRootSub.class,
        SourceRootDto.class,
        to(
          Telescope.lens((Function<SourceRootSub, String>) SourceRootBase::getLabel, (s, v) -> (SourceRootSub) null),
          SourceRootDto::note
        )
      );
      assertEquals(new SourceRootDto("lbl", 3), mapper.forward(new SourceRootSub("lbl", 3)));
      assertNull(mapper.backward(new SourceRootDto("nn", 4)));
    }

    @Test
    @DisplayName("a lambda lens whose write builds a superclass fails by name on the way back")
    void lambdaLensBuildingTheSuperclass() {
      final Telescope<Object, String> lens = Telescope.lens(
        (Function<Object, String>) o -> ((SourceRootBase) o).getLabel(),
        (o, v) -> base(v)
      );
      final var mapper = Telescope.mapper(SourceRootSub.class, SourceRootDto.class, to(lens, SourceRootDto::note));
      assertEquals(
        PairingMessages.sourcePathWroteOffSource("SourceRootSub", "SourceRootDto", "to", "SourceRootBase"),
        failedCall(() -> mapper.backward(new SourceRootDto("nn", 4)))
      );
    }

    @Test
    @DisplayName("a superclass lens whose write updates the instance it is given maps back to that subclass")
    void superclassLensUpdatingItsInstance() {
      final var mapper = Telescope.mapper(
        SourceRootSub.class,
        SourceRootDto.class,
        to(
          Telescope.lens(SourceRootBase::getLabel, (SourceRootBase b, String v) -> {
            b.setLabel(v);
            return b;
          }),
          SourceRootDto::note
        )
      );
      final var back = mapper.backward(new SourceRootDto("nn", 4));
      assertSame(SourceRootSub.class, back.getClass());
      assertEquals("nn", back.getLabel());
      assertEquals(4, back.getN());
    }
  }

  @Nested
  @DisplayName("A source path on a mapper whose source is an interface")
  class InterfaceSource {

    @Test
    @DisplayName("a lens on a class that is not final reads only a source of that class")
    void lensOnANonFinalClass() {
      final var mapper = Telescope.mapperForward(
        SourceRootNamed.class,
        SourceRootNoteDto.class,
        to(Telescope.lens(SourceRootOther::getLabel, (SourceRootOther o, String v) -> o), SourceRootNoteDto::note)
      );
      final var named = new SourceRootNamedBase();
      named.setLabel("IL");
      assertEquals(
        PairingMessages.sourcePathGivenOffRoot(
          "SourceRootNamed",
          "SourceRootNoteDto",
          "to",
          "SourceRootOther",
          "SourceRootNamedBase"
        ),
        failedCall(() -> mapper.forward(named))
      );
    }

    @Test
    @DisplayName("a lens on a final class that does not implement the interface is refused at build")
    void lensOnAFinalClassIsRefused() {
      assertEquals(
        PairingMessages.sourcePathNotOnSource("SourceRootNamed", "SourceRootNoteDto", "to", "SourceRootSub"),
        refusal(() ->
          Telescope.mapperForward(
            SourceRootNamed.class,
            SourceRootNoteDto.class,
            to(Telescope.lens(SourceRootSub::getLabel, (SourceRootSub s, String v) -> s), SourceRootNoteDto::note)
          )
        )
      );
    }
  }

  @Nested
  @DisplayName("Enums, their constants' bodies, and classes with no simple name")
  class EnumsAndUnnamedClasses {

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static MapStep afterThenLabel(
      final Class<?> root,
      final Telescope.Accessor<SourceRootNoteDto, String> target
    ) {
      final Telescope<Object, String> path = Telescope.ofBean((Class<Object>) root)
        .after(o -> o)
        .fieldByName("label", String.class);
      return to(path, (Telescope.Accessor) target);
    }

    @Test
    @DisplayName("an interface lens on an enum whose constants declare bodies is refused at build")
    void interfaceLensOnABodiedEnum() {
      assertEquals(
        PairingMessages.sourcePathNotOnSource("SourceRootBodied", "SourceRootNoteDto", "to", "SourceRootNamed"),
        refusal(() ->
          Telescope.mapperForward(
            SourceRootBodied.class,
            SourceRootNoteDto.class,
            to(Telescope.lens(SourceRootNamed::getLabel, (SourceRootNamed n, String v) -> n), SourceRootNoteDto::note)
          )
        )
      );
    }

    @Test
    @DisplayName("a path rooted at an enum constant's body, on an interface source, is refused and named by its enum")
    void constantBodyOnAnInterfaceSource() {
      assertEquals(
        PairingMessages.sourcePathNotOnSource("SourceRootNamed", "SourceRootNoteDto", "to", "SourceRootBodied"),
        refusal(() ->
          Telescope.mapperForward(
            SourceRootNamed.class,
            SourceRootNoteDto.class,
            afterThenLabel(SourceRootBodied.A.getClass(), SourceRootNoteDto::note)
          )
        )
      );
    }

    @Test
    @DisplayName("an enum constant with a body given to a path rooted at another class is named by its enum")
    void constantWithABodyIsNamedByItsEnum() {
      final var mapper = Telescope.mapperForward(
        SourceRootNamed.class,
        SourceRootNoteDto.class,
        to(Telescope.lens(SourceRootOther::getLabel, (SourceRootOther o, String v) -> o), SourceRootNoteDto::note)
      );
      assertEquals(
        PairingMessages.sourcePathGivenOffRoot(
          "SourceRootNamed",
          "SourceRootNoteDto",
          "to",
          "SourceRootOther",
          "SourceRootNamedBodied"
        ),
        failedCall(() -> mapper.forward(SourceRootNamedBodied.A))
      );
    }

    @Test
    @DisplayName("an anonymous class given to a path rooted at a subclass is named by its binary name")
    void anonymousClassIsNamedByItsBinaryName() {
      final var mapper = Telescope.mapperForward(
        SourceRootBase.class,
        SourceRootNoteDto.class,
        to(Telescope.lens(SourceRootSub::getLabel, (SourceRootSub s, String v) -> s), SourceRootNoteDto::note)
      );
      final var anonymous = new SourceRootBase() {};
      assertEquals(
        PairingMessages.sourcePathGivenOffRoot(
          "SourceRootBase",
          "SourceRootNoteDto",
          "to",
          "SourceRootSub",
          anonymous.getClass().getName()
        ),
        failedCall(() -> mapper.forward(anonymous))
      );
    }

    @Test
    @DisplayName("an interface lens on an interface source reads only a source implementing both")
    void interfaceLensOnAnInterfaceSource() {
      final var mapper = Telescope.mapperForward(
        SourceRootNamed.class,
        SourceRootNoteDto.class,
        to(Telescope.lens(SourceRootLabelled::getLabel, (SourceRootLabelled l, String v) -> l), SourceRootNoteDto::note)
      );
      assertEquals(
        PairingMessages.sourcePathGivenOffRoot(
          "SourceRootNamed",
          "SourceRootNoteDto",
          "to",
          "SourceRootLabelled",
          "SourceRootNamedBodied"
        ),
        failedCall(() -> mapper.forward(SourceRootNamedBodied.A))
      );
    }
  }
}
