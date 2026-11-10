package io.github.telescopeorganization.telescope.beans;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import java.util.Set;

/** Source for the SET concrete-subtype path: a Set source bridged to a TreeSet target. */
@Bridge(SetIdDst.class)
public record SetIdSrc(Set<String> tags) {}
