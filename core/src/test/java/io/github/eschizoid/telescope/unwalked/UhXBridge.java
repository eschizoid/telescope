package io.github.eschizoid.telescope.unwalked;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.BridgeFn;

/**
 * A bridge written by hand under the name a generated one takes, which lists none of the properties
 * it does not walk: it upper-cases the name and scales a leaf itself.
 */
public final class UhXBridge {

  private UhXBridge() {}

  public static final Telescope<UhX, UhXDto> BRIDGE = Telescope.bridge(
    new BridgeFn<>() {
      @Override
      public UhXDto forward(final UhX x) {
        return x == null
          ? null
          : new UhXDto(x.name().toUpperCase(), x.shape() instanceof UhLeaf leaf ? new UhLeafDto(leaf.n() * 100) : null);
      }

      @Override
      public UhX backward(final UhXDto x) {
        return x == null
          ? null
          : new UhX(x.name().toLowerCase(), x.shape() instanceof UhLeafDto leaf ? new UhLeaf(leaf.n() / 100) : null);
      }
    }
  );
}
