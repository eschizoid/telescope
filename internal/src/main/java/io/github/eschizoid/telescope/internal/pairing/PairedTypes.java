package io.github.eschizoid.telescope.internal.pairing;

/**
 * The types one pairing reads a source property and the target property it pairs with as, and the
 * types each has as a member of its own class, as {@link PairingRules#pairedTypes} decides them.
 * The two pairs differ only where the pairing reads as declared.
 *
 * @param source the type the pairing reads the source property as
 * @param target the type the pairing reads the target property as
 * @param sourceMember the source property's type as a member of the source class, with each
 *     variable the class passes through kept as that variable
 * @param targetMember the target property's type as a member of the target class, read the same way
 * @param handedAcross whether the pairing reads as declared and both sides declare one type, so a
 *     value is handed across unchanged and only a class one side fixes a type variable to can
 *     refuse it ({@link PairingRules#fixedFit})
 * @param <T> the world's type handle
 */
public record PairedTypes<T>(T source, T target, T sourceMember, T targetMember, boolean handedAcross) {}
