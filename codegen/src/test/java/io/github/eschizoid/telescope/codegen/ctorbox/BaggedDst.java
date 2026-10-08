package io.github.eschizoid.telescope.codegen.ctorbox;

/**
 * A target holding the list whose constructor only this package can call.
 *
 * @param items the list
 */
public record BaggedDst(PackageBag<String> items) {}
