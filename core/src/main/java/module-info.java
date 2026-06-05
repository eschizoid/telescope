/**
 * Telescope — deep-copy DSL for Java records and POJOs.
 *
 * <p>Two packages are exported: {@code io.github.eschizoid.telescope} (the DSL — {@link
 * io.github.eschizoid.telescope.Telescope} plus the {@link io.github.eschizoid.telescope.Either} /
 * {@link io.github.eschizoid.telescope.Validated} / {@link io.github.eschizoid.telescope.Indexed}
 * value types referenced in its signatures) and {@code io.github.eschizoid.telescope.annotations}
 * (the {@code @Focus} / {@code @Bridge} codegen markers). The {@code
 * io.github.eschizoid.telescope.internal} packages are deliberately not exported: they hold the
 * optic lattice, the HKT-emulation machinery (`Kind`, `Applicative`), and the per-effect
 * witness/applicative instances.
 *
 * <p>Promoting any internal type to public API in a future version is one {@code exports} line
 * away; until then, encapsulation is enforced at the JPMS level.
 */
module io.github.eschizoid.telescope {
  exports io.github.eschizoid.telescope;
  exports io.github.eschizoid.telescope.annotations;
}
