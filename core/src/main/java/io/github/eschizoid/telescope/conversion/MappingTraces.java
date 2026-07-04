package io.github.eschizoid.telescope.conversion;

import io.github.eschizoid.telescope.internal.Reflective;
import io.github.eschizoid.telescope.introspection.OpticNode;
import io.github.eschizoid.telescope.introspection.Trace;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the value-column {@code trace} for a mapper: apply the mapping, then for each resolved row
 * show the source field's value flowing to the target field's value. Shared by {@link Mapper} and
 * {@link ForwardMapper}. A mapping trace is flat (one row per field correspondence, no fan-out) —
 * the tree shape is a navigation concern.
 */
final class MappingTraces {

  private MappingTraces() {}

  /**
   * @param input the source value the mapper ran against
   * @param output the mapped result ({@code forward(input)})
   * @param trail the resolved {@link OpticNode} rows (the mapper's {@code explain()} trail)
   */
  static Trace of(final Object input, final Object output, final List<OpticNode> trail) {
    // Two passes: gather the columns, then pad so every arrow lines up, matching explain()'s
    // layout.
    final var rows = new ArrayList<Row>();
    for (final var node : trail) rows.add(rowFor(node, input, output));
    final var fromWidth = rows
      .stream()
      .mapToInt(r -> r.from().length())
      .max()
      .orElse(0);
    final var inWidth = rows
      .stream()
      .mapToInt(r -> r.valueIn().length())
      .max()
      .orElse(0);
    final var nodes = new ArrayList<Trace.Node>();
    for (final var r : rows) nodes.add(Trace.Node.leaf(r.render(fromWidth, inWidth)));
    return new Trace(nodes);
  }

  private static Row rowFor(final OpticNode node, final Object input, final Object output) {
    if (node instanceof OpticNode.Mapped m) return new Row(
      "✓",
      m.from(),
      render(readDotted(input, m.from())),
      m.to() + " " + render(readDotted(output, m.to()))
    );
    if (node instanceof OpticNode.Transformed t) return new Row(
      "•",
      t.from(),
      render(readDotted(input, t.from())),
      t.to() + " " + render(readDotted(output, t.to()))
    );
    if (node instanceof OpticNode.Skipped s) return new Row("•", s.field(), "", "(" + label(s.reason()) + ")");
    if (node instanceof OpticNode.UnusedSource u) return new Row("•", u.field(), "", "(unused source)");
    return new Row("•", String.valueOf(node), "", "");
  }

  // Sentinel for a read that could not apply — distinct from a legitimately-null field value so the
  // trace shows (n/a), not "null", never conflating a swallowed read failure with a real null.
  private static final Object UNREADABLE = new Object();

  private static Object readDotted(final Object root, final String path) {
    var current = root;
    for (final var segment : path.split("\\.")) {
      if (current == null) return null; // a legitimately-null intermediate — rendered as "null"
      try {
        current = Reflective.of(current.getClass()).read(current, segment);
      } catch (final RuntimeException e) {
        return UNREADABLE; // a debug aid never throws on a read that doesn't apply
      }
    }
    return current;
  }

  private static String render(final Object value) {
    if (value == UNREADABLE) return "(n/a)";
    if (value == null) return "null";
    if (value instanceof String s) return "\"" + s + "\"";
    return String.valueOf(value);
  }

  private static String label(final OpticNode.Reason reason) {
    return switch (reason) {
      case DROPPED -> "dropped";
      case MISSING_SOURCE -> "missing source";
    };
  }

  private static String pad(final String s, final int width) {
    return s.length() >= width ? s : s + " ".repeat(width - s.length());
  }

  /** One trace row: a marker, the source field, its value, and the target side (already joined). */
  private record Row(String marker, String from, String valueIn, String right) {
    String render(final int fromWidth, final int inWidth) {
      // Pad the field and value columns so every arrow lines up — skips keep an empty value column.
      return marker + " " + pad(from, fromWidth) + "  " + pad(valueIn, inWidth) + " → " + right;
    }
  }
}
