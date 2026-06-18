package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.ParameterizedType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Surface tests for {@link Reflective} — the polymorphic record/bean dispatch substrate that
 * DeepMap drives. Pins the {@code of(Class)} routing, the per-instance delegate wrappers ({@code
 * names} / {@code genericType} / {@code read} / {@code construct} / {@code normalize}), the
 * hint-aware bean variant, and {@code positionalBuilder} fast paths.
 */
class ReflectiveSurfaceTest {

  record SimpleUser(String name, int age) {}

  record AccountRecord(String id, List<String> tags) {}

  public static class SimpleBean {

    private String name;
    private int age;

    public SimpleBean() {}

    public String getName() {
      return name;
    }

    public int getAge() {
      return age;
    }

    public void setName(final String name) {
      this.name = name;
    }

    public void setAge(final int age) {
      this.age = age;
    }
  }

  @Nested
  @DisplayName("of(Class) — record/bean dispatch")
  class FactoryDispatch {

    @Test
    @DisplayName("of(Record.class) routes to the RECORDS singleton; of(BeanClass) routes to BEANS")
    void ofRoutesByClassKind() {
      assertSame(Reflective.RECORDS, Reflective.of(SimpleUser.class));
      assertSame(Reflective.BEANS, Reflective.of(SimpleBean.class));
    }
  }

  @Nested
  @DisplayName("Delegate wrappers — names / genericType / read / construct / normalize")
  class DelegateWrappers {

    @Test
    @DisplayName("RECORDS#names returns component names in canonical order")
    void recordNames() {
      assertArrayEquals(new String[] { "name", "age" }, Reflective.RECORDS.names(SimpleUser.class));
    }

    @Test
    @DisplayName("BEANS#names returns property names in scan order")
    void beanNames() {
      final var names = Reflective.BEANS.names(SimpleBean.class);
      assertTrue(List.of(names).contains("name"));
      assertTrue(List.of(names).contains("age"));
    }

    @Test
    @DisplayName("genericType preserves parameterised types so DeepMap can detect List<String> containers")
    void genericTypePreservesParameterisedShape() {
      final var t = Reflective.RECORDS.genericType(AccountRecord.class, "tags");
      assertTrue(t instanceof ParameterizedType, () -> "expected ParameterizedType, got " + t);
      assertEquals(List.class, ((ParameterizedType) t).getRawType());
    }

    @Test
    @DisplayName("read delegates to Records.read for the record reflective")
    void recordReadDelegates() {
      assertEquals("alice", Reflective.RECORDS.read(new SimpleUser("alice", 30), "name"));
    }

    @Test
    @DisplayName("read delegates to Beans.readProperty for the bean reflective")
    void beanReadDelegates() {
      final var bean = new SimpleBean();
      bean.setName("alice");
      assertEquals("alice", Reflective.BEANS.read(bean, "name"));
    }

    @Test
    @DisplayName("construct delegates to Records.construct for records and rebuilds the canonical instance")
    void recordConstructDelegates() {
      final var built = Reflective.RECORDS.construct(SimpleUser.class, name ->
        switch (name) {
          case "name" -> "bob";
          case "age" -> 21;
          default -> throw new IllegalStateException();
        }
      );
      assertEquals(new SimpleUser("bob", 21), built);
    }

    @Test
    @DisplayName("construct delegates to autoWriter for beans and populates the no-arg-ctor instance")
    void beanConstructDelegates() {
      final var built = (SimpleBean) Reflective.BEANS.construct(SimpleBean.class, name ->
        switch (name) {
          case "name" -> "carol";
          case "age" -> 27;
          default -> null;
        }
      );
      assertEquals("carol", built.getName());
      assertEquals(27, built.getAge());
    }

    @Test
    @DisplayName("RECORDS#normalize is identity (record component names need no transformation)")
    void recordNormalizeIsIdentity() {
      assertEquals("name", Reflective.RECORDS.normalize("name"));
      assertEquals("getAge", Reflective.RECORDS.normalize("getAge"));
    }

    @Test
    @DisplayName("BEANS#normalize strips the get/is prefix and decapitalises (mirrors JavaBeans rules)")
    void beanNormalizeStripsPrefixAndDecapitalises() {
      assertEquals("name", Reflective.BEANS.normalize("getName"));
      assertEquals("active", Reflective.BEANS.normalize("isActive"));
      assertEquals("URL", Reflective.BEANS.normalize("getURL"), "two-leading-caps rule keeps the name as-is");
    }
  }

  @Nested
  @DisplayName("beansWithHints — per-class hint + lazy default writer factory")
  class HintAwareBean {

    @Test
    @DisplayName("hint map drives the construct path: a SettersWriter hint produces a populated instance")
    void hintWriterDrivesConstruct() {
      // SettersWriter is a sealed-interface implementor so we can't use a lambda — but we can
      // verify the hint took precedence over autoWriter by confirming the constructed instance
      // is exactly the SimpleBean shape the hint produces.
      final Map<Class<?>, Beans.BeanWriter<?>> hints = Map.of(SimpleBean.class, Beans.settersWriter(SimpleBean.class));
      final var refl = Reflective.beansWithHints(hints, null);

      final var built = (SimpleBean) refl.construct(SimpleBean.class, name ->
        switch (name) {
          case "name" -> "dave";
          case "age" -> 19;
          default -> null;
        }
      );

      assertEquals("dave", built.getName());
      assertEquals(19, built.getAge());
    }

