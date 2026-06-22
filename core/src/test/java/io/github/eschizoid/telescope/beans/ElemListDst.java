package io.github.eschizoid.telescope.beans;

import java.util.List;

/** Element-list bridge target for {@link ElemListSrc}. */
public record ElemListDst(List<OptElemBO> items) {}
