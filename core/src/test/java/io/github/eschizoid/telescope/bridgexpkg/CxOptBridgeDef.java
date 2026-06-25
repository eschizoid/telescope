package io.github.eschizoid.telescope.bridgexpkg;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.bridgexpkg.bom.CxOptTarget;
import io.github.eschizoid.telescope.bridgexpkg.dbm.CxOptSource;

/** Carrier-form lenient @Bridge over an Optional<CxDoc> whose element bridges cross-package. */
@Bridge(source = CxOptSource.class, target = CxOptTarget.class, lenient = true)
public class CxOptBridgeDef {}
