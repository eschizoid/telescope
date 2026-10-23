package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract pins for {@link LambdaIntrospection}: {@code SerializedLambda} decode that recovers
 * method-reference metadata (impl method name + declaring class) used by every runtime navigation
 * site. The error-message text below is the adopter's only debugging signal when they hand a lambda
 * where a method reference was expected — wording is load-bearing, not cosmetic.
 */
class LambdaIntrospectionTest {

  @Test
  void methodReferenceMetadataDoesNotPinAnApplicationClassLoader() throws Exception {
    final var loader = exerciseIsolatedLoader();
    // Explicit GC is enabled in the test JVM. A static strong-key map keeps this alive forever;
    // a ClassValue permits unloading once both the reference and its defining class are unused.
    for (int i = 0; i < 100 && loader.get() != null; i++) {
      System.gc();
      Thread.sleep(10);
    }
    assertNull(loader.get(), "method-reference metadata pinned the loader");
  }

  private static WeakReference<ClassLoader> exerciseIsolatedLoader() throws Exception {
    final String name = UnloadableReference.class.getName();
    final byte[] bytes;
    try (var stream = UnloadableReference.class.getResourceAsStream("UnloadableReference.class")) {
      bytes = Objects.requireNonNull(stream).readAllBytes();
    }
    final var loader = new ClassLoader(null) {
      @Override
      protected Class<?> findClass(final String requested) throws ClassNotFoundException {
        if (!requested.equals(name)) throw new ClassNotFoundException(requested);
        return defineClass(requested, bytes, 0, bytes.length);
      }
    };
    final var fixture = loader.loadClass(name);
    final var reference = (Serializable) fixture.getMethod("create").invoke(null);
    assertEquals("trim", LambdaIntrospection.methodNameOf(reference));
    assertSame(String.class, LambdaIntrospection.implClassOf(reference));
    return new WeakReference<>(loader);
  }

  record User(String name) {}

  static class BeanUser {

    private String email;

    public String getEmail() {
      return email;
    }
  }

  // A Serializable Function so .name() can be passed by method-reference AND lambda forms.
  @FunctionalInterface
  interface SerFn<A, B> extends Function<A, B>, Serializable {}

  @Nested
  @DisplayName("methodNameOf — recover the impl method name from a Serializable method reference")
  class MethodNameRecovery {

    @Test
    @DisplayName("record-component method reference recovers the component name verbatim")
    void recordComponentName() {
      final SerFn<User, String> ref = User::name;
      assertEquals("name", LambdaIntrospection.methodNameOf(ref));
    }

    @Test
    @DisplayName(
      "bean-getter method reference recovers the RAW getter name (NOT property-normalized — that's Beans.propertyOf's job)"
    )
    void beanGetterName() {
      // Layer responsibility boundary: LambdaIntrospection returns the method name; downstream
      // Beans.propertyOf handles the `get`/`is` strip + lowercase first letter. A future "helpful"
      // normalization in this layer would shift the contract and break the propertyOf null-guard
      // fix, which assumes the raw name flows through.
      final SerFn<BeanUser, String> ref = BeanUser::getEmail;
      assertEquals("getEmail", LambdaIntrospection.methodNameOf(ref));
    }

    @Test
    @DisplayName("lambda is REJECTED with a precise error message — the adopter's only debugging signal")
    void lambdaRejected() {
      // u -> u.name() synthesizes a `lambda$xx$N` method whose name can't be a real record/bean
      // component. The wording matters: adopters who write a lambda by accident need to see WHAT
      // they should have written instead (`User::name`) — a generic "bad input" would waste hours.
      final SerFn<User, String> badLambda = u -> u.name();
      final var ex = assertThrows(IllegalArgumentException.class, () -> LambdaIntrospection.methodNameOf(badLambda));
      assertTrue(
        ex.getMessage().contains("method reference"),
        () -> "missing 'method reference' hint: " + ex.getMessage()
      );
      assertTrue(
        ex.getMessage().contains("User::name") || ex.getMessage().contains("getName"),
        () -> "error should show an example method reference, got: " + ex.getMessage()
      );
      assertTrue(
        ex.getMessage().contains("lambda$"),
        () -> "error should include the synthetic name for triage: " + ex.getMessage()
      );
    }

