package io.github.eschizoid.telescope.bridgecarrier;

/**
 * Target of the carrier {@code @Bridge}; {@code renamedName} is fed from the source's {@code
 * legacyName}.
 */
public record CarrierTgt(String renamedName) {}
