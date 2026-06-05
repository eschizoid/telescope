/**
 * Lombok integration for the telescope code generator. Hosts a single annotation processor that
 * recognises Lombok-shaped POJOs ({@code @Data}, {@code @Value}, {@code @Builder}) and emits the
 * same {@code <Bean>Focus} navigators as the core {@link
 * io.github.eschizoid.telescope.codegen.BeanFocusProcessor}.
 *
 * <ul>
 *   <li>{@link io.github.eschizoid.telescope.codegen.lombok.LombokFocusProcessor} — extends {@link
 *       io.github.eschizoid.telescope.codegen.AbstractTelescopeProcessor}, discovers the
 *       getters/setters that Lombok will (or has) generated, and emits a {@code <Bean>Focus} class
 *       whose constants navigate via those accessors.
 * </ul>
 *
 * <p>The processor is registered through {@code
 * META-INF/services/javax.annotation.processing.Processor} and must run on the same {@code javac}
 * round as Lombok itself; consumer projects only need this artifact on the annotation-processor
 * path.
 */
package io.github.eschizoid.telescope.codegen.lombok;
