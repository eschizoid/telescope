package io.github.eschizoid.telescope.internal.pairing;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The rule for the properties a persistence mapping generates, an identifier with a generator and a
 * version, at every pair an edit mapper writes: no conversion writes one into its target, whether
 * it builds a new object or writes into one it is handed, an element's included, whatever the
 * source sends under its name. A row naming one writes it. The source's namesake is the key a merge
 * by identifier matches a sent element to a held one by, read from the source, so the property is
 * accounted for with no row. The runtime mapper, the processor and the verifier take this one
 * decision.
 */
public final class GeneratedProperties {

  private GeneratedProperties() {}

  /**
   * The properties of {@code generated}, those of a target its persistence mapping generates, that
   * an edit mapper's conversion of the pair never writes: each one no row writes, as {@code
   * writtenByRow} answers.
   *
   * @param generated the target's generated properties
   * @param writtenByRow whether a row of the pair writes a property
   * @return the properties no conversion writes, in the order given
   */
  public static List<String> unwritten(final List<String> generated, final Predicate<String> writtenByRow) {
    final var unwritten = new ArrayList<String>(generated.size());
    for (final var name : generated) if (!writtenByRow.test(name)) unwritten.add(name);
    return unwritten;
  }
}
