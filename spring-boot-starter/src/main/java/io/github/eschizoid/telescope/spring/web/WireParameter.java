package io.github.eschizoid.telescope.spring.web;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Map;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * A mapped-body parameter as Spring's body reader should see it: of the wire type, carrying a
 * {@code @RequestBody} with the parameter's {@code required}. Everything else, {@code @Valid} among
 * it, comes from the parameter Spring built, so the body is read, validated and refused exactly as
 * an {@code @RequestBody} of the wire type would be.
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
    return original.getParameterAnnotations();
  }

  @Override
  @SuppressWarnings("unchecked")
  public <A extends Annotation> A getParameterAnnotation(final Class<A> annotationType) {
    return annotationType == RequestBody.class ? (A) requestBody : original.getParameterAnnotation(annotationType);
  }

  @Override
  public <A extends Annotation> boolean hasParameterAnnotation(final Class<A> annotationType) {
    return annotationType == RequestBody.class || original.hasParameterAnnotation(annotationType);
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
