/**
 * The contracts that container-generated beans implement: a {@link TelescopeProjection} maps one
 * model to another through a core mapper, and a {@link TelescopeTransformation} normalizes a value
 * at a typed path before mapping. Nothing here depends on a container; the beans generated for the
 * Spring starter and for the Quarkus starter implement the same interfaces.
 */
package io.github.eschizoid.telescope.inject;
