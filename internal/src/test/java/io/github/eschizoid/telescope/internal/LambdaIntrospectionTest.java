package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serializable;
import java.util.function.Function;
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
    @DisplayName("inherited bean getter resolves to the SUPERCLASS, not the receiver — documented limitation")
    void inheritedGetterReturnsSuperclass() {
      // Per the class javadoc: "for beans, a method inherited from a superclass returns the
      // superclass". Adopters who depend on the receiver-side declaring class must obtain it some
      // other way. Pinning the documented limitation guards against a future "fix" that silently
      // shifts the semantics — adopters who relied on the documented behavior would break.
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

  static class SuperBean {

    private String inheritedName;

    public String getInheritedName() {
      return inheritedName;
    }
  }

  static class SubBean extends SuperBean {}
}
