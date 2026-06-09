/**
 * Spring Boot 4 autoconfiguration for telescope. Drop the {@code telescope-spring-boot-starter}
 * dependency on your classpath; the {@link
 * io.github.eschizoid.telescope.spring.TelescopeAutoConfiguration} activates automatically and
 * contributes one bean by default — {@link
 * io.github.eschizoid.telescope.spring.TelescopeMapperRegistry} — a typed registry indexing every
 * {@link io.github.eschizoid.telescope.conversion.Mapper} bean in the {@code ApplicationContext} by
 * {@code (sourceClass, targetClass)} pair.
 *
 * <p>Configuration properties live under the {@code telescope.*} namespace; see {@link
 * io.github.eschizoid.telescope.spring.TelescopeProperties}.
 */
package io.github.eschizoid.telescope.spring;
