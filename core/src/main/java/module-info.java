/**
 * Telescope — deep-copy DSL for Java records and POJOs.
 *
 * <p>Two packages are exported: {@code org.telescope} (the DSL — {@link org.telescope.Telescope}
 * plus the {@link org.telescope.Either} / {@link org.telescope.Validated} / {@link
 * org.telescope.Indexed} value types referenced in its signatures) and {@code
 * org.telescope.annotations} (the {@code @Focus} / {@code @Bridge} codegen markers). The {@code
 * org.telescope.internal} packages are deliberately not exported: they hold the optic lattice, the
 * HKT-emulation machinery (`Kind`, `Applicative`), and the per-effect witness/applicative
 * instances.
 *
 * <p>Promoting any internal type to public API in a future version is one {@code exports} line
 * away; until then, encapsulation is enforced at the JPMS level.
 */
module org.telescope {
  exports org.telescope;
  exports org.telescope.annotations;
}
