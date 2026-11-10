/**
 * Quarkus 3 CDI extension module for telescope. Provides {@link
 * io.github.telescopeorganization.telescope.quarkus.TelescopeProducer} and the {@link
 * io.github.telescopeorganization.telescope.quarkus.TelescopeMapperRegistry} bean — a typed
 * registry indexing every {@link io.github.telescopeorganization.telescope.conversion.Mapper} bean
 * visible to ArC by {@code (sourceClass, targetClass)} pair.
 */
module io.github.telescopeorganization.telescope.quarkus {
  requires transitive io.github.telescopeorganization.telescope;
  requires transitive jakarta.cdi;
  requires io.quarkus.arc;
  requires transitive io.smallrye.config;

  exports io.github.telescopeorganization.telescope.quarkus;
}
