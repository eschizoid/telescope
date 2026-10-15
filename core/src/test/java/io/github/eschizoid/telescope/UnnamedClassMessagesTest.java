package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Cases.cases;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A message that names the class of a value a caller handed in names it even when the class has no
 * simple name: an enum constant that declares a body by its enum, and an anonymous class by its
 * binary name.
 */
class UnnamedClassMessagesTest {

  public sealed interface Plan permits Tier, Custom {}

  public enum Tier implements Plan {
    FREE,
  }

  public record Custom(int seats) implements Plan {}

  public sealed interface PlanDto permits TierDto, CustomDto {}

  /** A target enum whose constant declares a body, so the constant's class has no simple name. */
  public enum TierDto implements PlanDto {
    FREE {
      @Override
      public String toString() {
        return "free";
      }
    },
  }

  public record CustomDto(int seats) implements PlanDto {}

  @Test
  @DisplayName("into names the case a value converts to by its enum when that case is a constant with a body")
  void intoNamesABodiedEnumCase() {
    final var mapper = Telescope.mapper(
      Plan.class,
      PlanDto.class,
      cases(Tier.class, TierDto.class),
      cases(Custom.class, CustomDto.class)
    );
    final var refused = assertThrows(IllegalArgumentException.class, () -> mapper.into(new CustomDto(1), Tier.FREE));
    assertTrue(
      refused.getMessage().contains("the source converts to TierDto, and the target is a CustomDto"),
      refused::getMessage
    );
  }

  @Test
  @DisplayName("a when predicate that throws an anonymous exception names it by its binary name")
  void predicateFailureNamesAnAnonymousException() {
    final var thrown = new IllegalStateException("boom") {};
    final var mapper = Telescope.mapperForward(
      SourceRootSub.class,
      SourceRootDto.class,
      when(
        s -> {
          throw thrown;
        },
        to(Telescope.ofBean(SourceRootSub.class).field(SourceRootSub::getLabel), SourceRootDto::note)
      )
    );
    final var failure = assertThrows(IllegalStateException.class, () -> mapper.forward(new SourceRootSub("lbl", 3)));
    assertTrue(
      failure.getMessage().endsWith("Predicate failure: " + thrown.getClass().getName() + ": boom"),
      failure::getMessage
    );
    assertEquals(thrown, failure.getCause());
  }
}
