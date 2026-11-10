package io.github.telescopeorganization.telescope.patchfix;

import static io.github.telescopeorganization.telescope.Telescope.mapper;
import static io.github.telescopeorganization.telescope.mapping.Mapping.toOrElse;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.Mapper;

/** Bridge source whose {@code toOrElse} row null-coalesces a boxed component. */
public record DefIntSrc(Integer count, int hard) {
  @Bridge
  public static final Mapper<DefIntSrc, DefIntDst> DST = mapper(
    DefIntSrc.class,
    DefIntDst.class,
    toOrElse(DefIntSrc::count, DefIntDst::count, 42)
  );
}
