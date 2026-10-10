package io.github.eschizoid.telescope.frommapparity;

import io.github.eschizoid.telescope.annotations.FromMap;

/**
 * A nested type with a generated binder and a required key. The annotation is source-retained, so
 * only the generated binder knows {@code city} is required: a runtime mapper that rebuilt this type
 * itself rather than through that binder would accept a map the binder refuses.
 */
@FromMap(required = { "city" })
public record BackfillLeaf(String city, int zip) {}
