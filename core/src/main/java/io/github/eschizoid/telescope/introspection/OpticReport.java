package io.github.eschizoid.telescope.introspection;

import static java.util.stream.Collectors.joining;

import io.github.eschizoid.telescope.introspection.OpticNode.Hop;
import io.github.eschizoid.telescope.introspection.OpticNode.Mapped;
import io.github.eschizoid.telescope.introspection.OpticNode.Skipped;
import io.github.eschizoid.telescope.introspection.OpticNode.Transformed;
import io.github.eschizoid.telescope.introspection.OpticNode.UnusedSource;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The result of {@code explain()} — the ordered {@link OpticNode} trail describing what an optic
 * does, data first. The {@link #toString()} render is a <em>view</em>; the API is the structure:
 * iterate {@link #nodes()} or pull a typed slice with {@link #mapped()} / {@link
 * #transformations()} / {@link #skipped()} / {@link #unusedSources()} / {@link #hops()} and assert
 * on it.
 *
 * <pre>{@code
 * // completeness test — a strict mapper skips nothing by construction
 * assertThat(mapper.explain().skipped()).isEmpty();
 *
 * assertThat(mapper.explain().mapped())
 *     .contains(new Mapped("firstName", "givenName"));
 * }</pre>
 *
 * <p>A navigator's report carries {@link Hop} nodes and empty mapping slices; a mapper's report
 * carries the field {@link OpticNode.Row rows}. A bare {@code Telescope.of(…)} identity yields the
 * {@link #isEmpty() empty} report — never a throw.
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

  /**
   * The fields left out of a clean correspondence, with their reasons, in trail order. Per {@link
   * Skipped}, the field is the dropped source field for {@code DROPPED} and the unpopulated target
   * field for {@code MISSING_SOURCE}.
   */
  public List<Skipped> skipped() {
    return nodes.stream().filter(Skipped.class::isInstance).map(Skipped.class::cast).toList();
  }

  /** The source fields with no target consumer, in trail order. */
  public List<UnusedSource> unusedSources() {
    return nodes.stream().filter(UnusedSource.class::isInstance).map(UnusedSource.class::cast).toList();
  }

  /** The navigation steps of a path, in trail order. */
  public List<Hop> hops() {
    return nodes.stream().filter(Hop.class::isInstance).map(Hop.class::cast).toList();
  }

  @Override
  public String toString() {
    if (nodes.isEmpty()) return "(empty optic)";
    final var mapped = mapped();
    final var skipped = skipped();
    final var transformed = transformations();
    final var unused = unusedSources();
    // One left-column width shared across every mapping row (not per-section), so the marker,
    // field, and the → / ( that follows all land in the same column — the whole report reads as a
    // single aligned table rather than four independently-padded sections. The left cell is the
    // field for a mapped/skipped/unused row, and "field(Type)" for a transformation.
    final var leftWidth = Stream.of(
      mapped.stream().map(Mapped::from),
      skipped.stream().map(Skipped::field),
      transformed.stream().map(t -> t.from() + "(" + t.fromType() + ")"),
      unused.stream().map(UnusedSource::field)
    )
      .flatMap(Function.identity())
      .mapToInt(String::length)
      .max()
      .orElse(0);
    // Each non-empty part is one block; blocks are joined with a blank line. A block per mapping
    // section (in read order: what mapped, what was left out and why, what changed type, the unused
    // source residue) plus one block for the navigation hops. Joining — rather than appending a
    // trailing blank per section — keeps the spacing correct for a mixed report (a mapping
    // telescope
    // further navigated, e.g. map(A, B).field(B::x)), where hops follow the sections.
    final var blocks = new ArrayList<String>();
    section(blocks, "Mapped", mapped, m -> row("✓", m.from(), leftWidth, "→ " + m.to()));
    section(blocks, "Skipped", skipped, s -> row("•", s.field(), leftWidth, "(" + label(s.reason()) + ")"));
    section(blocks, "Transformations", transformed, t ->
      row(
        "•",
        t.from() + "(" + t.fromType() + ")",
        leftWidth,
        "→ " + (t.from().equals(t.to()) ? "" : t.to() + " ") + t.toType()
      )
    );
    section(blocks, "Unused sources", unused, u -> row("•", u.field(), leftWidth, ""));
    // Navigation hops render headingless and un-indented — a path reads as a sequence of steps.
    final var hops = hops();
    if (!hops.isEmpty()) blocks.add(hops.stream().map(OpticReport::renderHop).collect(joining("\n")));
    return String.join("\n\n", blocks);
  }

  // A mapping row: two-space indent, marker, the left cell padded to the shared column, then the
  // right cell. A row with no right cell (an unused source) is emitted without trailing padding.
  private static String row(final String marker, final String left, final int leftWidth, final String right) {
    final var head = "  " + marker + " " + left;
    return right.isEmpty() ? head : head + " ".repeat(leftWidth - left.length() + 1) + right;
  }

  private static <T> void section(
    final List<String> blocks,
    final String heading,
    final List<T> rows,
    final Function<T, String> render
  ) {
    if (rows.isEmpty()) return;
    blocks.add(heading + ":\n" + rows.stream().map(render).collect(joining("\n")));
  }

  private static String label(final OpticNode.Reason reason) {
    return switch (reason) {
      case DROPPED -> "ignored";
      case MISSING_SOURCE -> "missing source";
    };
  }

  private static String renderHop(final Hop node) {
    // Labels padded to a common width so the hop details line up.
    if (node instanceof OpticNode.Focus f) return "Focus:    " + f.path();
    if (node instanceof OpticNode.Traverse t) return "Traverse: " + t.path() + " (" + t.container() + ")";
    if (node instanceof OpticNode.Filter f) return "Filter:   " + f.description();
    if (node instanceof OpticNode.Narrow n) return "Narrow:   " + n.targetType();
    if (node instanceof OpticNode.Bridge b) return "Bridge:   " + b.targetType();
    return String.valueOf(node);
  }
}
