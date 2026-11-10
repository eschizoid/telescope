package io.github.telescopeorganization.telescope.benchmarks;

import io.github.telescopeorganization.telescope.Telescope;
import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.Mapper;

/**
 * The generated mappers {@link IntoBenchmark} declares on fields: the five-property and the
 * twenty-property source written into the same beans its runtime rows write into.
 */
public final class IntoMappers {

  private IntoMappers() {}

  @Bridge
  public static final Mapper<IntoFieldSrc, IntoNarrow> NARROW = Telescope.mapper(IntoFieldSrc.class, IntoNarrow.class);

  @Bridge
  public static final Mapper<IntoFieldWideSrc, IntoWide> WIDE = Telescope.mapper(
    IntoFieldWideSrc.class,
    IntoWide.class
  );
}
