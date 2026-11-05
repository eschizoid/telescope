package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.Serializable;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What a {@code compute} row's reference is read as on the runtime path: the form taking the source
 * a reference given no argument did not reach, and the class a function of the source takes, with
 * the name a refusal gives the function.
 */
class ComputeReferenceDecodeTest {

  interface Fresh<X> extends Supplier<X>, Serializable {}

  interface FromSource<A, X> extends Function<A, X>, Serializable {}

  interface IntFrom extends IntFunction<String>, Serializable {}

  interface Named {
    String first();
  }

  record Person(String first) implements Named {
    String shout() {
      return first.toUpperCase();
    }
  }

  record Plain(String first) {}

  static final class Both {

    static String label() {
      return "plain";
    }

    static String label(final Person person) {
      return person.first();
    }

    String tag() {
      return "tag";
    }

    String tag(final Person person) {
      return person.first();
    }
  }

  static final class Several {

    static String label() {
      return "plain";
    }

    static String label(final Person person) {
      return "person";
    }

    static String label(final Named named) {
      return "named";
    }

    static String label(final Record record) {
      return "record";
    }
  }

  static class Base {

    static String label(final Person person) {
      return person.first();
    }
  }

  static final class Derived extends Base {

    static String label() {
      return "plain";
    }
  }

  static class Upper {

    static String zz() {
      return "upper";
    }

    String ii() {
      return "upper";
    }
  }

  static final class Lower extends Upper {

    static final Lower LOWER = new Lower();

    static final Upper AS_UPPER = new Lower();

    static String zz(final Person person) {
      return person.first();
    }

    String ii(final Person person) {
      return person.first();
    }
  }

  static final class Stranger {

    String ii(final Person person) {
      return person.first();
    }
  }

  /**
   * The {@code SerializedLambda} of a reference bound to a {@code Lower} receiver naming {@code
   * Upper.ii()}, as written by {@code capturing}, a class standing in for the one the metafactory
   * defines, which holds the receiver in whatever fields it declares.
   */
  private static SerializedLambda boundToUpperIi(final Class<?> capturing) {
    return new SerializedLambda(
      capturing,
      "java/util/function/Supplier",
      "get",
      "()Ljava/lang/Object;",
      MethodHandleInfo.REF_invokeVirtual,
      Upper.class.getName().replace('.', '/'),
      "ii",
      "()Ljava/lang/String;",
      "()Ljava/lang/String;",
      new Object[] { Lower.LOWER }
    );
  }

  @SuppressWarnings("unused")
  static final class HeldAsLower implements Serializable {

    private static final long serialVersionUID = 1L;
    private final transient Lower held = Lower.LOWER;

    private Object writeReplace() {
      return boundToUpperIi(HeldAsLower.class);
    }
  }

  @SuppressWarnings("unused")
  static final class HeldTwice implements Serializable {

    private static final long serialVersionUID = 1L;
    private final transient Lower first = Lower.LOWER;
    private final transient Lower second = Lower.LOWER;

    private Object writeReplace() {
      return boundToUpperIi(HeldTwice.class);
    }
  }

  @SuppressWarnings("unused")
  static final class HeldAsStranger implements Serializable {

    private static final long serialVersionUID = 1L;
    private final transient Stranger held = new Stranger();

    private Object writeReplace() {
      return boundToUpperIi(HeldAsStranger.class);
    }
  }

  static final class HeldNowhere implements Serializable {

    private static final long serialVersionUID = 1L;

    private Object writeReplace() {
      return boundToUpperIi(HeldNowhere.class);
    }
  }

  static final class Hidden {

    static String label() {
      return "plain";
    }

    @SuppressWarnings("unused")
    private static String label(final Person person) {
      return person.first();
    }
  }

  static final class AnyValue {

    static String label() {
      return "plain";
    }

    static String label(final Object value) {
      return "any";
    }

    static <T> String typed(final T value) {
      return "typed";
    }

    static String typed() {
      return "plain";
    }
  }

  static final class Elsewhere {

    static String label() {
      return "plain";
    }

    static String label(final Plain plain) {
      return plain.first();
    }

    static String label(final int number) {
      return "number";
    }
  }

  public static final class Wrapped {

    public Wrapped() {}

    public Wrapped(final Person person) {}
  }

  static final class Implemented implements FromSource<Person, String> {

    private static final long serialVersionUID = 1L;

    @Override
    public String apply(final Person person) {
      return person.first();
    }
  }

  static String full(final Person person) {
    return person.first();
  }

  static String plain(final Plain plain) {
    return plain.first();
  }

  private static <X> Fresh<X> fresh(final Fresh<X> supplier) {
    return supplier;
  }

  private static <A, X> FromSource<A, X> from(final FromSource<A, X> function) {
    return function;
  }

  private static String shown(final Executable form) {
    final var owner = form.getDeclaringClass().getSimpleName();
    final var parameter = form.getParameterTypes()[0].getSimpleName();
    return form instanceof Constructor<?>
      ? "new " + owner + "(" + parameter + ")"
      : owner + "." + form.getName() + "(" + parameter + ")";
  }

  @Nested
  @DisplayName("the form taking the source a reference given no argument did not reach")
  class SourceForm {

    @Test
    @DisplayName("is the static, bound or constructor form of the same name taking the source")
    void eachKindFindsItsForm() {
      assertEquals(
        "Both.label(Person)",
        shown(LambdaIntrospection.oneArgumentFormTaking(fresh(Both::label), Person.class))
      );
      final var both = new Both();
      assertEquals(
        "Both.tag(Person)",
        shown(LambdaIntrospection.oneArgumentFormTaking(fresh(both::tag), Person.class))
      );
      assertEquals(
        "new Wrapped(Person)",
        shown(LambdaIntrospection.oneArgumentFormTaking(fresh(Wrapped::new), Person.class))
      );
    }

