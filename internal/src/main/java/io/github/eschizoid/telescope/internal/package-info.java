/**
 * Internal implementation details shared across the library. Not exported by the {@code
 * io.github.eschizoid.telescope} module — types here are free to evolve without notice.
 *
 * <ul>
 *   <li>{@link io.github.eschizoid.telescope.internal.Records} — record-aware reflection helpers:
 *       canonical-constructor lookup, component accessors, structural rebuilds. Field navigation in
 *       {@code Telescope} ultimately funnels through here.
 *   <li>{@link io.github.eschizoid.telescope.internal.Beans} — getter/setter reflection helpers
 *       used by the bean side of the deep mapping factory and by the generated {@code @Bridge} code
 *       to map POJOs to records and back.
 *   <li>{@link io.github.eschizoid.telescope.internal.Reflective} — the uniform read/construct
 *       interface DeepMap drives, with implementations for records and beans.
 *   <li>{@link io.github.eschizoid.telescope.internal.LambdaIntrospection} — {@code
 *       SerializedLambda} decode that recovers method-reference metadata (impl method name +
 *       declaring class) at runtime.
 *   <li>{@link io.github.eschizoid.telescope.internal.MetadataHolderProbe} — {@code ClassValue}-
 *       cached lookup for sibling {@code <X>FieldOptics} metadata holders emitted by {@code @Focus}
 *       / {@code @BeanFocus}. When present, runtime navigation reads codegen-emitted constants
 *       directly instead of going through the LMF reflective path.
 *   <li>{@link io.github.eschizoid.telescope.internal.NullDefaults} — JLS-default substitution
 *       table behind {@code NullHint.NullStrategy#DEFAULT} and the {@code mapperForward(...)} +
 *       {@code @Bridge(lenient = true)} lenient-fill paths.
 * </ul>
 *
 * <p>The optic lattice and the HKT-emulation machinery live under {@code
 * io.github.eschizoid.telescope.internal.optics} (and its sub-packages); see that package's
 * documentation for the wider picture.
 */
package io.github.eschizoid.telescope.internal;
