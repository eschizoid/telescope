package io.github.eschizoid.telescope.conversion;

import io.github.eschizoid.telescope.Telescope;
import java.util.Set;

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

  /**
   * Overlay the non-null properties of {@code partial} onto {@code base}, as a new {@code A}. A
   * bridge {@code @Bridge} generates answers with its own {@code patch}; a mapper between two
   * sealed interfaces patches a case converted through its bridge with it.
   *
   * @throws UnsupportedOperationException by default: a pair written by hand has no properties to
   *     overlay, and a bridge generated without this method needs the {@code @Bridge} processor run
   *     again
   */
  default A patch(final A base, final B partial) {
    throw new UnsupportedOperationException(
      getClass().getName() +
        " has no patch: a conversion pair written by hand has no properties to overlay. If it is a" +
        " generated bridge, re-run the @Bridge processor."
    );
  }

  /**
   * Whether a {@code @Bridge} names this bridge's pair. A bridge {@code @Bridge} generates for the
   * pair it names does. One the processor derives for a nested pair with no {@code @Bridge} of its
   * own does not: it carries the configuration of whichever bridges reached that pair, not the
   * pair's. A runtime mapper converts a nested pair through a bridge named {@code
   * <Source>To<Target>Bridge} only when this is true. False by default, so a bridge written by hand
   * or generated without this method is not read under that name.
   */
  default boolean declaresPair() {
    return false;
  }

  /**
   * Whether this bridge lists the properties it does not walk in {@link
   * #unwalkedSourceProperties()} and {@link #unwalkedTargetProperties()}. A bridge {@code @Bridge}
   * generates does, even when both lists are empty. False by default: a runtime mapper then reads
   * the bridge as walking every property, and does not look past a sealed interface into its cases
   * when it decides whether the bridge may convert a nested pair for it.
   */
  default boolean declaresUnwalkedProperties() {
    return false;
  }

  /**
   * The properties of the source this bridge does not walk: those it drops, and those it converts
   * by a conversion of its own through a {@code @Transform} or a {@code @ViaMapper}. A runtime
   * mapper deciding whether this bridge may convert a nested pair for it looks only below the
   * properties the bridge walks. Read only when {@link #declaresUnwalkedProperties()} is true.
   * Empty by default.
   */
  default Set<String> unwalkedSourceProperties() {
    return Set.of();
  }

  /**
   * The properties of the target this bridge does not walk: those a {@code @Transform} or a
   * {@code @ViaMapper} writes, and those a {@code @Constant} or a {@code @Compute} fills. See
   * {@link #unwalkedSourceProperties()}.
   */
  default Set<String> unwalkedTargetProperties() {
    return Set.of();
  }
}