    @Test
    @DisplayName(
      "cache returns identical String instance for repeated lookups — proves the cache short-circuits the writeReplace decode"
    )
    void cacheReturnsSameStringInstance() {
      // Performance-critical: every runtime navigation site calls methodNameOf during DeepMap
      // setup. A broken cache (e.g. removing computeIfAbsent's lambda-identity dedup) would mean
      // each call pays the writeReplace reflection cost — invisible until profiling.
      final SerFn<User, String> ref = User::name;
      final var first = LambdaIntrospection.methodNameOf(ref);
      final var second = LambdaIntrospection.methodNameOf(ref);
      assertSame(first, second, "cache must return the same String instance across calls");
    }
  }

  @Nested
  @DisplayName("implClassOf — recover the DECLARING class of a method reference")
  class ImplClassRecovery {

    @Test
    @DisplayName("record method reference resolves to the record class")
    void recordMethodRefDeclaringClass() {
      final SerFn<User, String> ref = User::name;
      assertSame(User.class, LambdaIntrospection.implClassOf(ref));
    }

    @Test
    @DisplayName("bean getter method reference resolves to the bean class")
    void beanGetterDeclaringClass() {
      final SerFn<BeanUser, String> ref = BeanUser::getEmail;
      assertSame(BeanUser.class, LambdaIntrospection.implClassOf(ref));
    }

    @Test
    @DisplayName("lambda is REJECTED with the same precise error message — error parity with methodNameOf")
    void lambdaRejectedSameMessage() {
      final SerFn<User, String> badLambda = u -> u.name();
      final var ex = assertThrows(IllegalArgumentException.class, () -> LambdaIntrospection.implClassOf(badLambda));
      assertTrue(
        ex.getMessage().contains("method reference"),
        () -> "missing 'method reference' hint: " + ex.getMessage()
      );
      assertTrue(
        ex.getMessage().contains("lambda$"),
        () -> "error should include the synthetic name: " + ex.getMessage()
      );
    }

    @Test
    @DisplayName("inherited bean getter resolves to the SUPERCLASS that declares it, not the receiver")
    void inheritedGetterReturnsSuperclass() {
      // implClassOf answers the declaring class; receiverClassOf answers the receiver.
      final SerFn<SubBean, String> ref = SubBean::getInheritedName;
      assertSame(SuperBean.class, LambdaIntrospection.implClassOf(ref));
      assertNotEquals(SubBean.class, LambdaIntrospection.implClassOf(ref));
    }

    @Test
    @DisplayName("repeat implClassOf lookups on the same lambda class hit the cache and return the same Class result")
    void cacheHitOnRepeatLookupSameLambda() {
      // Pins the ConcurrentHashMap.computeIfAbsent short-circuit at line 70 of LambdaIntrospection
      // — the second call must skip resolveImplClass and read directly from the cache. Same-result
      // assertSame guarantees consistency; performance is implicit (a broken cache here would
      // re-decode SerializedLambda every call site).
      final SerFn<User, String> ref = User::name;
      assertSame(User.class, LambdaIntrospection.implClassOf(ref));
      assertSame(User.class, LambdaIntrospection.implClassOf(ref));
    }
  }

  @Nested
  @DisplayName("receiverClassOf — recover the class a method reference is applied to")
  class ReceiverClassRecovery {

    @Test
    @DisplayName("an inherited bean getter resolves to the subclass it is applied to")
    void inheritedGetterResolvesToTheReceiver() {
      final SerFn<SubBean, String> ref = SubBean::getInheritedName;
      assertSame(SubBean.class, LambdaIntrospection.receiverClassOf(ref));
    }

    @Test
    @DisplayName("a superclass-qualified reference used at a subclass type resolves to the subclass")
    void superclassQualifiedReferenceResolvesToTheReceiver() {
      final SerFn<SubBean, String> ref = SuperBean::getInheritedName;
      assertSame(SubBean.class, LambdaIntrospection.receiverClassOf(ref));
    }

    @Test
    @DisplayName("a record accessor resolves to the record, as implClassOf does")
    void recordAccessorResolvesToTheRecord() {
      final SerFn<User, String> ref = User::name;
      assertSame(User.class, LambdaIntrospection.receiverClassOf(ref));
    }

    @Test
    @DisplayName("a receiver typed by a type variable resolves to the variable's bound")
    void typeVariableReceiverResolvesToItsBound() {
      assertSame(SuperBean.class, LambdaIntrospection.receiverClassOf(typeVariableReceiver()));
    }

