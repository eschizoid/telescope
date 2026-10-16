package io.github.eschizoid.telescope.codegen.lombok.fixtures;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Transform;

/**
 * Fixture: the source of a bridge into {@link OrderedLombokTarget}, converting both of its
 * components through {@link OrderedLog}.
 */
@Bridge(
  value = OrderedLombokTarget.class,
  transforms = {
    @Transform(field = "firstLo", using = OrderedLog.class, forwardOnly = true),
    @Transform(field = "secondLo", using = OrderedLog.class, forwardOnly = true),
  }
)
public record OrderedLombokSource(String firstLo, String secondLo) {}
