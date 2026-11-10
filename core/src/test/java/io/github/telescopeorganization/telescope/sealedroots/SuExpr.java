package io.github.telescopeorganization.telescope.sealedroots;

/** A sealed root that reaches itself through a case. */
public sealed interface SuExpr permits SuLit, SuAdd {}
