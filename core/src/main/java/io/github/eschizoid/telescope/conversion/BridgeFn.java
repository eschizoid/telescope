package io.github.eschizoid.telescope.conversion;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.introspection.OpticNode;
import java.util.IdentityHashMap;
import java.util.List;
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
   * Whether {@link #forward(Object, IdentityHashMap)} and {@link #backward(Object,
   * IdentityHashMap)} convert along the active path they are handed. A bridge {@code @Bridge}
   * generates for a pair that can reach itself does. A runtime mapper converts a nested pair on a
   * cycle through its own bridge only when this is true, handing it the mapper's path, so the
   * reference that closes the cycle converts to null on both paths. False by default: the mapper
   * then walks such a pair itself.
   *
   * <p>A bridge answering true has to override both path-taking methods, which throw by default.
   * One that ignored the path would start a path of its own on every call, and a cycle through it
   * would never close.
   */
  default boolean takesActivePath() {
    return false;
  }

  /**
   * {@link #forward(Object)} along {@code path}, the objects being converted right now: one
   * identity set per key, which a generated bridge takes as a {@link
   * io.github.eschizoid.telescope.PathKey} naming the two classes it converts between. A {@code
   * source} already in the set converts to null; otherwise it is in the set for as long as it
   * converts, and the path is handed to the bridges this one calls.
   *
   * @throws UnsupportedOperationException by default, for a bridge that does not {@linkplain
   *     #takesActivePath() take the path}
   */
  default B forward(final A source, final IdentityHashMap<Object, IdentityHashMap<Object, Boolean>> path) {
    throw new UnsupportedOperationException(noActivePath(getClass()));
  }

  /**
   * {@link #backward(Object)} along {@code path}, as {@link #forward(Object, IdentityHashMap)}
   * does.
   *
   * @throws UnsupportedOperationException by default, for a bridge that does not {@linkplain
   *     #takesActivePath() take the path}
   */
  default A backward(final B target, final IdentityHashMap<Object, IdentityHashMap<Object, Boolean>> path) {
    throw new UnsupportedOperationException(noActivePath(getClass()));
  }

  private static String noActivePath(final Class<?> bridge) {
    return (
      bridge.getName() +
      " does not convert along an active path. A bridge whose takesActivePath() is true overrides" +
      " both forward and backward taking a path; if it is a generated bridge, re-run the @Bridge" +
      " processor."
    );
  }

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

  /** {@link #writesEdits()} has {@link #editInto}. */
  int EDIT_INTO = 1;

  /** {@link #writesEdits()} has {@link #editPatchInto}. */
  int EDIT_PATCH_INTO = 2;

  /** {@link #writesEdits()} has {@link #editPatch}. */
  int EDIT_PATCH = 4;

  /**
   * Codegen-support seam: which of the {@link #editInto}, {@link #editPatchInto} and {@link
   * #editPatch} of an edit mapper this bridge has, as the sum of {@link #EDIT_INTO}, {@link
   * #EDIT_PATCH_INTO} and {@link #EDIT_PATCH}. A bridge generated for a field initialised by {@code
   * Telescope.edits} has each one generated code can write: {@code editInto} where the target is a
   * bean with a setter for every property it writes, the other two where no row writes a path into
   * it. The field's mapper runs the runtime path's operation for one it lacks. None by default.
   */
  default int writesEdits() {
    return 0;
  }

  /**
   * Codegen-support seam: write into {@code target} every property an edit mapper accounts for,
   * from {@code source}, as {@link EditMapper#into} does, and answer {@code target}. A bridge
   * {@code @Bridge} generates for a field initialised by {@code Telescope.edits} answers with its
   * own {@code into}.
   *
   * @throws UnsupportedOperationException by default, for a bridge no edit mapper declares
   */
  default B editInto(final B target, final A source) {
    throw new UnsupportedOperationException(noEdit(getClass(), "into"));
  }

  /**
   * Codegen-support seam: write into {@code target} what {@code partial} sends, as {@link
   * EditMapper#patchInto} does, and answer {@code target}.
   *
   * @throws UnsupportedOperationException by default, for a bridge no edit mapper declares
   */
  default B editPatchInto(final B target, final A partial) {
    throw new UnsupportedOperationException(noEdit(getClass(), "patchInto"));
  }

  /**
   * Codegen-support seam: a copy of {@code base} with what {@code partial} sends laid over it, as
   * {@link EditMapper#patch} answers it.
   *
   * @throws UnsupportedOperationException by default, for a bridge no edit mapper declares
   */
  default B editPatch(final B base, final A partial) {
    throw new UnsupportedOperationException(noEdit(getClass(), "patch"));
  }

  private static String noEdit(final Class<?> bridge, final String operation) {
    return (
      bridge.getName() +
      " has no " +
      operation +
      " of an edit mapper: only a bridge generated for a field initialised by Telescope.edits has one. If it" +
      " is one, re-run the @Bridge processor."
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
   * by a conversion of its own, a {@code to} row with converters or a {@code via} row. A runtime
   * mapper deciding whether this bridge may convert a nested pair for it looks only below the
   * properties the bridge walks. Read only when {@link #declaresUnwalkedProperties()} is true.
   * Empty by default.
   */
  default Set<String> unwalkedSourceProperties() {
    return Set.of();
  }

  /**
   * The properties of the target this bridge does not walk: those a converting {@code to} row or a
   * {@code via} row writes, and those a {@code constant} or a {@code compute} row fills. See {@link
   * #unwalkedSourceProperties()}.
   */
  default Set<String> unwalkedTargetProperties() {
    return Set.of();
  }

  /**
   * Codegen-support seam: the conversion of one {@code A} into one {@code B} as a container policy
   * writes an element in place, this bridge's {@code forward} and the check and write its {@code
   * into} makes after it, or null where the bridge writes none in place. A runtime mapper
   * converting the elements of a collection through this bridge hands it to the container policy,
   * as a generated bridge does. Null by default.
   *
   * @return the conversion, or null
   */
  default ContainerCopy.Elements targetWrite() {
    return null;
  }

  /**
   * Codegen-support seam: how the bridge writes each collection of its pair, and for an edit
   * mapper's bridge each property that converts through the edit mapper of its own pair, as the
   * nodes {@code explain()} reports for a mapper over the same rows. Empty by default.
   *
   * @return the nodes, one per collection and one per property converting through an edit mapper
   */
  default List<OpticNode> collectionWrites() {
    return List.of();
  }
}
