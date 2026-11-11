package io.github.eschizoid.telescope.benchmarks;

import static io.github.eschizoid.telescope.mapping.MergeStep.auto;

import io.github.eschizoid.telescope.Sources;
import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.benchmarks.MergeBenchmark.Audit;
import io.github.eschizoid.telescope.benchmarks.MergeBenchmark.Customer;
import io.github.eschizoid.telescope.benchmarks.MergeBenchmark.Profile;
import io.github.eschizoid.telescope.conversion.Mapper;

/** The {@code @Bridge} merge {@link MergeBenchmark} runs through its generated bridge. */
public final class MergeBridges {

  private MergeBridges() {}

  @Bridge
  public static final Mapper<Sources, Profile> PROFILE = Telescope.merge(
    Profile.class,
    auto(Customer.class),
    auto(Audit.class)
  );
}
