package io.github.eschizoid.telescope.sealedroots;

import io.github.eschizoid.telescope.conversion.BridgeFn;

/** Converts a back-reference to an empty one, so a result through it cannot pass for a walk. */
public final class StBackDrop implements BridgeFn<StBack, StBackDto> {

  @Override
  public StBackDto forward(final StBack s) {
    return s == null ? null : new StBackDto(null);
  }

  @Override
  public StBack backward(final StBackDto s) {
    return s == null ? null : new StBack(null);
  }
}
