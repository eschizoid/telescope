package io.github.eschizoid.telescope;

import java.util.List;

/** {@link SuperRowHolderSub}'s properties as a record. */
public record SuperRowHolderSource(int n, SuperRowSource child, List<String> tags) {}
