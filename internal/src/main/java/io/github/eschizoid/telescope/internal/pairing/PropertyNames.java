package io.github.eschizoid.telescope.internal.pairing;

/**
 * The single home of the JavaBeans property-name derivation used everywhere a getter-shaped name
 * becomes a property name: runtime bean discovery ({@code Beans}), accessor-reference normalization
 * (the public DSL), and both annotation processors. One rule, five call sites — this class exists
 * because the sites had drifted (only one of them required an uppercase character after the prefix,
 * so {@code getaway()} derived property {@code "away"} in one world and stayed {@code "getaway"} in
 * another).
 *
 * <p>The rule is the JavaBeans one: {@code getX} / {@code isX} strip only when the character after
 * the prefix is uppercase ({@code getaway} is not a getter), and decapitalization preserves a
 * leading acronym ({@code getURL} → {@code URL}, not {@code uRL}). Return-type conditions ({@code
 * get} must not return {@code void}, {@code is} must return a boolean) stay at the call sites —
 * they live in different worlds (reflection vs {@code javax.lang.model} mirrors); this class is
 * name logic only.
 */
public final class PropertyNames {

  private PropertyNames() {}

  /**
   * The property behind a {@code get}-prefixed name, or {@code null} when the name is not
   * getter-shaped: {@code getCity} → {@code city}, {@code getURL} → {@code URL}, {@code getaway} →
   * {@code null}.
   */
  public static String afterGet(final String name) {
    if (name == null || name.length() <= 3 || !name.startsWith("get")) return null;
    if (!Character.isUpperCase(name.charAt(3))) return null;
    return decapitalize(name.substring(3));
  }

  /**
   * The property behind a {@code set}-prefixed name, or {@code null} when the name is not
   * setter-shaped: {@code setCity} → {@code city}, {@code setup} → {@code null}, {@code settle} →
   * {@code null}. The uppercase-after-prefix rule is the same one {@link #afterGet} applies — a
   * method that merely starts with the letters {@code set} is not a property setter.
   */
  public static String afterSet(final String name) {
    if (name == null || name.length() <= 3 || !name.startsWith("set")) return null;
    if (!Character.isUpperCase(name.charAt(3))) return null;
    return decapitalize(name.substring(3));
  }

  /**
   * The property behind an {@code is}-prefixed name, or {@code null} when the name is not
   * getter-shaped: {@code isActive} → {@code active}, {@code isbn} → {@code null}.
   */
  public static String afterIs(final String name) {
    if (name == null || name.length() <= 2 || !name.startsWith("is")) return null;
    if (!Character.isUpperCase(name.charAt(2))) return null;
    return decapitalize(name.substring(2));
  }

  /**
   * Normalize an accessor name to its property name: the {@code getX} / {@code isX} strip when the
   * name is getter-shaped, otherwise the name unchanged (record component accessors pass through).
   * {@code null} passes through — callers that read a field name off a row shape whose
   * nested-telescope variants return {@code null} by design rely on that.
   */
  public static String property(final String name) {
    if (name == null) return name;
    final var get = afterGet(name);
    if (get != null) return get;
    final var is = afterIs(name);
    return is != null ? is : name;
  }

  /**
   * JavaBeans {@code Introspector.decapitalize}: a name whose first two characters are both
   * uppercase is left unchanged ({@code URL} stays {@code URL}); otherwise the first character is
   * lowercased.
   */
  public static String decapitalize(final String s) {
    if (s == null || s.isEmpty()) return s;
    if (s.length() > 1 && Character.isUpperCase(s.charAt(0)) && Character.isUpperCase(s.charAt(1))) return s;
    return Character.toLowerCase(s.charAt(0)) + s.substring(1);
  }
}
