package io.github.eschizoid.telescope.spring.web;

/**
 * A request body as it was read and as it was mapped, for a handler that needs both, such as one
 * that logs a field only the wire type carries.
 *
 * <pre>{@code
 * @PostMapping("/api/v1/users")
 * UserDto create(@Valid TelescopeRequest<CreateUserRequest, UserDto> request) {
 *   log.info("client {}", request.original().clientRef());
 *   return service.create(request.mapped());
 * }
 * }</pre>
 *
 * @param original the body as read, of the wire type
 * @param mapped the body mapped to the type the handler works with
 * @param <S> the wire type
 * @param <T> the mapped type
 */
public record TelescopeRequest<S, T>(S original, T mapped) {}
