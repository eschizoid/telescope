/**
 * Quarkus 3 CDI extension module for telescope. Provides {@link
 * io.github.eschizoid.telescope.quarkus.TelescopeProducer} and the {@link
 * io.github.eschizoid.telescope.quarkus.TelescopeMapperRegistry} bean — a typed registry indexing
 * every {@link io.github.eschizoid.telescope.conversion.Mapper} bean visible to ArC by {@code
 * (sourceClass, targetClass)} pair.
 */
module io.github.eschizoid.telescope.quarkus {
  requires transitive io.github.eschizoid.telescope;
  requires transitive jakarta.cdi;
  requires io.quarkus.arc;
  requires transitive io.smallrye.config;

  exports io.github.eschizoid.telescope.quarkus;
}
