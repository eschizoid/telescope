package io.github.eschizoid.telescope.demo.spring.substrate;

/** A till purchase and how it was paid. */
public record Purchase(String id, Tender tender) {}
