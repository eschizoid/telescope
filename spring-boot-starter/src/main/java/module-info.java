/**
 * Spring Boot 4 autoconfig module for telescope. Provides {@link
 * io.github.eschizoid.telescope.spring.TelescopeAutoConfiguration} and the {@link
 * io.github.eschizoid.telescope.spring.TelescopeMapperRegistry} bean — a typed registry indexing
 * every {@link io.github.eschizoid.telescope.conversion.Mapper} bean in the {@code ApplicationContext} by
 * {@code (sourceClass, targetClass)} pair.
 */
module io.github.eschizoid.telescope.spring {
  requires transitive io.github.eschizoid.telescope;
  requires transitive spring.boot;
  requires transitive spring.boot.autoconfigure;
  requires spring.context;
  requires spring.beans;

  exports io.github.eschizoid.telescope.spring;
}
