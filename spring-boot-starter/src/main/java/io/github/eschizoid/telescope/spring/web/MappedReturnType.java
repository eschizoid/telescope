package io.github.eschizoid.telescope.spring.web;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;

/**
 * A handler's return type as it reads once the value is mapped. Spring MVC picks a message
 * converter and its target type from this, so the converter writes the response type rather than
 * the declared one.
 *
 * <p>Only the two type getters change. The containing class and the method annotations come from
 * the parameter Spring built, which resolves them against the controller's bean type and the
 * interfaces it implements; scoped {@code ResponseBodyAdvice} and {@code @JsonView} read them.
 */
final class MappedReturnType extends MethodParameter {

  private final MethodParameter original;
  private final Type mappedType;

  MappedReturnType(final MethodParameter original, final Type mappedType) {
    super(original);
    this.original = original;
    this.mappedType = mappedType;
  }

  @Override
  public Class<?> getParameterType() {
    return ResolvableType.forType(mappedType).toClass();
  }

  @Override
  public Type getGenericParameterType() {
    return mappedType;
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
  public MappedReturnType clone() {
    return new MappedReturnType(original, mappedType);
  }
}
