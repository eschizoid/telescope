package io.github.eschizoid.telescope.sealedroots;

/** A case with no {@code @Bridge}, whose only bridge is the one {@link ShBox} derives for it. */
public record ShC(int x) implements ShShape {}
