package io.github.eschizoid.telescope.internal.pairing;

/**
 * A parameterized container as the pairing rules see it: its kind, its element (or map-value) type,
 * the map key type when applicable, and the raw class handle (carried so lifted conversions can
 * allocate the declared concrete class). The world-agnostic twin of the runtime's private
 * container-shape probe — built exclusively by {@link PairingRules#containerViewOf} so the kind
 * selection rules live once.
 *
 * @param <T> the world's type handle
 */
public record ContainerView<T>(ContainerView.Kind kind, T elementType, T keyType, T rawType) {
  /** Container families the auto-lift understands. */
  public enum Kind {
    LIST,
    SET,
    MAP_VALUES,
    OPTIONAL,
  }
}
