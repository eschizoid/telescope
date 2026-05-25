/**
 * The optic lattice that powers {@link org.telescope.Telescope}. Package-private to the library.
 *
 * <h2>The lattice</h2>
 *
 * <p>Each optic captures a different shape of focus into a structure {@code S} and what you can do
 * with it. They share a common read interface ({@link org.telescope.internal.optics.Fold}) and a
 * common write interface ({@link org.telescope.internal.optics.Setter}).
 *
 * <pre>
 *                  Fold                 (read many)
 *                 /    \
 *           Getter      Setter          (write many)
 *              \         /
 *               \       /
 *               Traversal               (read+write many)
 *                  |
 *                Affine                 (at-most-one)
 *                /     \
 *             Lens    Prism             (exactly-one  /  at-most-one + reconstruct)
 *                \    /
 *                 Iso                   (reversible exactly-one ↔ exactly-one)
 * </pre>
 *
 * <h2>When to pick which</h2>
 *
 * <ul>
 *   <li>{@link org.telescope.internal.optics.Lens} — a required record component (e.g. {@code
 *       User::name}). The focused {@code A} is always there.
 *   <li>{@link org.telescope.internal.optics.Prism} — a sealed-type case or an {@code Optional}
 *       payload. Sometimes the {@code A} is there, sometimes not, but if you have one you can
 *       rebuild an {@code S}.
 *   <li>{@link org.telescope.internal.optics.Iso} — a lossless conversion between {@code A} and
 *       {@code B} (entity ↔ DTO, Celsius ↔ Fahrenheit).
 *   <li>{@link org.telescope.internal.optics.Affine} — the result of mixing single-focus optics in
 *       a way that loses reconstructibility (e.g. {@code Lens.then(Prism)}).
 *   <li>{@link org.telescope.internal.optics.Traversal} — many foci (collections, filters,
 *       broadcast). Once you widen to a Traversal you can't narrow back.
 *   <li>{@link org.telescope.internal.optics.Getter} / {@link org.telescope.internal.optics.Setter}
 *       — read-only or write-only specializations that fall out of the lattice. Rarely used
 *       directly.
 * </ul>
 *
 * <h2>Composition table</h2>
 *
 * <p>Composing two optics picks the most-specific result type:
 *
 * <pre>
 * Outer ↘ Inner | Lens   | Prism  | Iso    | Affine | Traversal
 * --------------|--------|--------|--------|--------|----------
 * Lens          | Lens   | Affine | Lens   | Affine | Traversal
 * Prism         | Affine | Prism  | Prism  | Affine | Traversal
 * Iso           | Lens   | Prism  | Iso    | Affine | Traversal
 * Affine        | Affine | Affine | Affine | Affine | Traversal
 * Traversal     | Trav.  | Trav.  | Trav.  | Trav.  | Traversal
 * </pre>
 *
 * <p>An {@code Iso} extends both {@code Lens} and {@code Prism}, so the {@code Iso.then(Lens)} and
 * {@code Iso.then(Prism)} cases are resolved by explicit overrides on {@link
 * org.telescope.internal.optics.Iso}.
 *
 * <h2>Laws (what tests check)</h2>
 *
 * <ul>
 *   <li>Lens get-set / set-get / set-set — see {@link org.telescope.internal.optics.Lens}.
 *   <li>Iso forward and backward round-trip — see {@link org.telescope.internal.optics.Iso}.
 *   <li>Prism partial round-trip — see {@link org.telescope.internal.optics.Prism}.
 * </ul>
 *
 * <h2>Why this is internal</h2>
 *
 * <p>The DSL ({@code Telescope<S, A>}) wraps a {@link org.telescope.internal.optics.Traversal} and
 * never exposes the lattice types in its public signatures. The lattice is here because it's the
 * proven substrate (Haskell {@code lens} → Scala Monocle → Arrow → Higher-Kinded-J) and reusing it
 * gives us correct composition rules and laws for free. If a future use case needs the optic types
 * as public API, lifting the package boundary is one edit.
 */
package org.telescope.internal.optics;
