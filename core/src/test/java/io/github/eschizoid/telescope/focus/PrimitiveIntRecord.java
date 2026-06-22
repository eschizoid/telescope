package io.github.eschizoid.telescope.focus;

import io.github.eschizoid.telescope.annotations.Focus;

/**
 * {@code @Focus} record with a primitive {@code int} component — the record sibling of {@link
 * PrimitiveIntTarget}. Pairs with a nullable boxed-{@code Integer} source to exercise the generated
 * record holder's {@code construct(Function)} null-guard: a null boxed value bound to a primitive
 * component must take the JLS default rather than NPE-ing on the canonical-ctor unbox.
 */
@Focus
public record PrimitiveIntRecord(int attemptCount) {}
