package io.github.eschizoid.telescope.jackson;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.introspection.OpticNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;

/**
 * One JSON object level: the record or bean it builds, and for each of its properties the key it
 * answers to and how the value under that key is read. Every level has its own key table, so an
 * {@code id} at the root and an {@code id} in a nested object never meet.
 *
 * <p>A frame is registered with its {@link Plan} before its properties are resolved, so a type that
 * holds itself, directly or through a list, reads through the same frame at every depth. It is
 * complete before anything reads with it.
 *
 * @param <T> the type the frame builds
 */
final class Frame<T> {

  private final Class<T> type;

  /** The property names, in the order the type is built from. */
  private String[] names;

  /** The JSON key each property reads. */
  private String[] keys;

  /** How each property's value is read; null for one whose key is only checked for presence. */
  private Read[] reads;

  /** The value each property takes when its key is missing or holds {@code null}. */
  private Object[] absents;

  private boolean[] required;

  /** Whether each property is left unbound by its annotations, whatever the document holds. */
  private boolean[] ignored;

  private boolean anyRequired;
  private Map<String, Integer> indexByKey;
  private Function<Object[], T> construct;
  private String kind;
  private String[] declared;

  Frame(final Class<T> type) {
    this.type = type;
  }

  Class<T> type() {
    return type;
  }

  /**
   * Completes the frame.
   *
   * @param kind {@code component} for a record, {@code property} for a bean
   * @param declared each property's declared type as a message names it
   * @param indexByKey the property each key fills, an alias beside the key it stands for
   */
  void complete(
    final String kind,
    final String[] names,
    final String[] keys,
    final String[] declared,
    final Read[] reads,
    final Object[] absents,
    final boolean[] required,
    final boolean[] ignored,
    final Map<String, Integer> indexByKey,
    final Function<Object[], T> construct
  ) {
    this.kind = kind;
    this.names = names;
    this.keys = keys;
    this.declared = declared;
    this.reads = reads;
    this.absents = absents;
    this.required = required;
    this.ignored = ignored;
    this.indexByKey = indexByKey;
    this.construct = construct;
    var any = false;
    for (final var flag : required) any |= flag;
    this.anyRequired = any;
  }

  /**
   * Builds the type from the object under the parser, which is on its {@code START_OBJECT}, and
   * leaves the parser on the matching {@code END_OBJECT}. A key no property reads is skipped with
   * whatever it holds. A key that appears twice keeps the value it holds last.
   */
  T read(final JsonParser parser) {
    final var args = absents.clone();
    final var seen = anyRequired ? new boolean[args.length] : null;
    for (var key = parser.nextName(); key != null; key = parser.nextName()) {
      final var token = parser.nextToken();
      final var index = indexByKey.get(key);
      if (index == null) {
        parser.skipChildren();
        continue;
      }
      final int i = index;
      if (token == JsonToken.VALUE_NULL) {
        args[i] = absents[i];
        if (seen != null) seen[i] = false;
        continue;
      }
      final var read = reads[i];
      if (read == null) {
        parser.skipChildren();
      } else {
        try {
          args[i] = read.from(parser);
        } catch (final Refused refused) {
          throw refused.under(names[i], keys[i]);
        }
      }
      if (seen != null) seen[i] = true;
    }
    if (seen != null) refuseMissing(parser, seen);
    return construct.apply(args);
  }

  /** The binding plan, one row per property, in the order the type is built from. */
  List<OpticNode> explain() {
    final var trail = new ArrayList<OpticNode>(names.length);
    for (var i = 0; i < names.length; i++) {
      if (ignored[i]) {
        trail.add(new OpticNode.Skipped(names[i], OpticNode.Reason.DROPPED));
        continue;
      }
      if (reads[i] == null && !required[i]) {
        trail.add(new OpticNode.Skipped(names[i], OpticNode.Reason.MISSING_SOURCE));
        continue;
      }
      trail.add(
        new OpticNode.Extracted(
          keys[i],
          names[i],
          declared[i],
          required[i] ? OpticNode.WhenAbsent.REFUSES : OpticNode.WhenAbsent.DEFAULTS
        )
      );
    }
    return trail;
  }

  private void refuseMissing(final JsonParser parser, final boolean[] seen) {
    List<String> missing = null;
    for (var i = 0; i < seen.length; i++) {
      if (!required[i] || seen[i]) continue;
      if (missing == null) missing = new ArrayList<>();
      missing.add("\"" + keys[i] + "\" (" + kind + " '" + names[i] + "')");
    }
    if (missing == null) return;
    final var at = parser.currentTokenLocation();
    final var line = at.getLineNr();
    final var column = at.getColumnNr();
    final var named = List.copyOf(missing);
    final var target = type.getSimpleName();
    throw Refused.of((path, jsonPath) ->
      PairingMessages.jsonMissingRequired(path, jsonPath, named, target, line, column)
    );
  }
}