    @Test
    @DisplayName("of several, is the one whose parameter's class name sorts first, and is looked for in superclasses")
    void severalAndInherited() {
      assertEquals(
        "Several.label(Named)",
        shown(LambdaIntrospection.oneArgumentFormTaking(fresh(Several::label), Person.class))
      );
      final Method inherited = (Method) LambdaIntrospection.oneArgumentFormTaking(fresh(Derived::label), Person.class);
      assertEquals("Base.label(Person)", shown(inherited));
    }

    @Test
    @DisplayName("of a bound reference, is looked for from the type the receiver is written as, a subclass included")
    void aBoundReferenceStartsAtItsReceiversType() {
      assertEquals(
        "Lower.ii(Person)",
        shown(LambdaIntrospection.oneArgumentFormTaking(fresh(Lower.LOWER::ii), Person.class))
      );
      assertNull(LambdaIntrospection.oneArgumentFormTaking(fresh(Lower.AS_UPPER::ii), Person.class));
    }

    @Test
    @DisplayName("of a static reference, is looked for from the class declaring the method, not the one written")
    void aStaticReferenceStartsAtTheDeclaringClass() {
      assertNull(LambdaIntrospection.oneArgumentFormTaking(fresh(Lower::zz), Person.class));
    }

    @Test
    @DisplayName("of a bound reference, is looked for from the class its one instance field holds the receiver as")
    void aBoundReferenceStartsAtItsOneInstanceField() {
      assertEquals("Lower.ii(Person)", shown(LambdaIntrospection.oneArgumentFormTaking(new HeldAsLower(), Person.class)));
    }

    @Test
    @DisplayName(
      "of a bound reference holding its receiver in no instance field, in several, or as a class off the declaring" +
        " one, is looked for from the declaring class"
    )
    void aBoundReferenceHeldOtherwiseStartsAtTheDeclaringClass() {
      assertNull(LambdaIntrospection.oneArgumentFormTaking(new HeldNowhere(), Person.class));
      assertNull(LambdaIntrospection.oneArgumentFormTaking(new HeldTwice(), Person.class));
      assertNull(LambdaIntrospection.oneArgumentFormTaking(new HeldAsStranger(), Person.class));
    }

    @Test
    @DisplayName("is none when the form is private, takes any Object, takes another class, or a primitive")
    void noneWhereNoFormTakesTheSource() {
      assertNull(LambdaIntrospection.oneArgumentFormTaking(fresh(Hidden::label), Person.class));
      assertNull(LambdaIntrospection.oneArgumentFormTaking(fresh(AnyValue::label), Person.class));
      assertNull(LambdaIntrospection.oneArgumentFormTaking(fresh(AnyValue::typed), Person.class));
      assertNull(LambdaIntrospection.oneArgumentFormTaking(fresh(Elsewhere::label), Person.class));
      assertNull(LambdaIntrospection.oneArgumentFormTaking(fresh(List::of), Person.class));
    }

    @Test
    @DisplayName("is none for a lambda, and for a reference that itself takes an argument")
    void noneForALambdaOrAOneArgumentReference() {
      assertNull(LambdaIntrospection.oneArgumentFormTaking(fresh(() -> "x"), Person.class));
      assertNull(LambdaIntrospection.oneArgumentFormTaking(from(ComputeReferenceDecodeTest::full), Person.class));
    }
  }

  @Nested
  @DisplayName("the class a function of the source takes, and the name a refusal gives it")
  class Argument {

    @Test
    @DisplayName("a static method's parameter, named by its class")
    void staticMethod() {
      final var argument = LambdaIntrospection.argumentOf(from(ComputeReferenceDecodeTest::plain));
      assertEquals(Plain.class, argument.taken());
      assertEquals("ComputeReferenceDecodeTest::plain", argument.name());
    }

    @Test
    @DisplayName("an unbound method's receiver, named by the class it is applied to")
    void unboundMethod() {
      final var argument = LambdaIntrospection.argumentOf(from(Person::shout));
      assertEquals(Person.class, argument.taken());
      assertEquals("Person::shout", argument.name());
      assertEquals("Named::first", LambdaIntrospection.argumentOf(from(Named::first)).name());
    }

    @Test
    @DisplayName("a held function's apply, a constructor, and a lambda's declared parameter")
    void heldConstructorAndLambda() {
      final Function<Plain, String> held = Plain::first;
      final var apply = LambdaIntrospection.argumentOf(from(held::apply));
      assertEquals(Plain.class, apply.taken());
      assertEquals("Function::apply", apply.name());
      final var constructor = LambdaIntrospection.argumentOf(
        ComputeReferenceDecodeTest.<Person, Wrapped>from(Wrapped::new)
      );
      assertEquals(Person.class, constructor.taken());
      assertEquals("Wrapped::new", constructor.name());
      final var lambda = LambdaIntrospection.argumentOf(from((Plain plain) -> plain.first()));
      assertEquals(Plain.class, lambda.taken());
      assertNull(lambda.name());
    }

    @Test
    @DisplayName("none for a class implementing the function, and for a function given a primitive")
    void noneWhereNothingRecordsIt() {
      assertNull(LambdaIntrospection.argumentOf(new Implemented()));
      final IntFrom numbered = number -> "#" + number;
      assertNull(LambdaIntrospection.argumentOf(numbered));
    }
  }
}
