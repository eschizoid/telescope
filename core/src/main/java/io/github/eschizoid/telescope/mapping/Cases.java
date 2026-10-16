package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.util.List;
import java.util.Objects;

/**
 * Pairs one case of a sealed source with the case of the sealed target it converts to, for {@link
 * Telescope#map(Class, Class, MapStep...)} and its siblings. Static-import the factory.
 *
 * <pre>{@code
 * import static io.github.eschizoid.telescope.mapping.Cases.cases;
 *
 * final var mapper = Telescope.mapper(
 *     Shape.class, ShapeDto.class,
 *     cases(Circle.class, CircleDto.class),
 *     cases(Square.class, SquareDto.class));
 * }</pre>
 *
 * <p><b>How cases pair.</b> A mapper meeting two sealed interfaces, as the pair it is built for or
 * as a property, an element or an {@code Optional} below it, converts each value through the pair
 * its own case belongs to. A case pairs with the case a {@code cases} row names for it; with no
 * row, with the case of the target its own {@code @Bridge} converts to, which is the pairing a
 * {@code @Bridge} on the sealed source generates. A row wins over the case's {@code @Bridge}. A
 * case that neither pairs is refused by name when the mapper is built, and so is a case of the
 * target that no case pairs with, or that two cases pair with, on a mapper that converts backward.
 *
 * <p><b>Validation.</b> Each class must be a permitted subclass of a sealed interface, checked
 * here. When the mapper is built, a row whose two classes are not cases of one sealed pair the
 * mapper converts, and two rows pairing the same case, are refused.
 */
public sealed interface Cases extends MapStep permits Cases.CasePair {
  /**
   * Pair {@code sourceCase}, a case of a sealed source, with {@code targetCase}, the case of the
   * sealed target it converts to.
   *
   * @throws IllegalArgumentException when either class is not a permitted subclass of a sealed
   *     interface
   */
  static <S, T> Cases cases(final Class<S> sourceCase, final Class<T> targetCase) {
    return new CasePair(sourceCase, targetCase);
  }

  /** The case of the sealed source this row pairs. */
  Class<?> sourceCase();

  /** The case of the sealed target {@link #sourceCase()} converts to. */
  Class<?> targetCase();

  /** Whether a sealed interface {@code type} implements names it among its permitted subclasses. */
  private static boolean isCase(final Class<?> type) {
    for (final var root : type.getInterfaces()) {
      if (root.isSealed() && List.of(root.getPermittedSubclasses()).contains(type)) return true;
    }
    return false;
  }

  /**
   * The record behind {@link #cases}; users construct it through the factory. It holds the
   * factory's checks itself, so no row naming a class that is no case can be built.
   */
  record CasePair(Class<?> sourceCase, Class<?> targetCase) implements Cases {
    public CasePair {
      Objects.requireNonNull(sourceCase, "sourceCase");
      Objects.requireNonNull(targetCase, "targetCase");
      final var row = PairingMessages.caseRow(sourceCase.getSimpleName(), targetCase.getSimpleName());
      for (final var named : List.of(sourceCase, targetCase)) {
        if (!isCase(named)) throw new IllegalArgumentException(PairingMessages.notACase(row, named.getSimpleName()));
      }
    }
  }
}
