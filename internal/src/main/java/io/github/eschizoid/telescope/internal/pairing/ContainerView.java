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
  /**
   * Container families the auto-lift understands.
   *
   * <p>{@code COLLECTION} is not a family beside the other two so much as their union: a field
   * declared that way accepts either, so it is paired against whichever the other side is and
   * carries that kind from then on. Nothing downstream sees it.
   */
  public enum Kind {
    LIST,
    SET,
    COLLECTION,
    MAP_VALUES,
    OPTIONAL,
  }

  /**
   * This view seen as {@code kind}. Used to settle a {@code COLLECTION} against the side that has a
   * shape of its own, so the lift and the allocator are chosen by what is actually being built.
   */
  public ContainerView<T> as(final Kind other) {
    return kind == other ? this : new ContainerView<>(other, elementType, keyType, rawType);
  }

  public ContainerView {
    if ((keyType == null) == (kind == Kind.MAP_VALUES)) {
      throw new IllegalArgumentException(
        "keyType is required for MAP_VALUES and forbidden otherwise (kind=" + kind + ")"
      );
    }
  }
}
