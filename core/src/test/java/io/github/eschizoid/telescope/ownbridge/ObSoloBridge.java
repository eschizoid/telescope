package io.github.eschizoid.telescope.ownbridge;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.BridgeFn;
import io.github.eschizoid.telescope.ownbridge.twin.ObSoloDto;
import java.util.Locale;

/**
 * A bridge written by hand under the short name, {@code <Source>Bridge}, converting {@link ObSolo}
 * to the {@code twin} package's {@code ObSoloDto}. It upper-cases the name, so a result through it
 * cannot pass for one mapped by name.
 */
public final class ObSoloBridge {

  private ObSoloBridge() {}

  public static final Telescope<ObSolo, ObSoloDto> BRIDGE = Telescope.bridge(
    new BridgeFn<>() {
      @Override
      public ObSoloDto forward(final ObSolo source) {
        return source == null ? null : new ObSoloDto(source.name().toUpperCase(Locale.ROOT));
      }

      @Override
      public ObSolo backward(final ObSoloDto target) {
        return target == null ? null : new ObSolo(target.name().toLowerCase(Locale.ROOT));
      }
    }
  );
}