    private static <T extends SuperBean> SerFn<T, String> typeVariableReceiver() {
      return SuperBean::getInheritedName;
    }
  }

  @Nested
  @DisplayName("narrowedReturnOf — the class an instantiation reads a property as, where it is narrower")
  class NarrowedReturn {

    @Test
    @DisplayName("a reference typed by a class's argument returns that argument, narrower than the variable's erasure")
    void anArgumentNarrowsTheVariable() {
      final SerFn<Box<String>, String> ref = Box::value;
      assertSame(String.class, LambdaIntrospection.narrowedReturnOf(ref, Object.class));
    }

    @Test
    @DisplayName("an array argument narrows the variable to the array class")
    void anArrayArgumentNarrowsTheVariable() {
      final SerFn<Box<String[]>, String[]> ref = Box::value;
      assertSame(String[].class, LambdaIntrospection.narrowedReturnOf(ref, Object.class));
    }

    @Test
    @DisplayName("a reference returning its method's own class, or a superclass or interface of it, narrows nothing")
    void theOwnClassOrASupertypeNarrowsNothing() {
      final SerFn<User, String> own = User::name;
      final SerFn<Listed, List<String>> implemented = Listed::value;
      final SerFn<Listed, AbstractList<String>> extended = Listed::value;
      assertNull(LambdaIntrospection.narrowedReturnOf(own, String.class));
      assertNull(LambdaIntrospection.narrowedReturnOf(implemented, ArrayList.class));
      assertNull(LambdaIntrospection.narrowedReturnOf(extended, ArrayList.class));
    }

    @Test
    @DisplayName("an array of a supertype of the elements narrows nothing")
    void anArrayOfASupertypeNarrowsNothing() {
      final SerFn<Strings, Object[]> ref = Strings::value;
      assertNull(LambdaIntrospection.narrowedReturnOf(ref, String[].class));
    }

    @Test
    @DisplayName("a primitive is compared as its wrapper, and a primitive return narrows nothing")
    void aPrimitiveIsComparedAsItsWrapper() {
      final SerFn<Count, Integer> boxed = Count::value;
      final SerFn<Count, Number> widened = Count::value;
      final SerToInt<Count> primitive = Count::value;
      assertNull(LambdaIntrospection.narrowedReturnOf(boxed, int.class));
      assertNull(LambdaIntrospection.narrowedReturnOf(widened, int.class));
      assertNull(LambdaIntrospection.narrowedReturnOf(primitive, int.class));
    }
  }

  record Box<X>(X value) {}

  record Listed(ArrayList<String> value) {}

  record Strings(String[] value) {}

  record Count(int value) {}

  @FunctionalInterface
  interface SerToInt<A> extends ToIntFunction<A>, Serializable {}

  static class SuperBean {

    private String inheritedName;

    public String getInheritedName() {
      return inheritedName;
    }
  }

  static class SubBean extends SuperBean {}

  /** The classes a method reference converting one value takes and gives. */
  @Nested
  class ConversionOf {

    interface Fn<A, B> extends Function<A, B>, Serializable {}

    interface Fn2<A, B, C> extends BiFunction<A, B, C>, Serializable {}

    static Integer length(final CharSequence s) {
      return s.length();
    }

    static <T extends CharSequence> Integer generic(final T s) {
      return s.length();
    }

    static long twice(final int n) {
      return n * 2L;
    }

    private static Class<?>[] of(final Serializable reference) {
      final var conversion = LambdaIntrospection.conversionOf(reference);
      return new Class<?>[] { conversion.from(), conversion.to() };
    }

    static Shape draw(final Shape shape) {
      return shape;
    }

    @SuppressWarnings("unchecked")
    static <D> D infer(final String s) {
      return (D) s;
    }

    @Test
    @DisplayName("a static method takes its parameter's class and gives its return's")
    void aStaticMethod() {
      final Fn<CharSequence, Integer> f = ConversionOf::length;
      assertEquals(List.of(CharSequence.class, Integer.class), List.of(of(f)));
    }

    @Test
    @DisplayName("a parameter typed by a type variable takes the class the instantiation gives it")
    void aGenericMethod() {
      final Fn<String, Integer> f = ConversionOf::generic;
      assertEquals(List.of(String.class, Integer.class), List.of(of(f)));
    }

