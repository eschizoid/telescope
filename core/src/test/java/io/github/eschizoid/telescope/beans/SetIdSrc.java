package io.github.eschizoid.telescope.beans;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.Set;

/** Source for the SET concrete-subtype path: a Set source bridged to a TreeSet target. */
@Bridge(SetIdDst.class)
public record SetIdSrc(Set<String> tags) {}
