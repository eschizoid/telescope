package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.mapping.MapStep;
import io.github.eschizoid.telescope.mapping.Mapping;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A runtime row's result written into a property a generic superclass declares with a type
 * variable, which the side's class fixes to a class the member it is written through, declared with
 * the variable, does not name. The result is checked against the fixed class: a {@code via} row
 * whose mapper returns a class no value of which the property takes is refused when the mapper is
 * built, as a generated bridge refuses it, and any other result is checked as it is written. The
 * suite also runs on the accessor substrate a native image uses.
 */
@SuppressWarnings("rawtypes")
class ViaFixedResultTest {

  public record Ch(String name) {}

  public record ChDto(String name) {}

  public record OtherDto(String name) {}

  public static class Base<T> {

    private T child;

    public T getChild() {
      return child;
    }

    public void setChild(final T child) {
      this.child = child;
    }
  }

  /** Passes its own variable through to the superclass. */
  public static class Through<X> extends Base<X> {}

  /** Fixes the superclass's variable to {@code ChDto}. */
  public static class FixedDto extends Base<ChDto> {}

  /** Fixes the superclass's variable to {@code Ch}. */
  public static class FixedCh extends Base<Ch> {}

  /** Fixes the superclass's variable to {@code String}. */
  public static class FixedString extends Base<String> {}

  private static <S extends Base<?>> S holding(final S bean, final Object child) {
    @SuppressWarnings("unchecked")
    final var base = (Base<Object>) bean;
    base.setChild(child);
    return bean;
  }

  /** The runtime mapper for two classes the row names raw. */
  @SuppressWarnings("unchecked")
  private static <A, B> Mapper<A, B> mapper(final Class source, final Class target, final MapStep row) {
    return Telescope.mapper(source, target, row);
  }

  @Test
  @DisplayName("a via mapper returning a class the target's fixed property never takes is refused when built")
  void aForwardResultTheTargetNeverTakesIsRefused() {
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      mapper(
        Through.class,
        FixedDto.class,
        Mapping.<Through, FixedDto>via(
          Through::getChild,
          FixedDto::getChild,
          Telescope.mapper(Ch.class, OtherDto.class)
        )
      )
    );
    assertEquals(
      PairingMessages.viaResultNeverFits("Through", "FixedDto", "target", "child", "OtherDto", "ChDto"),
      refused.getMessage()
    );
  }

  @Test
  @DisplayName("a via mapper returning a class the source's fixed property never takes is refused when built")
  void aBackwardResultTheSourceNeverTakesIsRefused() {
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      mapper(
        FixedCh.class,
        Through.class,
        Mapping.<FixedCh, Through>via(
          FixedCh::getChild,
          Through::getChild,
          Telescope.mapper(OtherDto.class, ChDto.class)
        )
      )
    );
    assertEquals(
      PairingMessages.viaResultNeverFits("FixedCh", "Through", "source", "child", "OtherDto", "Ch"),
      refused.getMessage()
    );
  }

  @Test
  @DisplayName("a via mapper returning the class the property is fixed to converts both ways")
  void aFittingViaMapperConverts() {
    final Mapper<Through<Ch>, FixedDto> mapper = mapper(
      Through.class,
      FixedDto.class,
      Mapping.<Through, FixedDto>via(Through::getChild, FixedDto::getChild, Telescope.mapper(Ch.class, ChDto.class))
    );
    assertEquals(new ChDto("a"), mapper.forward(holding(new Through<>(), new Ch("a"))).getChild());
    assertEquals(new Ch("q"), mapper.backward(holding(new FixedDto(), new ChDto("q"))).getChild());
  }

  @Test
  @DisplayName("a transform result of another class than the target's fixed one is refused by name as it is written")
  void aTransformResultIsCheckedAgainstTheFixedClass() {
    final Mapper<Through<Object>, FixedString> mapper = mapper(
      Through.class,
      FixedString.class,
      Mapping.<Through, FixedString, Object, Object>to(Through::getChild, FixedString::getChild, v -> v, v -> v)
    );
    assertEquals("a", mapper.forward(holding(new Through<>(), "a")).getChild());
    final var refused = assertThrows(ClassCastException.class, () -> mapper.forward(holding(new Through<>(), 5)));
    assertEquals(
      PairingMessages.resultClassMismatch(
        FixedString.class.getTypeName(),
        "child",
        "java.lang.Integer",
        "java.lang.String"
      ),
      refused.getMessage()
    );
  }
}
