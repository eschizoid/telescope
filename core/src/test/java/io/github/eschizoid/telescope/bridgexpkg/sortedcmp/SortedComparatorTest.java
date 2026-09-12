package io.github.eschizoid.telescope.bridgexpkg.sortedcmp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.eschizoid.telescope.Telescope;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A sorted container orders by its comparator, so a rebuild that drops one does not merely reorder.
 * Where the keys implement no natural ordering, the rebuilt container throws on its first insert
 * while the source it was built from worked — so the comparator is part of the value, not a detail
 * of how it was constructed.
 *
 * <p>{@link SortKey} deliberately does not implement {@code Comparable}. That is what makes this
 * test able to fail: with a comparable key the dropped comparator only changes the order, which
 * these assertions on a single entry would not see.
 */
class SortedComparatorTest {

  private static CmpSrc source() {
    final var byKey = new TreeMap<SortKey, CmpA>(Comparator.comparing(SortKey::id));
    byKey.put(new SortKey("b"), new CmpA("second"));
    byKey.put(new SortKey("a"), new CmpA("first"));
    return new CmpSrc(byKey);
  }

  @Test
  @DisplayName("a generated bridge carries the source's comparator into the rebuilt container")
  void codegenPreservesTheComparator() {
    final var converted = CmpSrcBridge.forward(source());

    assertNotNull(converted.byKey().comparator(), "without it, a non-comparable key cannot be inserted at all");
    assertEquals(List.of("a", "b"), converted.byKey().keySet().stream().map(SortKey::id).toList());
  }

  @Test
  @DisplayName("a field declared as the SortedMap interface resolves on both paths, not just one")
  void declaredInterfaceResolvesOnBothPaths() {
    // The allocation tables are separate — one in the processor, one in the runtime lift — and a
    // family added to only one of them compiles under @Bridge and throws under mapper(...), which
    // is the swap the two paths exist to make interchangeable. A fixture declaring a concrete
    // TreeMap cannot see that: both tables already knew it.
    final var byKey = new TreeMap<SortKey, CmpA>(Comparator.comparing(SortKey::id));
    byKey.put(new SortKey("a"), new CmpA("first"));
    final var src = new IfaceSrc(byKey);

    final var codegen = IfaceSrcBridge.forward(src);
    final var runtime = Telescope.mapper(IfaceSrc.class, IfaceDst.class).forward(src);

    assertEquals(codegen.byKey().getClass(), runtime.byKey().getClass());
    assertNotNull(codegen.byKey().comparator());
    assertNotNull(runtime.byKey().comparator());
  }

  @Test
  @DisplayName("the reflective mapper does the same, so the two paths agree on ordering")
  void runtimeAgreesWithCodegen() {
    final var src = source();

    final var codegen = CmpSrcBridge.forward(src);
    final var runtime = Telescope.mapper(CmpSrc.class, CmpDst.class).forward(src);

    assertNotNull(runtime.byKey().comparator());
    assertEquals(
      codegen.byKey().keySet().stream().map(SortKey::id).toList(),
      runtime.byKey().keySet().stream().map(SortKey::id).toList()
    );
  }
}
