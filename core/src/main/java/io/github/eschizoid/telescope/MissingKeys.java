package io.github.eschizoid.telescope;

import java.util.ArrayList;
import java.util.Map;
import java.util.StringJoiner;

/**
 * The refusal a {@code fromMap} binder makes when a key its required rows name carries no value.
 * Every such key is named in one message, each beside the component it was to fill, because the two
 * need not share a name and a reader of the message has the map in one hand and the target in the
 * other. The binder generated for a {@code @FromMap(required = ...)} type words the same refusal
 * the same way after its own prefix.
 *
 * <p>Asked of the whole source before any value is converted, so a refusal never follows a
 * converter's side effects, and it does not depend on which slots the target's writer goes on to
 * read.
 */
final class MissingKeys {

  private static final MissingKeys NONE = new MissingKeys("", "", new String[0], new String[0]);

  private final String target;
  private final String kind;
  private final String[] keys;
  private final String[] slots;

  private MissingKeys(final String target, final String kind, final String[] keys, final String[] slots) {
    this.target = target;
    this.kind = kind;
    this.keys = keys;
    this.slots = slots;
  }

  /**
   * The check for the slots whose {@code required} flag is set, reading each slot's value from
   * {@code keys}. A binder with no required row gets a check that does nothing.
   */
  static MissingKeys of(
    final Class<?> target,
    final String kind,
    final String[] keys,
    final String[] slots,
    final boolean[] required
  ) {
    final var requiredKeys = new ArrayList<String>();
    final var requiredSlots = new ArrayList<String>();
    for (var i = 0; i < required.length; i++) {
      if (!required[i]) continue;
      requiredKeys.add(keys[i]);
      requiredSlots.add(slots[i]);
    }
    if (requiredKeys.isEmpty()) return NONE;
    return new MissingKeys(
      target.getSimpleName(),
      kind,
      requiredKeys.toArray(String[]::new),
      requiredSlots.toArray(String[]::new)
    );
  }

  /** Throws naming every required key {@code source} carries no value for, or returns. */
  void check(final Map<String, Object> source) {
    StringJoiner missing = null;
    var count = 0;
    for (var i = 0; i < keys.length; i++) {
      if (source.get(keys[i]) != null) continue;
      if (missing == null) missing = new StringJoiner(", ");
      missing.add("\"" + keys[i] + "\" (" + kind + " '" + slots[i] + "')");
      count++;
    }
    if (missing != null) throw new IllegalArgumentException(
      "Telescope.fromMap: the map carries no value for required " +
        (count == 1 ? "key " : "keys ") +
        missing +
        " of " +
        target
    );
  }
}
