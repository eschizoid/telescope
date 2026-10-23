package io.github.eschizoid.telescope.internal.pairing;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Which source properties left under their own name have nowhere to be written, decided once for
 * the runtime mapper and the bridge processor.
 *
 * <p>A source property no row reads pairs with the target property of its own name. When a row
 * reading another source property writes that target, as a rename onto it does, the kept property
 * has no target left: a strict mapper refuses it by name, and a lenient one leaves it unread.
 */
public final class KeptNames {

  private KeptNames() {}

  /**
   * The properties of {@code kept}, the source properties no row reads, whose same-named target is
   * in {@code written}, the target properties rows reading other source properties write, in the
   * order {@code kept} gives them.
   */
  public static List<String> taken(final Iterable<String> kept, final Set<String> written) {
    final var taken = new ArrayList<String>();
    if (written.isEmpty()) return taken;
    for (final var name : kept) {
      if (written.contains(name)) taken.add(name);
    }
    return taken;
  }
}
