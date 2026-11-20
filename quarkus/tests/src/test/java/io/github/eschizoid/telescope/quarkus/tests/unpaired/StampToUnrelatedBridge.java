package io.github.eschizoid.telescope.quarkus.tests.unpaired;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.BridgeFn;

/** The bridge holder beside {@link Stamp}, under the name the forward mapping looks for. */
public final class StampToUnrelatedBridge {

  /** Writes the stamp's code as {@code zzz} and its length as {@code yyy}. */
  public static final Telescope<Stamp, Unrelated> BRIDGE = Telescope.bridge(
    new BridgeFn<Stamp, Unrelated>() {
      @Override
      public Unrelated forward(final Stamp source) {
        return new Unrelated(source.code(), source.code().length());
      }

      @Override
      public Stamp backward(final Unrelated target) {
        return new Stamp(target.zzz());
      }
    }
  );

  private StampToUnrelatedBridge() {}
}
