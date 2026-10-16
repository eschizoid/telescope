package io.github.eschizoid.telescope.ownbridge;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.BridgeFn;
import java.util.Locale;

/**
 * A bridge written by hand under the name a generated one for {@link ObOb} takes. It does not take
 * the active path, so it cannot cut a cycle that runs through it.
 */
public final class ObObBridge {

  private ObObBridge() {}

  public static final Telescope<ObOb, ObObDto> BRIDGE = Telescope.bridge(
    new BridgeFn<>() {
      @Override
      public ObObDto forward(final ObOb source) {
        return source == null ? null : new ObObDto(source.label().toUpperCase(Locale.ROOT), null);
      }

      @Override
      public ObOb backward(final ObObDto target) {
        return target == null ? null : new ObOb(target.label().toLowerCase(Locale.ROOT), null);
      }
    }
  );
}
