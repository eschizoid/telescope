package io.github.telescopeorganization.telescope.benchmarks;

import io.github.telescopeorganization.telescope.Telescope;
import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.Mapper;

/**
 * Generated mappers declared on their fields, for {@link MapStructComparisonBenchmark}: the same
 * pairs of shapes its {@code @Bridge} classes declare, so a row calling one of these mappers
 * measures the field form against the class form on the same work.
 */
public final class McFieldMappers {

  private McFieldMappers() {}

  @Bridge
  public static final Mapper<McFieldFlatBean, McFlatRec> FLAT = Telescope.mapper(
    McFieldFlatBean.class,
    McFlatRec.class
  );

  @Bridge
  public static final Mapper<McFieldNestedBean, McNestedRec> NESTED = Telescope.mapper(
    McFieldNestedBean.class,
    McNestedRec.class
  );
}
