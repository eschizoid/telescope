package io.github.eschizoid.telescope.patchfix;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Default;

/** Bridge source carrying a @Default null-coalesce on a boxed component. */
@Bridge(value = DefIntDst.class, defaults = @Default(field = "count", value = "42"))
public record DefIntSrc(Integer count, int hard) {}
