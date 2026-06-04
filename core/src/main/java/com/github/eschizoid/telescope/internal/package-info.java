/**
 * Internal implementation details shared across the library. Not exported by the {@code
 * com.github.eschizoid.telescope} module — types here are free to evolve without notice.
 *
 * <ul>
 *   <li>{@link com.github.eschizoid.telescope.internal.Records} — record-aware reflection helpers:
 *       canonical-constructor lookup, component accessors, structural rebuilds. Field navigation in
 *       {@code Telescope} ultimately funnels through here.
 *   <li>{@link com.github.eschizoid.telescope.internal.Beans} — getter/setter reflection helpers
 *       used by {@link com.github.eschizoid.telescope.Telescope#fromBean} and by the generated
 *       {@code @Bridge} code to map POJOs to records and back.
 * </ul>
 *
 * <p>The optic lattice and the HKT-emulation machinery live under {@code
 * com.github.eschizoid.telescope.internal.optics} (and its sub-packages); see that package's
 * documentation for the wider picture.
 */
package com.github.eschizoid.telescope.internal;
