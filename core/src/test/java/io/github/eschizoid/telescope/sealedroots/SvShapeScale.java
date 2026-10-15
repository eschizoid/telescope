package io.github.eschizoid.telescope.sealedroots;

import io.github.eschizoid.telescope.conversion.BridgeFn;

/** Converts only a leaf, scaling it, so a result through this transform cannot pass for a walk. */
public final class SvShapeScale implements BridgeFn<SvShape, SvShapeDto> {

  @Override
  public SvShapeDto forward(final SvShape s) {
    return s instanceof SvLeaf leaf ? new SvLeafDto(leaf.n() * 100) : null;
  }

  @Override
  public SvShape backward(final SvShapeDto s) {
    return s instanceof SvLeafDto leaf ? new SvLeaf(leaf.n() / 100) : null;
  }
}
