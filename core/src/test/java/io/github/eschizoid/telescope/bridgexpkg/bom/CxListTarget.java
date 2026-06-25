package io.github.eschizoid.telescope.bridgexpkg.bom;

import java.util.List;

/**
 * BO-side parent: generic List of the same-simple-name BO doc (mirrors the adopter's
 * List<DocSubStatus>).
 */
public record CxListTarget(List<CxDoc> docs, String name) {}
