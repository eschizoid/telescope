package io.github.telescopeorganization.telescope.beans;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import java.util.List;

/**
 * Source for the identity-element concrete-container path: List<String> bridged to
 * LinkedList<String>.
 */
@Bridge(IdListDst.class)
public record IdListSrc(List<String> tags) {}
