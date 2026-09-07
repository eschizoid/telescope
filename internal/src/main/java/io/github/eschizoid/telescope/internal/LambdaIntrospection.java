package io.github.eschizoid.telescope.internal;

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;

/**
 * Reflective extraction of method-reference metadata via {@link SerializedLambda}. The runtime
 * navigation, conversion, and mapping layers all need to recover the impl method name (e.g. {@code
 * "name"} from {@code User::name}) and the declaring class (e.g. {@code User.class}) of a
 * Serializable method reference. This is the one place that decode lives.
 *
 * <p>This class is in the {@code internal} package so it isn't visible to consumers of the module —
 * the JPMS export list deliberately omits {@code internal.*}. The methods are {@code public static}
 * so the {@code mapping} and {@code conversion} sub-packages can call them across the module
 * without the inner reflection details leaking to user code.
 *
 * <p>Lambdas (e.g. {@code u -> u.name()}) are explicitly rejected — their implementation method
 * name is {@code lambda$xx$0}, which can't be recovered as a record component name. The error
 * message tells the caller to use a method reference instead.
 */
public final class LambdaIntrospection {

  private LambdaIntrospection() {}

  // Values belong to the lambda class and disappear with its loader. Never retain the lambda
  // instance or SerializedLambda: a bound method reference can capture an entire application graph.
  private static final ClassValue<MetadataSlot> CACHE = new ClassValue<>() {
    @Override
    protected MetadataSlot computeValue(final Class<?> type) {
      return new MetadataSlot();
    }
  };

  private record Metadata(String methodName, String implName) {}

  private static final class MetadataSlot {

    private volatile Metadata metadata;
    private volatile Class<?> implClass;

    Metadata get(final Serializable lambda) {
      final var cached = metadata;
      if (cached != null) return cached;
      synchronized (this) {
        if (metadata == null) metadata = decode(lambda);
        return metadata;
      }
    }
  }

  /**
   * The impl method name of a Serializable method reference (e.g. {@code "name"} from {@code
   * User::name}). Cached per lambda class — every reference to a given method ref shares the same
   * synthesized class, so the cache is fully effective for repeat lookups.
   *
   * @throws IllegalArgumentException if the lambda is not a method reference (its impl method name
   *     starts with {@code "lambda$"})
   */
  public static String methodNameOf(final Serializable lambda) {
    return CACHE.get(lambda.getClass()).get(lambda).methodName();
  }

  private static Metadata decode(final Serializable lambda) {
    try {
      final var writeReplace = lambda.getClass().getDeclaredMethod("writeReplace");
      writeReplace.setAccessible(true);
      final var serialized = (SerializedLambda) writeReplace.invoke(lambda);
      final var name = serialized.getImplMethodName();
      if (name.startsWith("lambda$")) throw new IllegalArgumentException(
        "Expected a method reference (e.g. User::name, User::getName), not a lambda. Got: " + name
      );
      return new Metadata(name, serialized.getImplClass().replace('/', '.'));
    } catch (final ReflectiveOperationException e) {
      throw new IllegalArgumentException(
        "Expected a method reference to a record component / bean property accessor",
        e
      );
    }
  }

  /**
   * The declaring class of a Serializable method reference (e.g. {@code UserEntity.class} from
   * {@code UserEntity::name}). Records can't extend other types, so for record accessors the
   * declaring class is always the receiver type. For beans, a method inherited from a superclass
   * returns the superclass — callers that need the receiver type must obtain it some other way.
   *
   * @throws IllegalArgumentException if the lambda is not a method reference
   */
  @SuppressWarnings("unchecked")
  public static <A> Class<A> implClassOf(final Serializable lambda) {
    final var slot = CACHE.get(lambda.getClass());
    final var metadata = slot.get(lambda);
    var result = slot.implClass;
    if (result == null) {
      try {
        result = Class.forName(metadata.implName(), false, lambda.getClass().getClassLoader());
        slot.implClass = result;
      } catch (final ClassNotFoundException e) {
        throw new IllegalArgumentException("Expected a method reference; got: " + lambda, e);
      }
    }
    return (Class<A>) result;
  }
}
