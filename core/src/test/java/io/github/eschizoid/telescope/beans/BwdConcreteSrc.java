package io.github.eschizoid.telescope.beans;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.LinkedList;

/** Source declared as a concrete LinkedList, so the BACKWARD rebuild must allocate a LinkedList. */
@Bridge(BwdConcreteDst.class)
public record BwdConcreteSrc(LinkedList<String> tags) {}
