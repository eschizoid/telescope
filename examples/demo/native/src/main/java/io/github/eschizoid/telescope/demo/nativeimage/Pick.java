package io.github.eschizoid.telescope.demo.nativeimage;

import io.github.eschizoid.telescope.annotations.Focus;

/** One item to take from one shelf. */
@Focus
public record Pick(String sku, int quantity, String bin) {}
