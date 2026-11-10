package io.github.telescopeorganization.telescope;

import static io.github.telescopeorganization.telescope.Telescope.mapper;
import static io.github.telescopeorganization.telescope.mapping.Mapping.compute;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.Mapper;

/**
 * {@link SuperRowSource}'s shape, bridged to {@link SuperRowGenericSub} with a computed value on
 * the property its generic base declares, so the generated bridge can be compared with a runtime
 * {@code compute} row naming the same property through the base's getter.
 */
public record SuperRowComputedBridged(int n) {
  @Bridge
  public static final Mapper<SuperRowComputedBridged, SuperRowGenericSub> SUB = mapper(
    SuperRowComputedBridged.class,
    SuperRowGenericSub.class,
    compute(SuperRowGenericSub::getName, SuperRowNameSupplier.INSTANCE)
  );
}
