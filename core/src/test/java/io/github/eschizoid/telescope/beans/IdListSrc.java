package io.github.eschizoid.telescope.beans;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.List;

/**
 * Source for the identity-element concrete-container path: List<String> bridged to
 * LinkedList<String>.
 */
@Bridge(IdListDst.class)
public record IdListSrc(List<String> tags) {}
