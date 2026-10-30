package io.github.eschizoid.telescope.internal.pairing;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which rows reading a source property a pair refuses, decided once for the runtime mapper, the
 * bridge processor and the compile-time verifier. One instance follows the rows keyed to one pair,
 * in the order they are given.
 *
 * <p>A mapper that converts backward rebuilds each source property from one target, so rows of such
 * a mapper write a source property into one target at most: a second target leaves backward no
 * defined target to read the property from. A mapper that converts forward only reads nothing back,
 * and writes a property into as many targets as its rows name. In any mapper a property is either
 * dropped or written, never both, whichever row comes first.
 */
public final class SourceWrites {

  private final String source;
  private final String target;
  private final boolean backward;
  private final Map<String, String> written = new HashMap<>();
  private final Set<String> dropped = new HashSet<>();

  /**
   * The rows of the pair of {@code source} and {@code target}, named as messages show them, for a
   * mapper that converts {@code backward} as well as forward, or forward only.
   */
  public SourceWrites(final String source, final String target, final boolean backward) {
    this.source = source;
    this.target = target;
    this.backward = backward;
  }

  /**
   * Records a row writing source property {@code srcField} into target property {@code tgtField}:
   * the refusal, or null when the row is accepted.
   */
  public String write(final String srcField, final String tgtField) {
    if (dropped.contains(srcField)) return PairingMessages.droppedAndWritten(source, target, srcField);
    final var first = written.putIfAbsent(srcField, tgtField);
    if (first != null && backward) {
      return PairingMessages.fanOutBothWays(source, target, srcField, first, tgtField);
    }
    return null;
  }

  /** Records a row dropping source property {@code srcField}: the refusal, or null. */
  public String drop(final String srcField) {
    if (written.containsKey(srcField)) return PairingMessages.droppedAndWritten(source, target, srcField);
    dropped.add(srcField);
    return null;
  }
}
