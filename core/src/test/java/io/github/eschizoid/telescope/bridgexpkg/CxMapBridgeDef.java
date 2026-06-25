package io.github.eschizoid.telescope.bridgexpkg;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.bridgexpkg.bom.CxMapTarget;
import io.github.eschizoid.telescope.bridgexpkg.dbm.CxMapSource;

/** Carrier-form lenient @Bridge over a Map<String, CxDoc> whose value bridges cross-package. */
@Bridge(source = CxMapSource.class, target = CxMapTarget.class, lenient = true)
public class CxMapBridgeDef {}
