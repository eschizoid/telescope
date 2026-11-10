/**
 * Quarkus CDI extension for the telescope optics DSL.
 *
 * <p>Adds two CDI beans when the extension is on the classpath:
 *
 * <ul>
 *   <li>{@link io.github.telescopeorganization.telescope.quarkus.TelescopeMapperRegistry} — a typed
 *       registry indexing every {@link io.github.telescopeorganization.telescope.conversion.Mapper}
 *       bean by {@code (sourceClass, targetClass)}. Useful for polymorphic conversion in generic
 *       services.
 *   <li>{@link io.github.telescopeorganization.telescope.quarkus.TelescopeProducer} — the CDI
 *       producer that builds the registry from every injected {@code Mapper<?, ?>} bean.
 * </ul>
 *
 * <p>Configuration lives under the {@code telescope.*} namespace via {@link
 * io.github.telescopeorganization.telescope.quarkus.TelescopeConfig}. The Spring Boot starter
 * {@code telescope-spring-boot-starter} ships the same registry shape under {@code
 * io.github.telescopeorganization.telescope.spring}.
 */
package io.github.telescopeorganization.telescope.quarkus;
