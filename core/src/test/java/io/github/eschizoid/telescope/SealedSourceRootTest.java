package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A source path rooted at an interface is accepted on a sealed source only when some permitted case
 * can be an instance of that interface, through the permitted cases of each sealed case in turn,
 * and is otherwise refused at build as it is on a final source. A path rooted at a sealed interface
 * is refused on a source no permitted case of it can be an instance of.
 */
class SealedSourceRootTest {

  /** A sealed interface whose only case is a record that does not implement the root. */
  public sealed interface NoCase permits NoCaseRecord {}

  public record NoCaseRecord(String label) implements NoCase {}

  /** A sealed abstract class whose only case is a final class that does not implement the root. */
  public abstract static sealed class AbstractNoCase permits AbstractNoCaseFinal {

    public String getLabel() {
      return "abstract";
    }
  }

  public static final class AbstractNoCaseFinal extends AbstractNoCase {

    public AbstractNoCaseFinal() {}
  }

  /** A sealed interface with one case that implements the root and one that does not. */
  public sealed interface SomeCase permits NamedCase, PlainCase {}

  public record NamedCase(String label) implements SomeCase, SourceRootNamed {
    @Override
    public String getLabel() {
      return label;
    }
  }

  public record PlainCase(String label) implements SomeCase {}

  /**
   * A sealed abstract class whose case is non-sealed, so a subclass of it may implement the root.
   */
  public abstract static sealed class OpenCase permits OpenCaseNonSealed {}

  public static non-sealed class OpenCaseNonSealed extends OpenCase {

    public OpenCaseNonSealed() {}
  }

  /** A sealed interface root whose only case is a record unrelated to the mapper's source. */
  public sealed interface SealedRoot permits SealedRootRecord {
    String getLabel();
  }

  public record SealedRootRecord(String label) implements SealedRoot {
    @Override
    public String getLabel() {
      return label;
    }
  }

  private static String refusal(final Runnable build) {
    return assertThrows(IllegalArgumentException.class, build::run).getMessage();
  }

  private static final Telescope<SourceRootNamed, String> NAMED_LABEL = Telescope.ofBean(SourceRootNamed.class).field(
    SourceRootNamed::getLabel
  );

  @Test
  @DisplayName("a sealed interface whose only case is a record that does not implement the root is refused")
  void sealedInterfaceWithNoImplementingCase() {
    assertEquals(
      PairingMessages.sourceRowOffSource("NoCase", "SourceRootNoteDto", "to", "SourceRootNamed", "label"),
      refusal(() ->
        Telescope.mapperForward(NoCase.class, SourceRootNoteDto.class, to(NAMED_LABEL, SourceRootNoteDto::note))
      )
    );
  }

  @Test
  @DisplayName("a sealed abstract class whose only case is final and does not implement the root is refused")
  void sealedAbstractClassWithNoImplementingCase() {
    assertEquals(
      PairingMessages.sourceRowOffSource("AbstractNoCase", "SourceRootNoteDto", "to", "SourceRootNamed", "label"),
      refusal(() ->
        Telescope.mapperForward(AbstractNoCase.class, SourceRootNoteDto.class, to(NAMED_LABEL, SourceRootNoteDto::note))
      )
    );
  }

  @Test
  @DisplayName("a sealed interface with one implementing case reads that case and fails by name on the other")
  void sealedInterfaceWithAnImplementingCase() {
    final var mapper = Telescope.mapperForward(
      SomeCase.class,
      SourceRootNoteDto.class,
      to(NAMED_LABEL, SourceRootNoteDto::note)
    );
    assertEquals(new SourceRootNoteDto("n"), mapper.forward(new NamedCase("n")));
    assertEquals(
      PairingMessages.sourcePathGivenOffRoot("SomeCase", "SourceRootNoteDto", "to", "SourceRootNamed", "PlainCase"),
      assertThrows(IllegalStateException.class, () -> mapper.forward(new PlainCase("p"))).getMessage()
    );
  }

  @Test
  @DisplayName(
    "a sealed class whose case is non-sealed is accepted, since a subclass of that case may implement the root"
  )
  void sealedClassWithANonSealedCase() {
    final var mapper = Telescope.mapperForward(
      OpenCase.class,
      SourceRootNoteDto.class,
      to(NAMED_LABEL, SourceRootNoteDto::note)
    );
    assertEquals(
      PairingMessages.sourcePathGivenOffRoot(
        "OpenCase",
        "SourceRootNoteDto",
        "to",
        "SourceRootNamed",
        "OpenCaseNonSealed"
      ),
      assertThrows(IllegalStateException.class, () -> mapper.forward(new OpenCaseNonSealed())).getMessage()
    );
  }

  @Test
  @DisplayName("a lens on a sealed interface no case of which can be the source is refused")
  void sealedInterfaceRootOnAnUnrelatedSource() {
    assertEquals(
      PairingMessages.sourcePathNotOnSource("SourceRootBase", "SourceRootNoteDto", "to", "SealedRoot"),
      refusal(() ->
        Telescope.mapperForward(
          SourceRootBase.class,
          SourceRootNoteDto.class,
          to(Telescope.lens(SealedRoot::getLabel, (SealedRoot r, String v) -> r), SourceRootNoteDto::note)
        )
      )
    );
  }
}
