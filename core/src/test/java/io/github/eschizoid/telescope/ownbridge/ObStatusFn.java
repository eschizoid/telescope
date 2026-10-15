package io.github.eschizoid.telescope.ownbridge;

import io.github.eschizoid.telescope.conversion.BridgeFn;

/** Converts by name, and converts the target's extra constant back to {@link ObStatus#A}. */
public final class ObStatusFn implements BridgeFn<ObStatus, ObStatusDto> {

  @Override
  public ObStatusDto forward(final ObStatus source) {
    return source == null ? null : ObStatusDto.valueOf(source.name());
  }

  @Override
  public ObStatus backward(final ObStatusDto target) {
    if (target == null) return null;
    return target == ObStatusDto.C ? ObStatus.A : ObStatus.valueOf(target.name());
  }
}
