package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Bridge;

/** Passes its own variable through to the values of {@link BenchFixedBase}. */
@Bridge(BenchFixedTgt.class)
public class BenchFixedSrc<X> extends BenchFixedBase<String, X> {}
