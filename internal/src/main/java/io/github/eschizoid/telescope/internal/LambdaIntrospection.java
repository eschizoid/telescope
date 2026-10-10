package io.github.eschizoid.telescope.internal;

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.InaccessibleObjectException;

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

  private record Metadata(String methodName, String implName, String receiverName) {}

  private static final class MetadataSlot {

    private volatile Metadata metadata;
    private volatile Class<?> implClass;
    private volatile Class<?> receiverClass;

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
      try {
        writeReplace.setAccessible(true);
      } catch (final InaccessibleObjectException e) {
        // The method reference is compiled into the class that wrote it, so reading it back needs
        // that class's package open to this module, the same opens every accessor needs.
        throw new IllegalStateException(
          "Cannot read the method reference " +
            lambda.getClass().getName() +
            ". " +
            ModuleAccess.opensRemedy(lambda.getClass()),
          e
        );
      }
      final var serialized = (SerializedLambda) writeReplace.invoke(lambda);
      final var name = serialized.getImplMethodName();
      if (name.startsWith("lambda$")) throw new IllegalArgumentException(
        "Expected a method reference (e.g. User::name, User::getName), not a lambda. Got: " + name
      );
      return new Metadata(
        name,
        serialized.getImplClass().replace('/', '.'),
        receiverNameOf(serialized.getInstantiatedMethodType())
      );
    } catch (final ReflectiveOperationException e) {
      throw new IllegalArgumentException(
        "Expected a method reference to a record component / bean property accessor",
        e
      );
    }
  }

  // The binary name of the first parameter in a method descriptor, or null when it is not a class.
  // For an unbound method reference that parameter is the receiver, typed as the functional
  // interface's instantiation erases it at the call site.
  private static String receiverNameOf(final String descriptor) {
    if (descriptor.length() < 2 || descriptor.charAt(1) != 'L') return null;
    final var end = descriptor.indexOf(';');
    return end < 0 ? null : descriptor.substring(2, end).replace('/', '.');
  }

  /**
   * The binary name of the class declaring a Serializable method reference's target, read from the
   * decoded {@code SerializedLambda} without loading the class. Callers that already hold candidate
   * classes match against this name instead of {@link #implClassOf}, which resolves the name with
   * {@code Class.forName} — a lookup a native image answers only for classes registered for
   * reflection.
   *
   * @throws IllegalArgumentException if the lambda is not a method reference
   */
  public static String implClassNameOf(final Serializable lambda) {
    return CACHE.get(lambda.getClass()).get(lambda).implName();
  }

  /**
   * The declaring class of a Serializable method reference (e.g. {@code UserEntity.class} from
   * {@code UserEntity::name}). Records can't extend other types, so for record accessors the
   * declaring class is always the receiver type. For beans, a method inherited from a superclass
   * returns the superclass; {@link #receiverClassOf} answers the class the reference is applied to.
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

  /**
   * The class a Serializable method reference is applied to: the receiver type of its instantiated
   * method type when that is a subtype of the declaring class, and the declaring class otherwise.
   * For {@code Sub::getNext} passed where an {@code Accessor<Sub, ?>} is expected, with {@code
   * getNext} declared on an abstract superclass, this is {@code Sub} while {@link #implClassOf} is
   * the superclass. A receiver erased to a type that is not a subtype of the declaring class, such
   * as {@code Object} for a type variable, answers the declaring class. For a record accessor the
   * two always agree.
   *
   * @throws IllegalArgumentException if the lambda is not a method reference
   */
  @SuppressWarnings("unchecked")
  public static <A> Class<A> receiverClassOf(final Serializable lambda) {
    final var slot = CACHE.get(lambda.getClass());
    var result = slot.receiverClass;
    if (result == null) {
      final Class<?> declaring = implClassOf(lambda);
      result = declaring;
      final var receiverName = slot.get(lambda).receiverName();
      if (receiverName != null && !receiverName.equals(declaring.getName())) {
        try {
          final var receiver = Class.forName(receiverName, false, lambda.getClass().getClassLoader());
          if (declaring.isAssignableFrom(receiver)) result = receiver;
        } catch (final ClassNotFoundException e) {
          // A receiver the lambda's loader cannot resolve keeps the declaring class.
        }
      }
      slot.receiverClass = result;
    }
    return (Class<A>) result;
  }
}
