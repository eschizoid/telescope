package io.github.eschizoid.telescope.demo.spring.bughunt.writebean;

import java.util.List;

/**
 * Source-side root record for the {@code writeBean(Class, STRATEGY)} per-class override slice.
 * Carries a few same-name scalar fields whose target-side counterparts live on a setter-driven JPA
 * sibling, plus a {@code List<NoteIn>} that recursively auto-lifts onto {@code List<ShippingNote>}
 * on the target side.
 */
public record BoxedOrder(Long id, String label, List<NoteIn> notes) {}
