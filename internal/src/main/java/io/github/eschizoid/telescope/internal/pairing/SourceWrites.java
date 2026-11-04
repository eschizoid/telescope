package io.github.eschizoid.telescope.internal.pairing;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which writes of a source property a pair refuses, decided once for the runtime mapper, the bridge
 * processor and the compile-time verifier. One instance follows the writes of one pair, in the
 * order its rows give them.
 *
 * <p>A write names the source property it reads back, or the properties a source path steps through
 * from the source, joined with dots, as {@code customer.city}. A path's write is a write of that
 * path: two paths through one property each take a target of their own, as two properties do.
 *
 * <p>A mapper that converts backward rebuilds each source property, and each path, from one target,
 * so such a mapper writes a property or a path into one target at most: a second target leaves
 * backward no defined target to read it from. A mapper that converts forward only reads nothing
 * back, and writes a property into as many targets as its rows name. In any mapper a property is
 * either dropped or written, never both, whichever row comes first; a path through a dropped
 * property writes it, since backward rebuilds every property the path steps through.
 *
 * <p>A drop beside a write is refused at the second of the two rows. A property written into
 * several targets is refused once every write of the pair is recorded, so the refusal names each
 * target once, in the order the writes came.
 */
public final class SourceWrites {

  private final String source;
  private final String target;
  private final boolean backward;
  private final Map<String, Set<String>> written = new LinkedHashMap<>();
  private final Set<String> dropped = new HashSet<>();
  private final Set<String> fannedOut = new LinkedHashSet<>();

  /**
   * The writes of the pair of {@code source} and {@code target}, named as messages show them, for a
   * mapper that converts {@code backward} as well as forward, or forward only.
   */
  public SourceWrites(final String source, final String target, final boolean backward) {
    this.source = source;
    this.target = target;
    this.backward = backward;
  }

  /**
   * Records a write of source property {@code srcField}, or of the source path it names with dots,
   * into target {@code tgtField}, a property or a path below one: the refusal of a property also
   * dropped, or null.
   */
  public String write(final String srcField, final String tgtField) {
    final var throughDropped = droppedOn(srcField);
    if (throughDropped != null) return refusal(throughDropped, srcField);
    final var targets = written.computeIfAbsent(srcField, unused -> new LinkedHashSet<>());
    if (targets.add(tgtField) && targets.size() > 1 && backward) fannedOut.add(srcField);
    return null;
  }

  /** Records a row dropping source property {@code srcField}: the refusal, or null. */
  public String drop(final String srcField) {
    for (final var path : written.keySet()) {
      if (path.equals(srcField) || path.startsWith(srcField + ".")) return refusal(srcField, path);
    }
    dropped.add(srcField);
    return null;
  }

  /** The dropped property {@code path} steps through, or null. */
  private String droppedOn(final String path) {
    var end = path.indexOf('.');
    while (true) {
      final var prefix = end < 0 ? path : path.substring(0, end);
      if (dropped.contains(prefix)) return prefix;
      if (end < 0) return null;
      end = path.indexOf('.', end + 1);
    }
  }

  /** The refusal of dropped property {@code dropped} beside a write of {@code path}. */
  private String refusal(final String dropped, final String path) {
    return path.equals(dropped)
      ? PairingMessages.droppedAndWritten(source, target, dropped)
      : PairingMessages.droppedAndReadThrough(source, target, dropped, path);
  }

  /**
   * Whether the writes recorded so far put {@code srcField} into a second target that is refused.
   */
  public boolean fannedOut(final String srcField) {
    return fannedOut.contains(srcField);
  }

  /**
   * The refusal of the first source property or path the writes put into more than one target, in
   * the order each reached its second target, naming every target it is written into; or null.
   */
  public String fanOutRefusal() {
    if (fannedOut.isEmpty()) return null;
    final var srcField = fannedOut.iterator().next();
    return PairingMessages.fanOutBothWays(source, target, srcField, List.copyOf(written.get(srcField)));
  }
}