    @Test
    @DisplayName("a primitive parameter and return are their wrappers")
    void primitives() {
      final Fn<Integer, Long> f = ConversionOf::twice;
      assertEquals(List.of(Integer.class, Long.class), List.of(of(f)));
    }

    @Test
    @DisplayName("an unbound instance method takes its receiver")
    void anUnboundMethod() {
      final Fn<String, Integer> f = String::length;
      assertEquals(List.of(String.class, Integer.class), List.of(of(f)));
    }

    @Test
    @DisplayName("a constructor gives the class it constructs")
    void aConstructor() {
      final Fn<String, StringBuilder> f = StringBuilder::new;
      assertEquals(List.of(String.class, StringBuilder.class), List.of(of(f)));
    }

    @Test
    @DisplayName("a method bound to a receiver takes its one parameter")
    void aBoundMethod() {
      final Fn<String, String> f = "a"::concat;
      assertEquals(List.of(String.class, String.class), List.of(of(f)));
    }

    @Test
    @DisplayName("a method taking two values is refused by name, a receiver's included")
    void twoValuesAreRefused() {
      final Fn2<Integer, Integer, Integer> sum = Integer::sum;
      final var refused = assertThrows(IllegalArgumentException.class, () -> of(sum));
      assertTrue(refused.getMessage().contains("java.lang.Integer::sum takes 2"), refused::getMessage);
      final Fn2<String, String, String> concat = String::concat;
      final var unbound = assertThrows(IllegalArgumentException.class, () -> of(concat));
      assertTrue(unbound.getMessage().contains("takes 1 besides its receiver"), unbound::getMessage);
    }

    interface Carries<D> {
      D carried();

      default D dto() {
        return carried();
      }
    }

    record Tagged(String name) implements Carries<String> {
      @Override
      public String carried() {
        return name;
      }
    }

    interface Shape {
      default String label() {
        return "shape";
      }
    }

    record Circle(int r) implements Shape {}

    record Holder<T>(T value) {
      public T get() {
        return value;
      }
    }

    @Test
    @DisplayName("an inherited method returning a supertype's variable gives the class the receiver binds it to")
    void anInheritedGenericReturn() {
      final Fn<Tagged, String> f = Tagged::dto;
      assertEquals(String.class, of(f)[1]);
    }

    @Test
    @DisplayName("a reference takes the class its instantiation casts the argument to, a narrowed one included")
    void aReferenceTakesItsInstantiatedClass() {
      final Fn<Circle, String> f = Circle::label;
      assertEquals(List.of(Circle.class, String.class), List.of(of(f)));
      final Fn<Circle, Shape> narrowed = ConversionOf::draw;
      assertEquals(List.of(Circle.class, Shape.class), List.of(of(narrowed)));
      final Fn<Shape, Shape> wide = ConversionOf::draw;
      assertEquals(List.of(Shape.class, Shape.class), List.of(of(wide)));
      assertTrue(LambdaIntrospection.takesReceiver(f));
      final Fn<CharSequence, Integer> s = ConversionOf::length;
      assertFalse(LambdaIntrospection.takesReceiver(s));
    }

    @Test
    @DisplayName("a return a type variable hides is refused by name")
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void aHiddenReturnIsRefused() {
      final Fn raw = (Fn<Holder, Object>) Holder::get;
      final var refused = assertThrows(IllegalArgumentException.class, () -> of(raw));
      assertTrue(
        refused.getMessage().contains("returns, which a type variable of its class names"),
        refused::getMessage
      );
    }

    @Test
    @DisplayName("a method returning its own type variable gives its bound, which the call site infers")
    void anInferredResult() {
      final Fn<String, Object> f = ConversionOf::infer;
      final var conversion = LambdaIntrospection.conversionOf(f);
      assertEquals(List.of(String.class, Object.class), List.of(conversion.from(), conversion.to()));
      assertTrue(conversion.resultInferred());
      final Fn<CharSequence, Integer> plain = ConversionOf::length;
      assertFalse(LambdaIntrospection.conversionOf(plain).resultInferred());
    }

    @Test
    @DisplayName("a lambda is refused, since only a method reference names its classes")
    void aLambdaIsRefused() {
      final Fn<String, Integer> f = x -> x.length();
      assertThrows(IllegalArgumentException.class, () -> of(f));
    }
  }
}
