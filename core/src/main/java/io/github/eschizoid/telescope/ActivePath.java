package io.github.eschizoid.telescope;

import java.util.IdentityHashMap;
import java.util.function.Function;

/**
 * A runtime mapper's active path in one direction: the objects being converted right now, by the
 * pair of classes each is converted between, and the walk that opened it. It is the path type a
 * generated bridge takes, so a mapper can hand it to a bridge that {@linkplain
 * io.github.eschizoid.telescope.conversion.BridgeFn#takesActivePath() takes the path}. The bridge
 * asks for the set under its {@link PathKey}, and gets the set of that key's pair, which is the set
 * the mapper enters the same pair's objects in. Any other key is kept by identity, as on a path the
 * bridge started itself.
 *
 * <p>The pairs are a short list rather than a table: a walk tracks only the pairs that lie on a
 * cycle, and a path opened for one call meets few of them. The map this class extends holds only
 * the keys a bridge asks for that are not a {@link PathKey}, so it starts at its smallest size.
 *
 * <p>Not thread-safe: one path belongs to one conversion on one thread.
 */
final class ActivePath extends IdentityHashMap<Object, IdentityHashMap<Object, Boolean>> {

  private static final long serialVersionUID = 1L;

  private final transient Object owner;
  private transient Pair pairs;

  /** The objects being converted from {@code from} into {@code into}. */
  private static final class Pair {

    private final Class<?> from;
    private final Class<?> into;
    private final IdentityHashMap<Object, Boolean> objects = new IdentityHashMap<>();
    private final Pair next;

    private Pair(final Class<?> from, final Class<?> into, final Pair next) {
      this.from = from;
      this.into = into;
      this.next = next;
    }
  }

  /** An empty path opened by the walk {@code owner}. */
  ActivePath(final Object owner) {
    super(1);
    this.owner = owner;
  }

  /** The walk that opened this path. */
  Object owner() {
    return owner;
  }

  /** The objects being converted from {@code from} into {@code into}, created when first asked. */
  IdentityHashMap<Object, Boolean> objects(final Class<?> from, final Class<?> into) {
    var pair = pairs;
    while (pair != null && (pair.from != from || pair.into != into)) pair = pair.next;
    if (pair == null) {
      pair = new Pair(from, into, pairs);
      pairs = pair;
    }
    return pair.objects;
  }

  @Override
  public IdentityHashMap<Object, Boolean> computeIfAbsent(
    final Object key,
    final Function<? super Object, ? extends IdentityHashMap<Object, Boolean>> mappingFunction
  ) {
    if (key instanceof PathKey(final Class<?> from, final Class<?> into)) return objects(from, into);
    return super.computeIfAbsent(key, mappingFunction);
  }
}
