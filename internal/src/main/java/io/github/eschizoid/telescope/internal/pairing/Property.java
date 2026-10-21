package io.github.eschizoid.telescope.internal.pairing;

/**
 * One property of a record or bean class, as {@link PropertySystem#properties} lists it: a record
 * component, or a bean property read through a public getter.
 *
 * @param name the property's name: a component's own name, a getter's with {@code get} or {@code
 *     is} stripped
 * @param member the property's type as a member of the class, with every type variable the class
 *     declares kept as that variable, so a type a generic superclass leaves to a variable reads as
 *     the type the class gives it
 * @param declared the property's type as its accessor declares it
 * @param <T> the world's type handle
 */
public record Property<T>(String name, T member, T declared) {}
