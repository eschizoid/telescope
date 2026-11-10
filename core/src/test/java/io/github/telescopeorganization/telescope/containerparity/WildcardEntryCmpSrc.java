package io.github.telescopeorganization.telescope.containerparity;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import java.util.SortedMap;

/** Paired against a target whose comparator parameter orders entries behind a wildcard. */
@Bridge(WildcardEntryCmpTgt.class)
public record WildcardEntryCmpSrc(SortedMap<String, SortedParityA> items) {}
