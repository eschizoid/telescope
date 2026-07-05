package io.github.eschizoid.telescope.introspection;

import java.util.List;

/**
 * The result of {@code trace(input)} — the optic's structure executed against a concrete value,
 * with each step's actual value filled in and many-focus steps ({@code each} / {@code eachValue} /
 * {@code whenPresent}) expanded into per-element subtrees. Where {@code explain()} is the static
 * skeleton, {@code trace} is that skeleton with a value column and branch expansion.
 *
 * <p>Data first: walk {@link #roots()} and the {@link Node#children()} to assert on the shape; the
 * {@link #toString()} render draws the {@code ├ / └} tree as a view.
 */
public record Trace(List<Node> roots) {
  public Trace {
    roots = List.copyOf(roots);
  }

  /**
   * One node in the trace tree: a rendered {@code label} for what happened at this step (a hop
   * applied, an element reached, a leaf value), its {@code children}, and whether it was cut short
   * by a {@link TraceLimits} cap.
   */
  public record Node(String label, List<Node> children, boolean truncated) {
    public Node {
      children = List.copyOf(children);
      // A cut is terminal — the marker stands in for the elided subtree, so it never has children.
      if (truncated && !children.isEmpty()) throw new IllegalArgumentException("a truncated node cannot have children");
    }

    /** A leaf node — no children, not truncated. */
    public static Node leaf(final String label) {
      return new Node(label, List.of(), false);
    }

    /** A truncation marker — the {@code … (+K more)} / depth-cap cut. */
    public static Node cut(final String label) {
      return new Node(label, List.of(), true);
    }
  }

  @Override
  public String toString() {
    final var out = new StringBuilder();
    for (final var root : roots) render(out, root, "");
    return out.toString().stripTrailing();
  }

  private static void render(final StringBuilder out, final Node node, final String prefix) {
    out.append(node.label()).append('\n');
    final var children = node.children();
    for (var i = 0; i < children.size(); i++) {
      final var isLast = i == children.size() - 1;
      out.append(prefix).append(isLast ? " └ " : " ├ ");
      render(out, children.get(i), prefix + (isLast ? "   " : " │ "));
    }
  }
}
