package io.github.telescopeorganization.telescope.beans;

import java.util.LinkedList;

/**
 * Target with a concrete LinkedList field whose element needs an OptElem -> OptElemBO sub-bridge.
 */
public record ConcreteListDst(LinkedList<OptElemBO> items) {}
