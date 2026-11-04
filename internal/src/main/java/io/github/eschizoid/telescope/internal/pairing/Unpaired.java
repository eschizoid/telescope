package io.github.eschizoid.telescope.internal.pairing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which properties of a pair a strict mapper refuses for having nothing to pair them with, decided
 * once for the runtime mapper, the bridge processor and the compile-time verifier.
 *
 * <p>A mapper that converts both ways rebuilds each side from the other, so at a pair whose
 * properties it decides it refuses a target property nothing fills and a source property nothing
 * reads. A target property is filled by a row that writes it, whether through an accessor, a path
 * starting at it, or a {@code constant} or {@code compute} row, and otherwise by the source
 * property of its name, when no row reads that one. A source property is read by a row that reads
 * it, whether through an accessor, a {@code drop} or a path starting at it, and otherwise by the
 * target property of its name, when no row writes that one. Which other rows the pair has changes
 * neither.
 *
 * <p>A path whose first property is not known may start at any property of its side, so where one
 * does, no property of that side is refused.
 */
public final class Unpaired {

  private Unpaired() {}

  /**
   * The refusals of the properties {@code match} leaves unpaired that nothing else fills or reads:
   * each target property, in the order {@code match} gives them, then each source property. {@code
   * sourceNames} are the source's properties, which decide whether a target property has a
   * namesake; {@code filled} the target properties path, {@code constant} and {@code compute} rows
   * write, or null when one of them starts at a property not known; {@code read} the source
   * properties path rows read, or null when one of them starts at a property not known. The names
   * of the classes and of what each calls a property are as the messages show them.
   */
  public static List<String> refusals(
    final Sides sides,
    final List<String> sourceNames,
    final PairingRules.MatchResult match,
    final Set<String> filled,
    final Set<String> read
  ) {
    final var refusals = new ArrayList<String>();
    final var namesakes = new HashSet<>(sourceNames);
    if (filled != null) {
      for (final var name : match.unmatchedTargets()) {
        if (filled.contains(name)) continue;
        refusals.add(
          namesakes.contains(name)
            ? PairingMessages.sameNameSourceRead(sides.source(), sides.target(), sides.tgtSlot(), sides.srcSlot(), name)
            : PairingMessages.noSameNameSource(sides.source(), sides.target(), sides.tgtSlot(), sides.srcSlot(), name)
        );
      }
    }
    if (read != null) {
      for (final var name : match.unmatchedSources()) {
        if (read.contains(name)) continue;
        refusals.add(
          PairingMessages.noSameNameTarget(sides.source(), sides.target(), sides.srcSlot(), sides.tgtSlot(), name)
        );
      }
    }
    return refusals;
  }

  /**
   * The pair a refusal names: its source and target classes, and the word each uses for a property,
   * {@code field} for a record and {@code property} for a bean.
   */
  public record Sides(String source, String target, String srcSlot, String tgtSlot) {}
}
