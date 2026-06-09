package io.github.eschizoid.telescope.demo.spring.bughunt.writebean;

/**
 * Record-side leaf for the {@code writeBean(Class, STRATEGY)} per-class override slice. Mirrors
 * {@link ShippingNote} field-for-field so the deep-mapping engine auto-recurses into the {@code
 * (NoteIn, ShippingNote)} pair and constructs each {@code ShippingNote} via its all-args
 * constructor (the only path it has).
 */
public record NoteIn(String code, String text) {}
