package io.github.telescopeorganization.telescope.frommapparity;

import io.github.telescopeorganization.telescope.annotations.FromMap;

/**
 * Two required components, one of them primitive, and one that defaults. The required names are
 * listed out of component order, so a refusal that followed the listing would name them backwards.
 */
@FromMap(required = { "count", "id" })
public record RequiredRow(String id, int count, String note) {}
