package io.github.eschizoid.telescope.conversion;

/**
 * Codegen-support seam: the capability the class generated for a {@code @Bridge} field passes to
 * the {@link Mapper} or {@link ForwardMapper} constructor it extends. Only this package creates
 * one, and {@link BridgeRegistry#specialise(BridgeProvider, Mapper)} hands it only to a {@link
 * BridgeProvider} that {@code ServiceLoader} registered for the class declaring the field, when a
 * factory call from that class's static initialiser is dispatched to it. The grant stops accidental
 * subclassing, such as wrapping a field's mapper in a class of one's own, which a constructor given
 * any other value refuses. It does not stop a deliberate one: a hand-written provider registered in
 * {@code META-INF/services} receives the grant at dispatch and can keep it, and on the class path
 * reflection can read the instance.
 */
public final class BridgeGrant {

  /** The one grant; a constructor given any other value, null included, refuses it. */
  static final BridgeGrant INSTANCE = new BridgeGrant();

  private BridgeGrant() {}
}
