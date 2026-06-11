package io.github.eschizoid.telescope.conversion;

import io.github.eschizoid.telescope.Telescope;

/**
 * Two-method bidirectional conversion pair. Companion to {@link Telescope#bridge(BridgeFn)}.
 *
 * <p>Each implementation supplies a {@link #forward forward} and a {@link #backward backward}
 * method that form an isomorphism: {@code backward(forward(a)).equals(a)} and {@code
 * forward(backward(b)).equals(b)} for the components involved.
 *
 * <p><b>Purpose — monomorphic dispatch.</b> {@code BridgeFn} exists so {@code @Bridge}-generated
 * code can hand telescope a concrete typed pair instead of two raw {@link
 * java.util.function.Function Function} values. The generated bridge class declares its own
 * implementing class (one per {@code @Bridge}-annotated type), so the dispatch site inside the
 * resulting {@link Telescope} sees a single concrete implementation per bridge constant — no
 * megamorphic {@code Function::apply} hop shared across every bridge ever loaded into the JVM.
 *
 * <p>Hand-written callers can implement this directly too: usually a {@code record} with two
 * method-reference returns is enough.
 *
 * <pre>{@code
 * final BridgeFn<UserEntity, UserDto> bridge = new BridgeFn<>() {
 *   public UserDto forward(final UserEntity e) { return new UserDto(e.id(), e.email()); }
 *   public UserEntity backward(final UserDto d) { return new UserEntity(d.id(), d.email()); }
 * };
 * final Telescope<UserEntity, UserDto> userBridge = Telescope.bridge(bridge);
 * }</pre>
 *
 * <p>For ad-hoc {@code Function}-typed pairs, the {@link Telescope#from(Class) from(...).to(...)
 * .using(forward, backward)} factory remains the more ergonomic entry point.
 *
 * @param <A> source type (forward input, backward output)
 * @param <B> target type (forward output, backward input)
 * @see Telescope#bridge(BridgeFn)
 * @see Telescope#from(Class)
 */
public interface BridgeFn<A, B> {
  /** Forward conversion {@code A -> B}. */
  B forward(A source);

  /** Backward conversion {@code B -> A}, inverse of {@link #forward}. */
  A backward(B target);
}
