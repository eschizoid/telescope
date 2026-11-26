package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope.Accessor;
import java.util.Objects;

/**
 * The row naming the property through which each element of a collection points back at the owner
 * that holds it, built by {@link Mapping#backReference(Accessor)}. Without the row a mapper sets
 * only the one {@code @OneToMany(mappedBy)} names with {@code telescope-jpa}, and none on a plain
 * bean, whatever types its properties have. The row names it for a plain bean, or a
 * {@code @OneToMany} with no {@code mappedBy}, or pins it over {@code mappedBy}.
 *
 * <p>The row is keyed on the element class: it applies to every collection of that class the mapper
 * writes whose owner is of the accessor's type. A mapper refuses it, when it is built, where the
 * accessor's type is the owner class of no such collection, or where no collection the mapper
 * writes converts elements of that class.
 *
 * @param property the element's getter of the back-reference, a method reference
 * @param <E> the element class
 * @param <O> the owner class
 */
public record BackReference<E, O>(Accessor<E, O> property) implements MapStep {
  public BackReference {
    Objects.requireNonNull(property, "property");
  }
}
