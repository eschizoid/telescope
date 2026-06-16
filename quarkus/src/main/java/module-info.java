/**
 * Quarkus 3 CDI extension module for telescope. Provides {@link
 * io.github.eschizoid.telescope.quarkus.TelescopeProducer} and the {@link
 * io.github.eschizoid.telescope.quarkus.TelescopeMapperRegistry} bean — a typed registry indexing
 * every {@link io.github.eschizoid.telescope.conversion.Mapper} bean visible to ArC by {@code
 * (sourceClass, targetClass)} pair.
 *
 * <p>Two transitive deps ({@code quarkus-arc}, {@code smallrye-config}) ship neither {@code
 * module-info.class} nor {@code Automatic-Module-Name} in their manifests. Rather than patching
 * them via the {@code org.gradlex.extra-java-module-info} plugin, this module reads them via {@code
 * --add-reads io.github.eschizoid.telescope.quarkus=ALL-UNNAMED} (see {@code build.gradle.kts}).
 * Strict downstream JPMS consumers still get a real {@code requires
 * io.github.eschizoid.telescope.quarkus;} surface; the unnamed-module read is compile-time only.
 */
module io.github.eschizoid.telescope.quarkus {
  requires transitive io.github.eschizoid.telescope;
  requires transitive jakarta.cdi;

  exports io.github.eschizoid.telescope.quarkus;
}
