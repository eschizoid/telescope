package io.github.eschizoid.telescope.ownbridge;

import java.util.Locale;

/**
 * A hand-written class under the long name a generated bridge for {@code (ObHand, ObHandDto)} would
 * take, with no {@code BRIDGE} field: the shape a {@code @ViaMapper} class may have.
 */
public final class ObHandToObHandDtoBridge {

  private ObHandToObHandDtoBridge() {}

  public static ObHandDto forward(final ObHand source) {
    return source == null ? null : new ObHandDto(source.s().toUpperCase(Locale.ROOT));
  }

  public static ObHand backward(final ObHandDto target) {
    return target == null ? null : new ObHand(target.s().toLowerCase(Locale.ROOT));
  }
}
