package io.github.telescopeorganization.telescope.beans;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import java.util.LinkedList;

/** Source declared as a concrete LinkedList, so the BACKWARD rebuild must allocate a LinkedList. */
@Bridge(BwdConcreteDst.class)
public record BwdConcreteSrc(LinkedList<String> tags) {}
