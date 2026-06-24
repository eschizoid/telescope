package io.github.eschizoid.telescope.conversion;

/**
 * Service-provider interface for runtime discovery of a {@code @Bridge}-generated converter by its
 * {@code (source, target)} pair. The {@code @Bridge} processor emits one implementation per
 * carrier-form bridge and registers it through {@link java.util.ServiceLoader} ({@code
 * META-INF/services}), so {@link io.github.eschizoid.telescope.Telescope#mapperForward(Class,
 * Class, io.github.eschizoid.telescope.mapping.MapStep...)} can locate the bridge without knowing
 * the package or class name it was emitted under.
 *
 * <p>This closes the gap left by the name-derived sibling probe: a carrier-form {@code @Bridge}
 * lives in the carrier's package (a third module that sees both source and target), which a probe
 * keyed off the source class's package can't reach. Locating by {@code (source, target)} through
 * this SPI is package-agnostic and works across the module path (each generated provider declares
 * itself with {@code provides … with …}) and the class path ({@code META-INF/services}).
 *
 * <p>{@link #bridge()} is typed as {@link Object} because the {@code Telescope<Source, Target>}
 * optic type is the consumer's to know; the runtime factory casts it back. Implementations must
 * expose a public no-argument constructor for {@code ServiceLoader} instantiation.
 */
public interface BridgeProvider {
  /** The bridge's source type — the {@code A} in {@code Telescope<A, B>}. */
  Class<?> sourceType();

  /** The bridge's target type — the {@code B} in {@code Telescope<A, B>}. */
  Class<?> targetType();

  /** The generated {@code BRIDGE} constant — a {@code Telescope<Source, Target>}, opaque here. */
  Object bridge();
}
