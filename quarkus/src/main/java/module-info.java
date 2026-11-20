/**
 * Quarkus 3 extension module for telescope. Provides {@link
 * io.github.eschizoid.telescope.quarkus.TelescopeProducer} and the {@link
 * io.github.eschizoid.telescope.quarkus.TelescopeMapperRegistry} bean — a typed registry indexing
 * every {@link io.github.eschizoid.telescope.conversion.Mapper} bean visible to ArC by {@code
 * (sourceClass, targetClass)} pair — and the mapped request and response bodies of Quarkus REST in
 * {@code io.github.eschizoid.telescope.quarkus.rest}, whose build steps live in the {@code
 * telescope-quarkus-deployment} artifact.
 */
module io.github.eschizoid.telescope.quarkus {
  requires transitive io.github.eschizoid.telescope;
  requires transitive jakarta.cdi;
  requires io.quarkus.arc;
  requires transitive io.smallrye.config;
  requires static io.quarkus.core;
  requires static transitive jakarta.ws.rs;
  requires static jakarta.validation;

  exports io.github.eschizoid.telescope.quarkus;
  exports io.github.eschizoid.telescope.quarkus.rest;
}
