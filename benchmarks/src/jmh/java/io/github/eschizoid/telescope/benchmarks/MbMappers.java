package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

/** The two mappers {@link MergeByIdBenchmark} writes an order with: generated, and runtime. */
public final class MbMappers {

  private MbMappers() {}

  /** Compiled by {@code telescope-codegen}. */
  @Bridge
  public static final Mapper<MbOrderForm, MbOrder> FORM = Telescope.mapper(MbOrderForm.class, MbOrder.class);

  /** Built at run time, with no bridge generated for its pair. */
  public static final Mapper<MbOrderEdit, MbOrder> EDIT = Telescope.mapper(MbOrderEdit.class, MbOrder.class);
}
