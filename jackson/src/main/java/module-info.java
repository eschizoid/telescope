/**
 * telescope-jackson: JSON at the edge of a telescope mapping, on Jackson 3.
 *
 * <ul>
 *   <li>{@link io.github.eschizoid.telescope.jackson.TelescopeJson#fromJson TelescopeJson.fromJson}
 *       builds a {@link io.github.eschizoid.telescope.jackson.JsonBinder JsonBinder}, which reads
 *       JSON straight into a record or a bean over {@code jackson-core}'s streaming parser, with no
 *       intermediate tree or map.
 *   <li>{@link io.github.eschizoid.telescope.jackson.TelescopeModule TelescopeModule} is a databind
 *       module that reads and writes a domain type through the wire type a {@code Mapper} converts
 *       it from, for the mappers it is given and no others.
 * </ul>
 *
 * <p>The binder needs {@code jackson-core} alone. Databind is a static requirement: an application
 * that registers a {@code TelescopeModule} has it, and one that only binds JSON can leave it off
 * the module path. The optic lattice stays private to {@code telescope-core}; this module reads the
 * reflection helpers and the shared conversion rules through qualified exports.
 */
module io.github.eschizoid.telescope.jackson {
  requires transitive io.github.eschizoid.telescope;
  requires transitive tools.jackson.core;
  requires static transitive tools.jackson.databind;
  requires static transitive com.fasterxml.jackson.annotation;

  exports io.github.eschizoid.telescope.jackson;

  // A target's generated @FromMap binder says which of its keys are required, and the JSON binder
  // holds a payload to the same keys.
  uses io.github.eschizoid.telescope.conversion.FromMapProvider;
}
