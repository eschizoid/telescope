package io.github.eschizoid.telescope.bridgexpkg;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.bridgexpkg.bom.CxTarget;
import io.github.eschizoid.telescope.bridgexpkg.dbm.CxSource;

/**
 * Carrier-form lenient @Bridge over a DB→BO split (plain records, no Lombok). The nested {@code
 * CxDoc} fields share a simple name across the {@code dbm}/{@code bom} packages, so the generated
 * sub-bridge {@code CxDocToCxDocBridge} lives in {@code dbm} — a different package than this
 * carrier bridge. The parent bridge must import it, not reference a bare simple name it can't
 * resolve.
 */
@Bridge(source = CxSource.class, target = CxTarget.class, lenient = true)
public class CxBridgeDef {}
