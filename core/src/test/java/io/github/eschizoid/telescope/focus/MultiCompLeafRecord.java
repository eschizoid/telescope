package io.github.eschizoid.telescope.focus;

import io.github.eschizoid.telescope.annotations.Focus;

/**
 * Multi-component {@code @Focus} record used as a nullable nested intermediate on a write path. The
 * focused component ({@code cityName}) is what a write targets; the off-path {@code countryName}
 * (reference) and {@code zipCode} (primitive) are read off the previous record during the generated
 * canonical-constructor rebuild. When the intermediate is {@code null} those off-path reads must
 * not NPE — the reference defaults to {@code null} and the primitive to its JLS default ({@code
 * 0}).
 */
@Focus
record MultiCompLeafRecord(String cityName, String countryName, int zipCode) {}
