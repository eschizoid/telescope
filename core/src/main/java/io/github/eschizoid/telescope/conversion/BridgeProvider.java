package io.github.eschizoid.telescope.conversion;

import java.util.List;

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
  /**
   * The resource path, followed by a class's binary name, under which the processor lists the pairs
   * of the class's {@code @Bridge} fields, one {@code source -> target} line each, when it compiled
   * the class without the javac tree API and so compiled none of their rows.
   */
  String UNREAD_ROWS_PREFIX = "META-INF/telescope/unread-bridge-fields/";

  /** The bridge's source type — the {@code A} in {@code Telescope<A, B>}. */
  Class<?> sourceType();

  /** The bridge's target type — the {@code B} in {@code Telescope<A, B>}. */
  Class<?> targetType();

  /** The generated {@code BRIDGE} constant — a {@code Telescope<Source, Target>}, opaque here. */
  Object bridge();

  /**
   * The class whose {@code @Bridge} field declared this bridge, or null for a bridge a
   * {@code @Bridge} on a class declared. A mapper factory called from that class for this pair
   * returns a mapper over the bridge.
   */
  default Class<?> declaringType() {
    return null;
  }

  /**
   * For a bridge a field declared, the simple names of the classes of the rows its mapper call
   * builds, in order; a factory handed other rows refuses the bridge, since it was generated from a
   * different declaration. Empty for a bridge a class declared.
   */
  default List<String> rowKinds() {
    return List.of();
  }

  /**
   * For a bridge a field declared, the accessor methods each row of its mapper call names, as
   * {@code source>target} with an empty side where a row names none, in order. Empty for a bridge a
   * class declared.
   */
  default List<String> rowAccessors() {
    return List.of();
  }

  /**
   * For a bridge a field declared, the target's properties, which {@code Mapper.into} writes. Empty
   * for a bridge a class declared.
   */
  default List<String> targetProperties() {
    return List.of();
  }
}
