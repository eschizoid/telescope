package io.github.telescopeorganization.telescope.bridgexpkg;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.bridgexpkg.bom.CxMapTarget;
import io.github.telescopeorganization.telescope.bridgexpkg.dbm.CxMapSource;

/** Carrier-form lenient @Bridge over a Map<String, CxDoc> whose value bridges cross-package. */
@Bridge(source = CxMapSource.class, target = CxMapTarget.class, lenient = true)
public class CxMapBridgeDef {}
