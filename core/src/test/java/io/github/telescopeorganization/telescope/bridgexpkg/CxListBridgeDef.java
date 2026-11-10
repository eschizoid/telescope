package io.github.telescopeorganization.telescope.bridgexpkg;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.bridgexpkg.bom.CxListTarget;
import io.github.telescopeorganization.telescope.bridgexpkg.dbm.CxListSource;

/**
 * Carrier-form lenient @Bridge over a GENERIC List<CxDoc> field whose element bridges cross-package
 * (dbm.CxDoc → bom.CxDoc) — the adopter's actual List<DocSubStatus> shape. The List helper calls
 * the element sub-bridge by simple name, so without the cross-package import this fails to compile.
 */
@Bridge(source = CxListSource.class, target = CxListTarget.class, lenient = true)
public class CxListBridgeDef {}
