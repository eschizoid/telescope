package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.FromMap;
import java.util.List;

/**
 * The codegen sibling of {@code FromMapBenchmark.Payment}, bound by the generated {@code
 * PaymentGenFromMap} rather than by the runtime closure. Top level because the binder names its
 * target by simple name, which a nested record does not answer to.
 *
 * <p>Carries a list so the row measures what the binder does with a container as well as what it
 * does with scalars.
 */
@FromMap
public record PaymentGen(String id, String currency, int amountCents, List<String> tags) {}
