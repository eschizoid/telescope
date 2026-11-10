package io.github.telescopeorganization.telescope.bridgexpkg;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.bridgexpkg.bom.CxOptTarget;
import io.github.telescopeorganization.telescope.bridgexpkg.dbm.CxOptSource;

/** Carrier-form lenient @Bridge over an Optional<CxDoc> whose element bridges cross-package. */
@Bridge(source = CxOptSource.class, target = CxOptTarget.class, lenient = true)
public class CxOptBridgeDef {}
