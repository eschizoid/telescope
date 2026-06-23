package io.github.eschizoid.telescope.beans;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.List;

/**
 * Codegen fixture with a {@code List<OptElem>} bridged to {@code List<OptElemBO>}. Exercises the
 * generated container helper's null-element handling: a null element inside the list must bridge to
 * null rather than NPE on {@code subBridge.forward(null)}, matching the runtime element Iso.
 */
@Bridge(ElemListDst.class)
public record ElemListSrc(List<OptElem> items) {}
