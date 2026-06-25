package io.github.eschizoid.telescope.bridgexpkg;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.eschizoid.telescope.bridgexpkg.dbm.CxDoc;
import io.github.eschizoid.telescope.bridgexpkg.dbm.CxSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bug 21: a carrier {@code @Bridge} whose nested field is bridged to a same-simple-name type in a
 * different package generates a sub-bridge ({@code CxDocToCxDocBridge}) in the nested source's
 * package. The parent bridge referenced it by bare simple name and never imported it, so the whole
 * generated bridge failed to compile ("cannot find symbol"). If THIS test compiles and round-trips,
 * the cross-package sub-bridge import is emitted. Plain records — no Lombok — proving the bug is in
 * the import logic, not round-deferred emission.
 */
class CrossPackageSubBridgeTest {

  @Test
  @DisplayName("a cross-package, same-simple-name nested sub-bridge is imported and bridges end-to-end")
  void crossPackageNestedSubBridgeResolves() {
    final var src = new CxSource(new CxDoc("d-1"), "platform");

    final var tgt = CxBridgeDefBridge.BRIDGE.read(src);

    assertEquals("d-1", tgt.doc().docId(), "nested DB-side doc bridges into the BO-side doc across packages");
    assertEquals("platform", tgt.name());
  }
}
