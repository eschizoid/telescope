package io.github.eschizoid.telescope.conversion;

/**
 * Codegen-support seam: the capability the class generated for a {@code @Bridge} field passes to
 * the {@link Mapper} or {@link ForwardMapper} constructor it extends. Only this package creates
 * one, and {@link BridgeRegistry#specialise(BridgeProvider, Mapper)} hands it only to a {@link
 * BridgeProvider} that {@code ServiceLoader} registered for the class declaring the field, when a
 * factory call from that class's static initialiser is dispatched to it. Code outside a registered
 * provider holds no grant, so it cannot build a subclass of either mapper.
 */
public final class BridgeGrant {

  /** The one grant; a constructor given any other value, null included, refuses it. */
  static final BridgeGrant INSTANCE = new BridgeGrant();

  private BridgeGrant() {}
}
