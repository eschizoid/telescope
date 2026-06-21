package io.github.eschizoid.telescope.examples;

import io.github.eschizoid.telescope.effects.Validated;
import java.util.List;

/**
 * Accumulating validated mapping — the conversion story MapStruct can't tell.
 *
 * <p>Mapping a raw, stringly-typed input (a form post, a CSV row, a JSON body) into a typed domain
 * object usually means validating several fields at once. MapStruct maps field-by-field and has no
 * built-in way to <em>collect every failure</em>: you throw on the first bad field or hand-roll an
 * {@code @AfterMapping} accumulator. Telescope ships {@link Validated} as a first-class effect, so
 * "construct the target only if every field validates, and report all failures in one pass" is a
 * library primitive:
 *
 * <ul>
 *   <li>{@link Validated#combine(Validated, Validated, java.util.function.BiFunction) combine} runs
 *       both checks and accumulates both error lists — the applicative behaviour, distinct from
 *       {@code Either} short-circuiting on the first failure.
 *   <li>{@link Validated#combineAll(List) combineAll} folds a batch of per-row results into one
 *       {@code Validated<E, List<A>>}, surfacing every error across every row at once.
 * </ul>
 */
final class ValidatedMappingDemo {

  private ValidatedMappingDemo() {}

  static void main() {
    run();
  }

  /** Raw input — every field is an unvalidated {@code String}. */
  record SignupForm(String email, String ageText) {}

  /** Typed domain target — built only once every field has passed. */
  record Account(String email, int age) {}

  static void run() {
    singleFormAccumulatesEveryFieldError();
    batchImportAccumulatesEveryRowError();
  }

  // One form, two field validators. A bad email AND a bad age surface together — not just the
  // first.
  // MapStruct maps each field independently; reporting both failures at once is on you to wire.
  private static void singleFormAccumulatesEveryFieldError() {
    final var valid = mapForm(new SignupForm("ada@x.io", "37"));
    System.out.println("[combine] both fields valid : " + valid);

    final var broken = mapForm(new SignupForm("not-an-email", "200"));
    System.out.println("[combine] every error kept  : " + errorsOf(broken));
  }

  // A batch of rows. combineAll keeps every error from every offending row in a single Invalid —
  // the "import 1000 records, tell me everything wrong with the file" case.
  private static void batchImportAccumulatesEveryRowError() {
    final var rows = List.of(
      new SignupForm("ada@x.io", "37"),
      new SignupForm("bad", "x"),
      new SignupForm("grace@x.io", "200")
    );

    final Validated<String, List<Account>> batch = Validated.combineAll(
      rows.stream().map(ValidatedMappingDemo::mapForm).toList()
    );

    System.out.println("[combineAll] valid?         : " + batch.isValid());
    System.out.println("[combineAll] every row error: " + errorsOf(batch));
  }

  // The mapping: validate each field, accumulate across both, build Account only if both pass.
  private static Validated<String, Account> mapForm(final SignupForm form) {
    return Validated.combine(validateEmail(form.email()), validateAge(form.ageText()), Account::new);
  }

  private static Validated<String, String> validateEmail(final String raw) {
    return raw.contains("@") ? Validated.valid(raw) : Validated.invalid("email: missing '@' in '" + raw + "'");
  }

  private static Validated<String, Integer> validateAge(final String raw) {
    final int age;
    try {
      age = Integer.parseInt(raw);
    } catch (final NumberFormatException e) {
      return Validated.invalid("age: not a number: '" + raw + "'");
    }
    return age >= 18 && age <= 120 ? Validated.valid(age) : Validated.invalid("age: out of range: " + age);
  }

  private static List<String> errorsOf(final Validated<String, ?> v) {
    return v instanceof Validated.Invalid<String, ?> invalid ? invalid.errors() : List.of();
  }
}
