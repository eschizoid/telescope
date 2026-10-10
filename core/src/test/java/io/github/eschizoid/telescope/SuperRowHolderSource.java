package io.github.eschizoid.telescope;

import java.util.List;

/** {@link SuperRowHolderSub}'s properties as a record, plus a {@code label} rows can route. */
public record SuperRowHolderSource(int n, SuperRowSource child, List<String> tags, String label) {}
