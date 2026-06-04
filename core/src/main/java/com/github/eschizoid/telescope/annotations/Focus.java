package com.github.eschizoid.telescope.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link Record} for codegen by the {@code telescope-codegen} annotation processor. For
 * each annotated record, the processor emits a sibling class {@code <RecordName>Focus} with {@code
 * public static final Telescope<Record, FieldType>} constants per record component, each built via
 * {@link com.github.eschizoid.telescope.Telescope#lens(java.util.function.Function,
 * java.util.function.BiFunction)}.
 *
 * <p>The generated constants are the reflection-free, compile-checked replacement for the runtime
 * {@code Telescope.of(Record.class).field(Record::component)} path: the field-name reference is
 * resolved by the processor (a typo is a compile error), and navigation never touches reflection.
 * The constants are plain {@link com.github.eschizoid.telescope.Telescope} values, so they compose
 * with {@code .field(...)}, {@code .each(...)}, {@code .then(...)} like any other telescope.
 *
 * <p>Records only, and only top-level records — the generated top-level {@code *Focus} class cannot
 * reference a nested record's canonical constructor, so a nested or non-record target is a compile
 * error.
 *
 * <p>Without the processor on the annotation-processor path this annotation is inert — annotated
 * records compile fine and the reflection-based {@code .field(Record::component)} path still works.
 * Add the processor to opt into compile-time-checked, allocation-free field navigation:
 *
 * <pre>{@code
 * // Gradle:
 * annotationProcessor("io.github.eschizoid:telescope-codegen:0.1.0")
 *
 * // Source:
 * @Focus
 * record User(String name, int age, Address address) {}
 *
 * // Generated alongside (UserFocus.java):
 * // public final class UserFocus {
 * //   public static final Telescope<User, String> name = Telescope.lens(...);
 * //   public static final Telescope<User, Integer> age = Telescope.lens(...);
 * //   public static final Telescope<User, Address> address = Telescope.lens(...);
 * // }
 *
 * // Usage — no reflection, field name checked at compile time:
 * final var alice = new User("alice", 30, new Address("NYC"));
 * final var loud  = UserFocus.name.update(alice, String::toUpperCase); // User[name=ALICE, ...]
 *
 * // Composes like any Telescope value — drill into a nested @Focus record:
 * final var moved = UserFocus.address.then(AddressFocus.city).set(alice, "LA");
 * }</pre>
 *
 * @see com.github.eschizoid.telescope.Telescope#lens(java.util.function.Function,
 *     java.util.function.BiFunction)
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface Focus {}
