package io.github.eschizoid.telescope.sealedroots;

/** A sealed root that reaches itself through a case. */
public sealed interface SuExpr permits SuLit, SuAdd {}
