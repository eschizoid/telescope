package io.github.eschizoid.telescope.internal;

import java.io.Serializable;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodType;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.TypeVariable;
import java.util.Arrays;

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

  private record Metadata(
    String methodName,
    String implName,
    String receiverName,
    String returnName,
    String implSignature,
    int implKind,
    int capturedArgs
  ) {}

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
        receiverNameOf(serialized.getInstantiatedMethodType()),
        returnNameOf(serialized.getInstantiatedMethodType()),
        serialized.getImplMethodSignature(),
        serialized.getImplMethodKind(),
        serialized.getCapturedArgCount()
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

  // The name Class.forName takes for the return type of a method descriptor, or null when it is
  // primitive or void. An array keeps its descriptor form, with dots for slashes.
  private static String returnNameOf(final String descriptor) {
    final var type = descriptor.substring(descriptor.indexOf(')') + 1);
    if (type.startsWith("[")) return type.replace('/', '.');
    if (!type.startsWith("L")) return null;
    return type.substring(1, type.length() - 1).replace('/', '.');
  }

  /**
   * The class a Serializable method reference's instantiation says it returns, where that is
   * narrower than {@code declared}, the class the referenced method returns; null where every value
   * of {@code declared} is an instance of it. For {@code Box::value} passed where an {@code
   * Accessor<Box<String>, String>} is expected, with {@code value} declared by a type variable
   * whose bound erases to {@code Object}, this is {@code String}: the class a caller typed by the
   * instantiation takes the value as, which nothing checked when the method returned it. A
   * primitive {@code declared} is compared as its wrapper.
   *
   * <p>The supertypes of {@code declared} are compared by name first, so a reference returning its
   * method's own class or one of its supertypes loads nothing. Only a narrower class is loaded, by
   * name through the lambda's class loader, as {@link #receiverClassOf} loads a receiver.
   *
   * @throws IllegalArgumentException if the lambda is not a method reference
   * @throws IllegalStateException if the narrower class cannot be loaded
   */
  public static Class<?> narrowedReturnOf(final Serializable lambda, final Class<?> declared) {
    final var name = CACHE.get(lambda.getClass()).get(lambda).returnName();
    if (name == null) return null;
    final var widened = MethodType.methodType(declared).wrap().returnType();
    if (namesSupertype(widened, name)) return null;
    final Class<?> returned;
    try {
      returned = Class.forName(name, false, lambda.getClass().getClassLoader());
    } catch (final ClassNotFoundException e) {
      throw new IllegalStateException(
        "Cannot load " +
          name +
          ", the class the method reference " +
          implClassNameOf(lambda) +
          "::" +
          methodNameOf(lambda) +
          " is typed to return. Inside a native image, register it for reflection.",
        e
      );
    }
    return returned.isAssignableFrom(widened) ? null : returned;
  }

  // Whether `name` is the name of `type` or of one of its superclasses or interfaces.
  private static boolean namesSupertype(final Class<?> type, final String name) {
    if (type == null) return false;
    if (type.getName().equals(name)) return true;
    if (namesSupertype(type.getSuperclass(), name)) return true;
    for (final var implemented : type.getInterfaces()) {
      if (namesSupertype(implemented, name)) return true;
    }
    return false;
  }

  /**
   * The class a Serializable method reference converting one value takes, and the class it gives,
   * read from the referenced method itself: a static method's or a constructor's one parameter, or
   * the receiver of an unbound instance method, and the method's return, or the constructed class.
   * A parameter or return typed by a type variable is its bound, and a primitive is its wrapper, so
   * {@code static <T extends Line> long weigh(T line)} takes {@code Line} and gives {@code Long}.
   * The classes are loaded by name through the lambda's class loader, as {@link #receiverClassOf}
   * loads a receiver.
   *
   * @return the class taken, then the class given
   * @throws IllegalArgumentException if the lambda is not a method reference, or the method does
   *     not take exactly one value
   * @throws IllegalStateException if a class cannot be loaded
   */
  public static Class<?>[] conversionOf(final Serializable lambda) {
    final var metadata = CACHE.get(lambda.getClass()).get(lambda);
    final MethodType type;
    try {
      type = MethodType.fromMethodDescriptorString(metadata.implSignature(), lambda.getClass().getClassLoader());
    } catch (final TypeNotPresentException e) {
      throw new IllegalStateException(
        "Cannot load a class the method reference " +
          metadata.implName() +
          "::" +
          metadata.methodName() +
          " names. Inside a native image, register it for reflection.",
        e
      );
    }
    final var kind = metadata.implKind();
    final var unbound = takesReceiver(lambda);
    final Class<?> from;
    // An unbound method takes any instance of the class declaring it, whatever receiver the call
    // site's inference narrowed the reference to.
    if (unbound && type.parameterCount() == 0) from = implClassOf(lambda);
    else if (!unbound && type.parameterCount() == 1) from = type.parameterType(0);
    else throw new IllegalArgumentException(
      "Expected a method reference converting one value, such as Hw::forward; " +
        metadata.implName() +
        "::" +
        metadata.methodName() +
        " takes " +
        type.parameterCount() +
        (unbound ? " besides its receiver" : "")
    );
    final Class<?> to = kind == MethodHandleInfo.REF_newInvokeSpecial ? implClassOf(lambda) : returnedBy(lambda, type);
    return new Class<?>[] {
      MethodType.methodType(from).wrap().returnType(),
      MethodType.methodType(to).wrap().returnType(),
    };
  }

  /**
   * Whether a Serializable method reference names an instance method it calls on the value it is
   * handed, as {@code Shape::toDto} does, rather than a static method, a constructor, or a method
   * of a receiver it holds.
   *
   * @throws IllegalArgumentException if the lambda is not a method reference
   */
  public static boolean takesReceiver(final Serializable lambda) {
    final var metadata = CACHE.get(lambda.getClass()).get(lambda);
    final var kind = metadata.implKind();
    return (
      (kind == MethodHandleInfo.REF_invokeVirtual || kind == MethodHandleInfo.REF_invokeInterface) &&
      metadata.capturedArgs() == 0
    );
  }

  /**
   * The class {@code lambda}'s method returns: what the reference's instantiation says, where that
   * is narrower than the method's erased return, as a method a generic supertype declares returns
   * the class the receiver gives its type variable.
   *
   * @throws IllegalArgumentException if the method returns a type variable nothing narrows
   */
  private static Class<?> returnedBy(final Serializable lambda, final MethodType type) {
    final var erased = type.returnType();
    final var narrowed = narrowedReturnOf(lambda, erased);
    if (narrowed != null) return narrowed;
    if (erased == Object.class && returnsTypeVariable(lambda, type)) {
      final var metadata = CACHE.get(lambda.getClass()).get(lambda);
      throw new IllegalArgumentException(
        "Cannot read the class " +
          metadata.implName() +
          "::" +
          metadata.methodName() +
          " returns, which a type variable names; reference a method whose return names the class," +
          " such as a static one"
      );
    }
    return erased;
  }

  /** Whether the method {@code lambda} references declares its return as a type variable. */
  private static boolean returnsTypeVariable(final Serializable lambda, final MethodType type) {
    return returnsTypeVariable(implClassOf(lambda), CACHE.get(lambda.getClass()).get(lambda).methodName(), type);
  }

  private static boolean returnsTypeVariable(final Class<?> owner, final String name, final MethodType type) {
    if (owner == null) return false;
    for (final var method : owner.getDeclaredMethods()) {
      if (
        method.getName().equals(name) &&
        Arrays.equals(method.getParameterTypes(), type.parameterArray()) &&
        method.getGenericReturnType() instanceof TypeVariable<?>
      ) {
        return true;
      }
    }
    if (returnsTypeVariable(owner.getSuperclass(), name, type)) return true;
    for (final var implemented : owner.getInterfaces()) {
      if (returnsTypeVariable(implemented, name, type)) return true;
    }
    return false;
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
   * the superclass. A receiver typed by a type variable is its bound. A receiver that is not a
   * subtype of the declaring class answers the declaring class. For a record accessor the two
   * always agree.
   *
   * <p>The receiver is loaded by name through the lambda's class loader, which on the JVM always
   * finds it, since creating the reference already resolved it. Inside a native image the lookup
   * answers only for a class registered for reflection: under exact reachability metadata the
   * image's missing-registration error passes through unchanged, and otherwise the lookup's {@code
   * ClassNotFoundException} is reported as an {@link IllegalStateException} naming the class.
   *
   * @throws IllegalArgumentException if the lambda is not a method reference
   * @throws IllegalStateException if the receiver class cannot be loaded
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
          // The receiver is the class a write rebuilds; answering the declaring class instead
          // would rebuild a superclass, which an abstract one cannot be.
          throw new IllegalStateException(
            "Cannot load " +
              receiverName +
              ", the class the method reference " +
              declaring.getName() +
              "::" +
              slot.get(lambda).methodName() +
              " is applied to. Inside a native image, register it for reflection.",
            e
          );
        }
      }
      slot.receiverClass = result;
    }
    return (Class<A>) result;
  }
}
