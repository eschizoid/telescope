package io.github.eschizoid.telescope.introspection;

/**
 * One entry in an optic's introspection trail — the world-agnostic description of a single thing a
 * {@code Telescope}, {@code Mapper}, or {@code ForwardMapper} does, surfaced by {@code explain()}
 * (structure only) and {@code trace(input)} (structure plus the value that flowed through it).
 *
 * <p>The family is unified across the two shapes an optic can take, designed complete from the
 * start so neither surface is a breaking change later:
 *
 * <ul>
 *   <li><b>Navigation hops</b> — {@link Focus}, {@link Traverse}, {@link Filter}, {@link Narrow},
 *       {@link Bridge} — the steps of a path built by {@code of(…).field(…).each(…)…}.
 *   <li><b>Mapping rows</b> — {@link Mapped}, {@link Transformed}, {@link Skipped} — the field
 *       correspondences of a conversion built by {@code map} / {@code mapper} / {@code
 *       mapperForward} / {@code fromMap}.
 * </ul>
 *
 * <p>Every variant is a public record so a caller can assert on it directly ({@code
 * assertThat(report.mapped()).contains(new Mapped("firstName", "firstName", "givenName"))}); the
 * nodes are derived from the same decisions the optic uses to build itself, so the trail cannot
 * drift from what the optic actually does.
 */
public sealed interface OpticNode {
  /** Why a target field was not populated by the mapping. */
  enum Reason {
    /** An explicit {@code Mapping.drop(src)} row removed the field. */
    DROPPED,
    /**
     * A target field with no same-name source and no row — lenient / {@code fromMap} paths only.
     */
    MISSING_SOURCE,
    /** A source field with no target consumer — the unmatched-sources residue. */
    UNMAPPED_SOURCE,
  }

  // ---- Navigation hops -----------------------------------------------------------------------

  /**
   * A single-focus step onto a named field — {@code .field(User::email)} → {@code Focus("email")}.
   */
  record Focus(String path) implements OpticNode {}

  /**
   * A many-focus step over a container — {@code .each(Team::users)} → {@code Traverse("users",
   * "List<User>")}. In a {@code trace}, this is where the walk fans out into per-element subtrees.
   */
  record Traverse(String path, String container) implements OpticNode {}

  /** A predicate restriction — {@code .filter(pred)} → {@code Filter("age > 18")}. */
  record Filter(String description) implements OpticNode {}

  /** A sealed-type narrowing — {@code .as(Dog.class)} → {@code Narrow("Dog")}. */
  record Narrow(String targetType) implements OpticNode {}

  /** A cross-paradigm bridge hop — {@code .asUserDto()} → {@code Bridge("UserDto")}. */
  record Bridge(String targetType) implements OpticNode {}

  // ---- Mapping rows --------------------------------------------------------------------------

  /**
   * A same-typed correspondence: a source field lands on a target field unchanged (same-name auto
   * or an explicit rename row). {@code path} is the dotted target path for nested pairs.
   */
  record Mapped(String path, String from, String to) implements OpticNode {}

  /**
   * A type-changing correspondence: a typed-transform row or a cross-type pairing decision
   * (primitive↔wrapper, Optional bridge, container lift).
   */
  record Transformed(String field, String fromType, String toType) implements OpticNode {}

  /** A target field the mapping does not populate, with the reason it was left out. */
  record Skipped(String field, Reason reason) implements OpticNode {}
}
