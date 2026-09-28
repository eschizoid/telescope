/**
 * The contracts that container-generated beans implement: a {@link TelescopeProjection} maps one
 * model to another through a core mapper, and a {@link TelescopeTransformation} normalizes a value
 * at a typed path before mapping. Nothing here depends on a container; the Spring starter's
 * generated components implement these interfaces, and another container's could implement the same
 * ones.
 */
package io.github.eschizoid.telescope.inject;
