package io.github.telescopeorganization.telescope.internal;

import java.io.Serializable;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodType;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Executable;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.TypeVariable;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;

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
    private volatile Class<?> instantiatedReceiver;

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
      final var serialized = serializedOf(lambda);
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

  /** The {@code SerializedLambda} a Serializable lambda or method reference writes itself as. */
  private static SerializedLambda serializedOf(final Serializable lambda) throws ReflectiveOperationException {
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
    return (SerializedLambda) writeReplace.invoke(lambda);
  }

  /**
   * The class a Serializable lambda or method reference taking one value takes it as, and how a
   * message names the function, as {@link #argumentOf} reads them.
   *
   * @param taken the class the call site's instantiation types the argument as: an unbound instance
   *     method's receiver, the parameter of a static method, a constructor or a held receiver's
   *     method as inference narrowed it, or a lambda's declared parameter
   * @param name {@code Owner::method} or {@code Owner::new}, naming an unbound instance method by
   *     the class it is applied to and any other reference by the class declaring its method; null
   *     for a lambda
   */
  public record Argument(Class<?> taken, String name) {}

  /**
   * What a function written as a lambda or method reference whose functional interface is
   * Serializable takes, read from its {@code SerializedLambda}; null for any other function, and
   * for one whose argument is primitive. The class is loaded by name through the function's class
   * loader.
   *
   * @throws IllegalStateException if the class cannot be loaded
   */
  public static Argument argumentOf(final Serializable function) {
    if (!function.getClass().isHidden()) return null;
    final SerializedLambda serialized;
    try {
      serialized = serializedOf(function);
    } catch (final ReflectiveOperationException e) {
      return null;
    }
    final var takenName = receiverNameOf(serialized.getInstantiatedMethodType());
    if (takenName == null) return null;
    final Class<?> taken;
    try {
      taken = Class.forName(takenName, false, function.getClass().getClassLoader());
    } catch (final ClassNotFoundException e) {
      throw new IllegalStateException("Cannot load " + takenName + ", the class a function is typed to take.", e);
    }
    final var method = serialized.getImplMethodName();
    if (method.startsWith("lambda$")) return new Argument(taken, null);
    final var kind = serialized.getImplMethodKind();
    final var unbound =
      (kind == MethodHandleInfo.REF_invokeVirtual || kind == MethodHandleInfo.REF_invokeInterface) &&
      serialized.getCapturedArgCount() == 0;
    final var implClass = serialized.getImplClass();
    final var owner = unbound
      ? taken.getSimpleName()
      : implClass.substring(Math.max(implClass.lastIndexOf('/'), implClass.lastIndexOf('$')) + 1);
    return new Argument(taken, owner + "::" + (kind == MethodHandleInfo.REF_newInvokeSpecial ? "new" : method));
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
   * What a Serializable method reference converting one value takes and gives, as {@link
   * #conversionOf} reads it.
   *
   * @param from the class the reference accepts: what the lambda javac builds for it casts its
   *     argument to, which is narrower than the method's parameter where the call site's inference
   *     narrowed it
   * @param to the class the method returns, or the bound of the type variable it returns
   * @param resultInferred whether the method returns its own type variable, whose class the call
   *     site infers from where the result goes
   * @param declared the class the method itself takes, its parameter or declaring class, which
   *     {@code from} narrows
   */
  public record Conversion(Class<?> from, Class<?> to, boolean resultInferred, Class<?> declared) {}

  /**
   * What a Serializable method reference converting one value takes and gives. It takes a static
   * method's or a constructor's one parameter, or an unbound instance method's receiver, as the
   * reference's instantiation types it, and gives the method's return, or the constructed class; a
   * return a generic supertype types is the class the receiver gives it. A parameter or return
   * typed by a type variable is its bound, and a primitive is its wrapper, so {@code static <T
   * extends Line> long weigh(T line)} takes {@code Line} and gives {@code Long}. The classes are
   * loaded by name through the lambda's class loader, as {@link #receiverClassOf} loads a receiver.
   *
   * @throws IllegalArgumentException if the lambda is not a method reference, the method does not
   *     take exactly one value, or it returns a type variable of a class that nothing narrows
   * @throws IllegalStateException if a class cannot be loaded
   */
  public static Conversion conversionOf(final Serializable lambda) {
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
    final var unbound = takesReceiver(lambda);
    final Class<?> from;
    final Class<?> declaredClass;
    // The lambda javac builds for the reference casts its argument to the instantiation's
    // parameter, or receiver, which the call site's inference may have narrowed below the method's.
    if (unbound && type.parameterCount() == 0) {
      from = receiverClassOf(lambda);
      declaredClass = implClassOf(lambda);
    } else if (!unbound && type.parameterCount() == 1) {
      from = instantiatedParameterOf(lambda, type.parameterType(0));
      declaredClass = type.parameterType(0);
    } else throw new IllegalArgumentException(
      "Expected a method reference converting one value, such as Hw::forward; " +
        metadata.implName() +
        "::" +
        metadata.methodName() +
        " takes " +
        type.parameterCount() +
        (unbound ? " besides its receiver" : "")
    );
    final var takes = MethodType.methodType(from).wrap().returnType();
    final var declared = MethodType.methodType(declaredClass).wrap().returnType();
    if (metadata.implKind() == MethodHandleInfo.REF_newInvokeSpecial) {
      return new Conversion(takes, implClassOf(lambda), false, declared);
    }
    final var erased = type.returnType();
    final var narrowed = narrowedReturnOf(lambda, erased);
    if (narrowed != null) return new Conversion(takes, narrowed, false, declared);
    final var gives = MethodType.methodType(erased).wrap().returnType();
    final var variable = returnedVariable(implClassOf(lambda), metadata.methodName(), type);
    if (variable != null && variable.getGenericDeclaration() instanceof Method) {
      return new Conversion(takes, gives, true, declared);
    }
    if (variable != null && erased == Object.class) {
      throw new IllegalArgumentException(
        "Cannot read the class " +
          metadata.implName() +
          "::" +
          metadata.methodName() +
          " returns, which a type variable of its class names; reference a method whose return names" +
          " the class, such as a static one"
      );
    }
    return new Conversion(takes, gives, false, declared);
  }

  /**
   * The class the instantiation of {@code lambda} types its one argument as, where that is narrower
   * than {@code declared}, the method's parameter; {@code declared} otherwise.
   */
  private static Class<?> instantiatedParameterOf(final Serializable lambda, final Class<?> declared) {
    final var name = CACHE.get(lambda.getClass()).get(lambda).receiverName();
    if (declared.isPrimitive() || name == null || name.equals(declared.getName())) return declared;
    try {
      final var instantiated = Class.forName(name, false, lambda.getClass().getClassLoader());
      return declared.isAssignableFrom(instantiated) ? instantiated : declared;
    } catch (final ClassNotFoundException e) {
      throw new IllegalStateException(
        "Cannot load " +
          name +
          ", the class a method reference is typed to take. Inside a native image," +
          " register it for reflection.",
        e
      );
    }
  }

  /**
   * The form taking one {@code argument} of the method or constructor a Serializable reference
   * names, where the reference calls it with no argument, or null when there is none, when the
   * reference takes an argument, or when it is a lambda, which names no method a caller chose by
   * name. A reference javac compiles into a lambda method of its own, as it does one through {@code
   * super} or one to a protected method of a class in another package, records that lambda method
   * rather than the method it calls, and reads as a lambda.
   *
   * <p>A form is a method of the same name, static when the referenced one is and an instance
   * method when it is, or a constructor of the same class; not private; whose one parameter {@code
   * argument} is an instance of, a primitive parameter compared as its wrapper. A parameter of
   * class {@code Object}, which a type variable with no bound erases to, takes any value rather
   * than {@code argument}, so it is no such form. The search starts at the class the reference
   * names the method through, then takes each class and interface it extends, superclass before
   * interfaces, and the first that declares a form gives it; of several there, the one whose
   * parameter's class name sorts first.
   *
   * <p>A reference bound to a receiver names the method through the type of the expression written
   * before {@code ::}, which the class defined for the reference holds the receiver as. A static
   * reference names it through the class declaring the method: the class file records a handle on
   * that class and no trace of the class written before {@code ::}, so a form only a subclass
   * written there declares is not found, and {@code Sub::label} with {@code label()} on {@code
   * Base} and {@code label(Person)} on {@code Sub} answers null.
   *
   * <p>Java binds such a reference to the overload taking no argument whenever one overload takes a
   * supplier and another a function, so the form returned here is the one the call did not reach.
   */
  public static Executable oneArgumentFormTaking(final Serializable reference, final Class<?> argument) {
    final Metadata metadata;
    try {
      metadata = CACHE.get(reference.getClass()).get(reference);
    } catch (final IllegalArgumentException notAMethodReference) {
      return null;
    }
    final var kind = metadata.implKind();
    final var bound = (kind == MethodHandleInfo.REF_invokeVirtual || kind == MethodHandleInfo.REF_invokeInterface);
    final var given = MethodType.fromMethodDescriptorString(
      metadata.implSignature(),
      reference.getClass().getClassLoader()
    ).parameterCount();
    if (given != 0 || (bound && metadata.capturedArgs() != 1)) return null;
    final Class<?> owner = implClassOf(reference);
    if (kind == MethodHandleInfo.REF_newInvokeSpecial) {
      return firstByParameter(Arrays.asList(owner.getDeclaredConstructors()), argument, null, false);
    }
    final var wantStatic = kind == MethodHandleInfo.REF_invokeStatic;
    final var pending = new ArrayDeque<Class<?>>();
    pending.add(bound ? receiverHeldAs(reference, owner) : owner);
    while (!pending.isEmpty()) {
      final var type = pending.poll();
      final var form = firstByParameter(
        Arrays.asList(type.getDeclaredMethods()),
        argument,
        metadata.methodName(),
        wantStatic
      );
      if (form != null) return form;
      if (type.getSuperclass() != null) pending.add(type.getSuperclass());
      pending.addAll(Arrays.asList(type.getInterfaces()));
    }
    return null;
  }

  /**
   * The class a reference bound to a receiver holds it as, where that is {@code declaring}, the
   * class declaring the referenced method, or a subtype of it; {@code declaring} otherwise. The
   * class the JDK's metafactory defines for a bound reference keeps the receiver in its one
   * instance field, typed as the type of the expression written before {@code ::} rather than as
   * the class of the value it holds, so {@code base::label} on a {@code Base} variable holding a
   * {@code Sub} answers {@code Base}, the type Java resolved the name against.
   */
  private static Class<?> receiverHeldAs(final Serializable reference, final Class<?> declaring) {
    Class<?> held = null;
    for (final var field : reference.getClass().getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers())) continue;
      if (held != null) return declaring;
      held = field.getType();
    }
    return held != null && declaring.isAssignableFrom(held) ? held : declaring;
  }

  /**
   * Of {@code candidates} named {@code name}, or of any name when it is null, static exactly when
   * {@code wantStatic} for a method, the one not private whose one parameter, not {@code Object},
   * {@code argument} is an instance of, whose parameter's class name sorts first; or null.
   */
  private static Executable firstByParameter(
    final List<? extends Executable> candidates,
    final Class<?> argument,
    final String name,
    final boolean wantStatic
  ) {
    Executable first = null;
    for (final var candidate : candidates) {
      if (candidate.getParameterCount() != 1 || Modifier.isPrivate(candidate.getModifiers())) continue;
      if (
        name != null && (!candidate.getName().equals(name) || Modifier.isStatic(candidate.getModifiers()) != wantStatic)
      ) {
        continue;
      }
      final var declared = candidate.getParameterTypes()[0];
      final var parameter = MethodType.methodType(declared).wrap().returnType();
      if (parameter == Object.class || !parameter.isAssignableFrom(argument)) continue;
      if (first == null || sortName(declared).compareTo(sortName(first.getParameterTypes()[0])) < 0) first = candidate;
    }
    return first;
  }

  /** The name a class sorts by: its canonical name, or its binary name where it has none. */
  private static String sortName(final Class<?> type) {
    final var canonical = type.getCanonicalName();
    return canonical == null ? type.getName() : canonical;
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
   * The type variable the method {@code name} of {@code owner}, or of a class or interface it
   * extends, declares its return as, or null when it returns no type variable.
   */
  private static TypeVariable<?> returnedVariable(final Class<?> owner, final String name, final MethodType type) {
    if (owner == null) return null;
    for (final var method : owner.getDeclaredMethods()) {
      if (
        method.getName().equals(name) &&
        Arrays.equals(method.getParameterTypes(), type.parameterArray()) &&
        method.getGenericReturnType() instanceof TypeVariable<?> variable
      ) {
        return variable;
      }
    }
    final var inherited = returnedVariable(owner.getSuperclass(), name, type);
    if (inherited != null) return inherited;
    for (final var implemented : owner.getInterfaces()) {
      final var found = returnedVariable(implemented, name, type);
      if (found != null) return found;
    }
    return null;
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
   * The class the instantiation of a Serializable method reference types its first argument as,
   * read from {@link SerializedLambda#getInstantiatedMethodType()}. For an unbound reference this
   * is the receiver as the use site types it: the class written before {@code ::}, or the subtype
   * inference gave it there. A getter {@code Sub} inherits from {@code Base}, written {@code
   * Sub::getName}, answers {@code Sub}, while {@link #implClassOf} answers {@code Base}. Null when
   * that argument is a primitive or an array, which names no class.
   *
   * <p>The class is loaded by name through the lambda's class loader, as {@link #receiverClassOf}
   * loads a receiver, so inside a native image it answers only for a class registered for
   * reflection.
   *
   * @throws IllegalArgumentException if the lambda is not a method reference
   * @throws IllegalStateException if the class cannot be loaded
   */
  public static Class<?> instantiatedReceiverOf(final Serializable lambda) {
    final var slot = CACHE.get(lambda.getClass());
    final var cached = slot.instantiatedReceiver;
    if (cached != null) return cached;
    final var metadata = slot.get(lambda);
    final var name = metadata.receiverName();
    if (name == null) return null;
    try {
      final var receiver = Class.forName(name, false, lambda.getClass().getClassLoader());
      slot.instantiatedReceiver = receiver;
      return receiver;
    } catch (final ClassNotFoundException e) {
      throw new IllegalStateException(
        "Cannot load " +
          name +
          ", the class the method reference " +
          metadata.implName() +
          "::" +
          metadata.methodName() +
          " is applied to. Inside a native image, register it for reflection.",
        e
      );
    }
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
