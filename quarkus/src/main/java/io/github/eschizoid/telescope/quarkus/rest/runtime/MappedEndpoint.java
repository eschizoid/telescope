package io.github.eschizoid.telescope.quarkus.rest.runtime;

import io.quarkus.runtime.annotations.RecordableConstructor;

/**
 * One mapped body as the build found it: a response of a resource method annotated with {@code
 * TelescopeResponse}, or the body parameter of one carrying {@code TelescopeRequestBody} or typed
 * {@code TelescopeRequest}. The build records these for the runtime, which resolves the mapper of
 * each at startup.
 *
 * <p>For a response, {@code source} is the type the method returns values of and {@code target} the
 * annotation's {@code to}. For a request, {@code source} is the wire type and {@code target} the
 * type the parameter holds. Either of {@code source} and {@code target} is {@code null} when only
 * {@code using} supplies it; {@code using} is {@code null} when unset.
 */
public final class MappedEndpoint {

  private final String methodId;
  private final String where;
  private final String annotation;
  private final boolean request;
  private final ValueShape shape;
  private final Class<?> source;
  private final Class<?> target;
  private final Class<?> using;
  private final boolean holder;
  private final boolean required;
  private final boolean validated;
  private final String validationPath;

  /**
   * @param methodId the method's id as Quarkus REST keys a resource method: resource class, name
   *     and erased parameter types
   * @param where the method, and for a request the parameter, as a refusal names it
   * @param annotation the annotation's name as a refusal names it, such as {@code
   *     "@TelescopeResponse"}
   * @param request whether this is a request body rather than a response
   * @param shape where the values sit in the body
   * @param source the type the mapping reads, or {@code null} when {@code using} supplies it
   * @param target the type the mapping writes, or {@code null} when {@code using} supplies it
   * @param using the projection to map through, or {@code null}
   * @param holder whether the parameter is a {@code TelescopeRequest}
   * @param required whether a request body is required
   * @param validated whether the body is validated as read
   * @param validationPath the property path a violation of the body as read starts with, as Quarkus
   *     names a resource method's parameter, or {@code null} for a response
   */
  @RecordableConstructor
  public MappedEndpoint(
    final String methodId,
    final String where,
    final String annotation,
    final boolean request,
    final ValueShape shape,
    final Class<?> source,
    final Class<?> target,
    final Class<?> using,
    final boolean holder,
    final boolean required,
    final boolean validated,
    final String validationPath
  ) {
    this.methodId = methodId;
    this.where = where;
    this.annotation = annotation;
    this.request = request;
    this.shape = shape;
    this.source = source;
    this.target = target;
    this.using = using;
    this.holder = holder;
    this.required = required;
    this.validated = validated;
    this.validationPath = validationPath;
  }

  /**
   * @return the method's id as Quarkus REST keys it
   */
  public String getMethodId() {
    return methodId;
  }

  /**
   * @return the method, and for a request the parameter, as a refusal names it
   */
  public String getWhere() {
    return where;
  }

  /**
   * @return the annotation's name as a refusal names it
   */
  public String getAnnotation() {
    return annotation;
  }

  /**
   * @return whether this is a request body
   */
  public boolean isRequest() {
    return request;
  }

  /**
   * @return where the values sit in the body
   */
  public ValueShape getShape() {
    return shape;
  }

  /**
   * @return the type the mapping reads, or {@code null}
   */
  public Class<?> getSource() {
    return source;
  }

  /**
   * @return the type the mapping writes, or {@code null}
   */
  public Class<?> getTarget() {
    return target;
  }

  /**
   * @return the projection to map through, or {@code null}
   */
  public Class<?> getUsing() {
    return using;
  }

  /**
   * @return whether the parameter is a {@code TelescopeRequest}
   */
  public boolean isHolder() {
    return holder;
  }

  /**
   * @return whether a request body is required
   */
  public boolean isRequired() {
    return required;
  }

  /**
   * @return whether the body is validated as read
   */
  public boolean isValidated() {
    return validated;
  }

  /**
   * @return the property path a violation of the body as read starts with, or {@code null}
   */
  public String getValidationPath() {
    return validationPath;
  }
}
