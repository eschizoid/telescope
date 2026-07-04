package io.github.eschizoid.telescope.introspection;

import io.github.eschizoid.telescope.introspection.OpticNode.Mapped;
import io.github.eschizoid.telescope.introspection.OpticNode.Skipped;
import io.github.eschizoid.telescope.introspection.OpticNode.Transformed;
import java.util.List;

/**
 * The result of {@code explain()} — the ordered {@link OpticNode} trail describing what an optic
 * does, data first. The {@link #toString()} render is a <em>view</em>; the API is the structure:
 * iterate {@link #nodes()} or pull a typed slice with {@link #mapped()} / {@link
 * #transformations()} / {@link #skipped()} and assert on it.
 *
 * <pre>{@code
 * // completeness test — a strict mapper skips nothing by construction
 * assertThat(mapper.explain().skipped()).isEmpty();
 *
 * assertThat(mapper.explain().mapped())
 *     .contains(new Mapped("firstName", "firstName", "givenName"));
 * }</pre>
 *
 * <p>A navigator's report carries hop nodes ({@link OpticNode.Focus} / {@link OpticNode.Traverse} /
 * …) and empty mapping slices; a mapper's report carries the field rows. A bare {@code
 * Telescope.of(…)} identity yields the {@link #isEmpty() empty} report — never a throw.
 */
public record OpticReport(List<OpticNode> nodes) {
  public OpticReport {
    nodes = List.copyOf(nodes);
  }

  /** True when the optic contributes no describable step (e.g. a bare identity telescope). */
  public boolean isEmpty() {
    return nodes.isEmpty();
  }

  /** The same-typed correspondences, in trail order. */
  public List<Mapped> mapped() {
    return nodes.stream().filter(Mapped.class::isInstance).map(Mapped.class::cast).toList();
  }

  /** The type-changing correspondences, in trail order. */
  public List<Transformed> transformations() {
    return nodes.stream().filter(Transformed.class::isInstance).map(Transformed.class::cast).toList();
  }

  /** The unpopulated target fields with their reasons, in trail order. */
  public List<Skipped> skipped() {
    return nodes.stream().filter(Skipped.class::isInstance).map(Skipped.class::cast).toList();
  }

  @Override
  public String toString() {
    if (nodes.isEmpty()) return "(no mapping)";
    final var out = new StringBuilder();
    final var mapped = mapped();
    // Left-pad the source column so every → lines up, matching the report's aligned layout.
    final var fromWidth = mapped
      .stream()
      .mapToInt(m -> m.from().length())
      .max()
      .orElse(0);
    renderSection(out, "Mapped", mapped, m -> "  ✓ " + pad(m.from(), fromWidth) + " → " + m.to());
    renderSection(
      out,
      "Transformations",
      transformations(),
      t -> "  • " + t.field() + "(" + t.fromType() + ") → " + t.toType()
    );
    final var skipped = skipped();
    final var skipWidth = skipped
      .stream()
      .mapToInt(s -> s.field().length())
      .max()
      .orElse(0);
    renderSection(out, "Skipped", skipped, s -> "  • " + pad(s.field(), skipWidth) + "  (" + label(s.reason()) + ")");
    // Navigation hops render headingless and un-indented — a path reads as a sequence of steps.
    for (final var hop : hops()) out.append(renderHop(hop)).append('\n');
    return out.toString().stripTrailing();
  }

  private static String pad(final String s, final int width) {
    return s.length() >= width ? s : s + " ".repeat(width - s.length());
  }

  private List<OpticNode> hops() {
    return nodes
      .stream()
      .filter(n -> !(n instanceof Mapped) && !(n instanceof Transformed) && !(n instanceof Skipped))
      .toList();
  }

  private static <T> void renderSection(
    final StringBuilder out,
    final String heading,
    final List<T> rows,
    final java.util.function.Function<T, String> render
  ) {
    if (rows.isEmpty()) return;
    out.append(heading).append(":\n");
    for (final var row : rows) out.append(render.apply(row)).append('\n');
  }

  private static String label(final OpticNode.Reason reason) {
    return switch (reason) {
      case DROPPED -> "dropped";
      case MISSING_SOURCE -> "missing source";
      case UNMAPPED_SOURCE -> "unmapped source";
    };
  }

  private static String renderHop(final OpticNode node) {
    // Labels padded to a common width so the hop details line up.
    if (node instanceof OpticNode.Focus f) return "Focus:    " + f.path();
    if (node instanceof OpticNode.Traverse t) return "Traverse: " + t.path() + " (" + t.container() + ")";
    if (node instanceof OpticNode.Filter f) return "Filter:   " + f.description();
    if (node instanceof OpticNode.Narrow n) return "Narrow:   " + n.targetType();
    if (node instanceof OpticNode.Bridge b) return "Bridge:   " + b.targetType();
    return String.valueOf(node);
  }
}
