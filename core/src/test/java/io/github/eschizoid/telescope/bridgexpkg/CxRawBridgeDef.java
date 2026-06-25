package io.github.eschizoid.telescope.bridgexpkg;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.bridgexpkg.bom.CxRawTarget;
import io.github.eschizoid.telescope.bridgexpkg.dbm.CxRawSource;

/**
 * Carrier-form lenient @Bridge over a raw-Collection-subtype field (CxDocList) whose element
 * bridges cross-package (dbm.CxDoc → bom.CxDoc). The raw-container helper calls the sub-bridge by
 * simple name, so without the cross-package import the generated parent bridge fails to compile.
 */
@Bridge(source = CxRawSource.class, target = CxRawTarget.class, lenient = true)
public class CxRawBridgeDef {}
