package io.github.eschizoid.telescope.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link Record} for codegen by the {@code telescope-codegen} annotation processor. For
 * each annotated record, the processor emits a sibling class {@code <RecordName>Telescope<R>} — a
 * fluent navigator with one method per record component, plus the full Telescope op-surface
 * forwarded so any hop reads / updates / traverses without reaching for {@code .get()}.
 *
 * <p>The generated navigator is the reflection-free, compile-checked replacement for the runtime
 * {@code Telescope.of(Record.class).field(Record::component)} path: field names are resolved by the
 * processor (a typo is a compile error), navigation never touches reflection, and the hot path
 * inlines through generated method references. Container components ({@code List<E>}, {@code
 * Set<E>}, {@code Map<K,V>}, {@code Optional<E>}, {@code Iterable<E>}) get their own typed {@code
 * <X><Cap>Step<R>} sub-navigator with the right {@code .each() / .eachValue() / .whenPresent()}
 * exposed; if the element type is itself navigable (carries {@code @Focus} / {@code @BeanFocus} / a
 * Lombok bean annotation), the step's method returns the sub-element's {@code Telescope<R>} so
 * navigation continues fluently.
 *
 * <p>Records only, and only top-level records — the generated top-level {@code *Path} class cannot
 * reference a nested record's canonical constructor, so a nested or non-record target is a compile
 * error.
 *
 * <p>Without the processor on the annotation-processor path this annotation is inert — annotated
 * records compile fine and the reflection-based {@code .field(Record::component)} path still works.
 * Add the processor to opt into compile-time-checked, allocation-free field navigation:
 *
 * <pre>{@code
 * // Gradle:
 * annotationProcessor("io.github.eschizoid:telescope-codegen:0.3.0")
 *
 * // Source:
 * @Focus
 * record User(String name, int age, Address address) {}
 *
 * @Focus
 * record Address(String city) {}
 *
 * // Generated alongside (UserPath.java, AddressPath.java):
 * // public final class UserTelescope<R> {
 * //   public static UserPath<User> start() { ... }
 * //   public Telescope<R, String> name() { ... }
 * //   public Telescope<R, Integer> age() { ... }
 * //   public AddressTelescope<R> address() { ... }
 * //   // + read / find / set / update / toList / ... forwarders
 * // }
 *
 * // Usage — no reflection, field name checked at compile time:
 * final var alice = new User("alice", 30, new Address("NYC"));
 * final var loud  = UserPath.start().name().update(alice, String::toUpperCase);
 *
 * // Drill into a nested @Focus record fluently — no .then(...) plumbing:
 * final var moved = UserPath.start().address().city().set(alice, "LA");
 * }</pre>
 *
 * @see io.github.eschizoid.telescope.Telescope#lens(java.util.function.Function,
 *     java.util.function.BiFunction)
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface Focus {}