    @Test
    @DisplayName("default factory fires for non-hinted classes when no hint is present (lazy resolution)")
    void defaultFactoryFiresForUnhintedClasses() {
      // Track factory-call count to prove the lazy path is reached — the factory must be invoked
      // when no hint covers the class. (The lambda runs at first encounter; if the result is
      // memoised internally the count stays at 1 across multiple construct() calls.)
      final var factoryCalls = new int[] { 0 };
      final var refl = Reflective.beansWithHints(new HashMap<>(), cls -> {
        factoryCalls[0]++;
        return Beans.settersWriter(SimpleBean.class);
      });

      refl.construct(SimpleBean.class, name -> name.equals("name") ? "x" : 1);
      assertTrue(factoryCalls[0] >= 1, "factory must fire at least once for the unhinted class");
    }
  }

  @Nested
  @DisplayName("positionalBuilder — Object[arity] → T fast-path for the runtime mapper")
  class PositionalBuilder {

    @Test
    @DisplayName("record fast-path: no holder data → cached canonical-ctor function applies the array directly")
    void recordFastPathBindsCanonicalCtor() {
      final var builder = Reflective.RECORDS.<SimpleUser>positionalBuilder(SimpleUser.class, null, null);
      assertNotNull(builder);
      final SimpleUser built = builder.apply(new Object[] { "eve", 24 });
      assertEquals(new SimpleUser("eve", 24), built);
    }

    @Test
    @DisplayName("bean fallback: no holder data → array-positional construct path resolves via autoWriter")
    void beanFallbackUsesAutoWriter() {
      final String[] names = Reflective.BEANS.names(SimpleBean.class);
      final var builder = Reflective.BEANS.<SimpleBean>positionalBuilder(SimpleBean.class, null, null);
      // Build a values array matched to the bean's name order so the rebuild can reverse-lookup.
      final var arr = new Object[names.length];
      for (var i = 0; i < names.length; i++) {
        arr[i] = names[i].equals("name") ? "frank" : 17;
      }
      final SimpleBean built = builder.apply(arr);
      assertEquals("frank", built.getName());
      assertEquals(17, built.getAge());
    }
  }

  @Nested
  @DisplayName("positionalReaders — exposes the per-slot reader array (holder-aware)")
  class PositionalReaders {

    @Test
    @DisplayName("record path reads each component via the LMF-cached positional reader")
    void recordReadersReturnComponentValues() {
      final var readers = Reflective.RECORDS.positionalReaders(SimpleUser.class, null);
      final var user = new SimpleUser("gabe", 33);
      assertEquals(2, readers.length);
      assertEquals("gabe", readers[0].apply(user));
      assertEquals(33, readers[1].apply(user));
    }

    @Test
    @DisplayName("bean path captures the property name per slot and reads through Beans.readProperty's substrate")
    void beanReadersReadByPropertyName() {
      final var refl = Reflective.BEANS;
      final var readers = refl.positionalReaders(SimpleBean.class, null);
      final var bean = new SimpleBean();
      bean.setName("hank");
      bean.setAge(41);
      final var names = refl.names(SimpleBean.class);
      // Find the slots for name and age — the bean fallback doesn't promise a specific order, only
      // the same order as #names. Use the names array as the indirection.
      final var nameIdx = indexOf(names, "name");
      final var ageIdx = indexOf(names, "age");
      assertEquals("hank", readers[nameIdx].apply(bean));
      assertEquals(41, readers[ageIdx].apply(bean));
    }

    private static int indexOf(final String[] arr, final String needle) {
      for (var i = 0; i < arr.length; i++) if (arr[i].equals(needle)) return i;
      throw new IllegalArgumentException("not found: " + needle);
    }
  }

  @Nested
  @DisplayName("Holder-aware structuralIso path through positionalBuilder")
  class HolderAware {

    @Test
    @DisplayName(
      "holderConstructor short-circuits the reflective construct path: returned function is applied with a name-indexed array view"
    )
    void holderConstructorTakesPriorityOverReflectiveBuild() {
      // Stand-in for a codegen FieldOptics' bound construct(Function<String, Object>): if the
      // caller passes a non-null holderConstructor, positionalBuilder must invoke it instead of
      // going through Reflective#construct. Pins the contract by routing the result through a
      // sentinel record-builder lambda that's distinct from the canonical ctor's behaviour.
      final Function<String, Object> sentinel = name ->
        switch (name) {
          case "name" -> "via-holder";
          case "age" -> 99;
          default -> null;
        };
      final var builder = Reflective.RECORDS.<SimpleUser>positionalBuilder(SimpleUser.class, Map.of(), ignored ->
        new SimpleUser((String) sentinel.apply("name"), (Integer) sentinel.apply("age"))
      );
      final SimpleUser built = builder.apply(new Object[] { "ignored", 0 });
      assertEquals(new SimpleUser("via-holder", 99), built);
    }
  }
}
