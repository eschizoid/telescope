package io.github.eschizoid.telescope.quarkus.rest;

import io.github.eschizoid.telescope.inject.TelescopeProjection;
import jakarta.ws.rs.NameBinding;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Maps a resource method's response body from the type the method returns to {@link #to()}. Quarkus
 * REST writes the mapped value; a Java caller of the method still receives what the method
 * returned.
 *
 * <pre>{@code
 * @GET
 * @Path("/{number}")
 * @TelescopeResponse(to = OrderDto.class)
 * public Order get(@PathParam("number") String number) { return desk.get(number); }
 * }</pre>
 *
 * <p>The method returns {@code S}, {@code List<S>}, {@code Collection<S>}, {@code Set<S>}, {@code
 * Optional<S>}, a {@code RestResponse} of one of those, or a {@code Uni} or {@code CompletionStage}
 * of any of these, where {@code S} is the type the mapper reads. The build refuses any other return
 * type with the method named. The mapper for the pair is resolved once, at startup: a {@link
 * TelescopeProjection} bean for the pair, then a {@code Mapper} bean in the {@code
 * TelescopeMapperRegistry}, then a generated {@code @Bridge} or the same-name forward mapping. Two
 * candidates fail startup unless {@link #using()} picks one.
 */
@Documented
@NameBinding
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TelescopeResponse {
  /**
   * The response type. {@code Void.class} means unset, in which case {@link #using()} supplies it.
   *
   * @return the type the response body is mapped to
   */
  Class<?> to() default Void.class;

  /**
   * The projection to map through, chosen over any other mapper for the pair. {@code
   * TelescopeProjection.class} means unset. The bound is raw so the interface itself can serve as
   * that sentinel.
   *
   * @return the projection interface to map through
   */
  @SuppressWarnings("rawtypes")
  Class<? extends TelescopeProjection> using() default TelescopeProjection.class;
}
