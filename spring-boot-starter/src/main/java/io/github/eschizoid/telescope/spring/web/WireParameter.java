package io.github.eschizoid.telescope.spring.web;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Map;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.validation.annotation.ValidationAnnotationUtils;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * A mapped-body parameter as Spring's body reader should see it: of the wire type, carrying a
 * {@code @RequestBody} with the parameter's {@code required}, so the body is read and refused
 * exactly as an {@code @RequestBody} of the wire type would be. Validation annotations are left
 * out: when method validation applies to the handler, Spring takes the validator off the binder of
 * any {@code @Valid} parameter, so {@link TelescopeRequestBodyResolver} validates the body as read
 * with a binder of its own.
 */
final class WireParameter extends MethodParameter {

  private final MethodParameter original;
  private final Type wireType;
  private final Class<?> wireClass;
  private final boolean required;
  private final RequestBody requestBody;

  WireParameter(final MethodParameter original, final Type wireType, final Class<?> wireClass, final boolean required) {
    super(original);
    this.original = original;
    this.wireType = wireType;
    this.wireClass = wireClass;
    this.required = required;
    this.requestBody = MergedAnnotation.of(RequestBody.class, Map.of("required", required)).synthesize();
  }

  @Override
  public Class<?> getParameterType() {
    return wireClass;
  }

  @Override
  public Type getGenericParameterType() {
    return wireType;
  }

  @Override
  public Class<?> getNestedParameterType() {
    return wireClass;
  }

  @Override
  public Type getNestedGenericParameterType() {
    return wireType;
  }

  @Override
  public Annotation[] getParameterAnnotations() {
    return Arrays.stream(original.getParameterAnnotations())
      .filter(annotation -> ValidationAnnotationUtils.determineValidationHints(annotation) == null)
      .toArray(Annotation[]::new);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <A extends Annotation> A getParameterAnnotation(final Class<A> annotationType) {
    if (annotationType == RequestBody.class) return (A) requestBody;
    for (final var annotation : getParameterAnnotations())
      if (annotationType.isInstance(annotation)) return (A) annotation;
    return null;
  }

  @Override
  public <A extends Annotation> boolean hasParameterAnnotation(final Class<A> annotationType) {
    return getParameterAnnotation(annotationType) != null;
  }

  @Override
  public Class<?> getContainingClass() {
    return original.getContainingClass();
  }

  @Override
  public Annotation[] getMethodAnnotations() {
    return original.getMethodAnnotations();
  }

  @Override
  public <A extends Annotation> A getMethodAnnotation(final Class<A> annotationType) {
    return original.getMethodAnnotation(annotationType);
  }

  @Override
  public <A extends Annotation> boolean hasMethodAnnotation(final Class<A> annotationType) {
    return original.hasMethodAnnotation(annotationType);
  }

  @Override
  public WireParameter clone() {
    return new WireParameter(original, wireType, wireClass, required);
  }
}
