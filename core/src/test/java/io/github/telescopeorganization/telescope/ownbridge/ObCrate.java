package io.github.telescopeorganization.telescope.ownbridge;

import io.github.telescopeorganization.telescope.annotations.Bridge;

/**
 * A lenient bridge whose component pair has no {@code @Bridge} of its own, so the processor derives
 * a lenient bridge for that pair too.
 */
@Bridge(value = ObCrateDto.class, lenient = true)
public record ObCrate(ObItem item) {}
