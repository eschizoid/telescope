package io.github.telescopeorganization.telescope.beans;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import java.util.List;

/** Source for the concrete-container parity test: a List source bridged to a LinkedList target. */
@Bridge(ConcreteListDst.class)
public record ConcreteListSrc(List<OptElem> items) {}
