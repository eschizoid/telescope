/**
 * Compile-time markers consumed by the telescope annotation processors. Exported as part of the
 * library's public API so user code can apply them on records and beans.
 *
 * <ul>
 *   <li>{@link io.github.eschizoid.telescope.annotations.Focus} — applied to a record. The {@code
 *       :codegen} processor emits a sibling {@code <Record>Focus} class holding per-component optic
 *       constants, eliminating the per-field reflection cost of {@code .field(...)}.
 *   <li>{@link io.github.eschizoid.telescope.annotations.BeanFocus} — the POJO counterpart of
 *       {@code @Focus}. Drives generation of {@code <Bean>Focus} navigators for getter/setter beans
 *       (including Lombok {@code @Data} / {@code @Value} / {@code @Builder} classes via the {@code
 *       :lombok} processor).
 *   <li>{@link io.github.eschizoid.telescope.annotations.Bridge} — applied to a record to generate
 *       a reflection-free, compile-checked bridge to a sibling POJO; the runtime counterpart of
 *       {@link io.github.eschizoid.telescope.Telescope#fromBean}.
 * </ul>
 *
 * <p>The annotations themselves carry no runtime behavior — they are read by the {@code
 * AbstractProcessor}s shipped in the {@code telescope-codegen} and {@code telescope-lombok}
 * modules.
 */
package io.github.eschizoid.telescope.annotations;
