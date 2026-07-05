package io.github.eschizoid.telescope.introspection;

/**
 * One entry in an optic's introspection trail — the world-agnostic description of a single thing a
 * {@code Telescope}, {@code Mapper}, or {@code ForwardMapper} does, surfaced by {@code explain()}
 * (structure only) and {@code trace(input)} (structure plus the value that flowed through it).
 *
 * <p>The family splits at the top into the two shapes an optic can take, so the navigation/mapping
 * distinction is a type invariant rather than a comment:
 *
 * <ul>
 *   <li>{@link Hop} — a step of a path built by {@code of(…).field(…).each(…)…}: {@link Focus},
 *       {@link Traverse}, {@link Filter}, {@link Narrow}, {@link Bridge}.
 *   <li>{@link Row} — a field correspondence of a conversion built by {@code map} / {@code mapper}
 *       / {@code mapperForward} / {@code fromMap}: {@link Mapped}, {@link Transformed}, {@link
 *       Skipped}, {@link UnusedSource}.
 * </ul>
 *
 * <p>Every variant is a public record so a caller can assert on it directly ({@code
 * assertThat(report.mapped()).contains(new Mapped("firstName", "givenName"))}); the nodes are
 * derived from the same decisions the optic uses to build itself, so the trail cannot drift from
 * what the optic actually does.
 */
public sealed interface OpticNode {
  /** Why a target field was not populated by the mapping. */
  enum Reason {
    /** An explicit {@code Mapping.drop(src)} row removed a source field from the mapping. */
    DROPPED,
    /**
     * A target field with no same-name source and no row — lenient / {@code fromMap} paths only.
     */
    MISSING_SOURCE,
  }

  /** A navigation step of a path. */
  sealed interface Hop extends OpticNode {}

  /** A field correspondence of a conversion. */
  sealed interface Row extends OpticNode {}

  // ---- Navigation hops -----------------------------------------------------------------------

  /**
   * A single-focus step onto a named field — {@code .field(User::email)} → {@code Focus("email")}.
   */
  record Focus(String path) implements Hop {}

  /**
   * A many-focus step over a container — {@code .each(Team::users)} → {@code Traverse("users",
   * "collection")}. {@code container} is a family label ({@code "collection"} / {@code "map
   * values"} / {@code "optional"}), not the element type. In a {@code trace}, this is where the
   * walk fans out into per-element subtrees.
   */
  record Traverse(String path, String container) implements Hop {}

  /**
   * A predicate restriction — {@code .filter(pred)} → {@code Filter("predicate")}. The description
   * is a fixed placeholder: a lambda predicate cannot be recovered, so trace annotates the step
   * without applying it.
   */
  record Filter(String description) implements Hop {}

  /** A sealed-type narrowing — {@code .as(Dog.class)} → {@code Narrow("Dog")}. */
  record Narrow(String targetType) implements Hop {}

  /** A cross-paradigm bridge hop — {@code .asUserDto()} → {@code Bridge("UserDto")}. */
  record Bridge(String targetType) implements Hop {}

  // ---- Mapping rows --------------------------------------------------------------------------

  /**
   * A same-typed correspondence: a source field lands on a target field unchanged. {@code from ==
   * to} is a same-name auto pair; distinct names are an explicit rename row. For nested pairs both
   * carry the dotted path (e.g. {@code new Mapped("address.city", "address.city")}).
   */
  record Mapped(String from, String to) implements Row {}

  /**
   * A type-changing correspondence: a typed-transform row or a cross-type pairing decision
   * (primitive↔wrapper, Optional bridge, container lift). {@code from} / {@code to} are the source
   * and target field names (equal for a same-name transform, distinct for a renamed one); {@code
   * fromType} / {@code toType} are their type names.
   */
  record Transformed(String from, String to, String fromType, String toType) implements Row {}

  /**
   * A field the mapping leaves out of a clean correspondence, with the reason. The side {@code
   * field} names depends on the reason: {@link Reason#DROPPED} names the explicitly-dropped
   * <em>source</em> field ({@code Mapping.drop(src)}), while {@link Reason#MISSING_SOURCE} names
   * the unpopulated <em>target</em> field. A source field with no consumer (the lenient residue) is
   * an {@link UnusedSource} instead.
   */
  record Skipped(String field, Reason reason) implements Row {}

  /** A source field with no target consumer — the unmatched-sources residue of a lenient mapper. */
  record UnusedSource(String field) implements Row {}
}
