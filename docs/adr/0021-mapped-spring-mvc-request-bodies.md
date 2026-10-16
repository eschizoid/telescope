# ADR-0021: Mapped Spring MVC request bodies

**Status:** Accepted · **Date:** 2026-10-12

## Context

ADR-0020 removed the mapper call from the end of a REST handler. The start of a write handler still has one: the body
arrives as a wire DTO, and the first line maps it to the type the service takes. Some handlers also need the body as
sent, to log or audit a field the domain type does not carry.

## Decision

1. **The parameter declares the type the handler works with, and the annotation names the wire type.**
   `@TelescopeRequestBody(from = CreateUserRestRequestDtoV1.class) UserDto user`, the mirror of ADR-0020's `to`. The
   parameter may be `T`, `List<T>`, `Collection<T>` or `Set<T>`.
2. **A `TelescopeRequest<S, T>` parameter carries both.** Its type arguments name the wire and mapped types, so it needs
   no annotation; `@TelescopeRequestBody` on it carries only `using` and `required`, and `from` is refused.
3. **The body is read by Spring's own `@RequestBody` processor, as the wire type.** A parameter view reports the wire
   type and a `@RequestBody` with the parameter's `required`, and keeps every other annotation of the parameter. Content
   negotiation, `RequestBodyAdvice`, the missing-body check and `@Valid` therefore run as they do for `@RequestBody`, on
   the body as read; a constraint violation is the usual `400` before anything is mapped.
4. **The mapper is resolved at startup by the same lookup as responses**, through one `MapperLookup`: `using`, then a
   projection, then a `Mapper` bean, then `Telescope.mapperForward`. Every refusal fails startup with the parameter
   named.
5. **springdoc documents the wire type as the request body**, and drops the query parameter it would otherwise derive
   from the handler argument, which carries no `@RequestBody`.

## Consequences

- A write endpoint names no mapper, and a handler that needs the original body takes a `TelescopeRequest` instead.
- Validation stays on the wire DTO, where the constraints are written.
- The resolver sits first among the adapter's argument resolvers, ahead of the catch-all that would bind a
  `TelescopeRequest` as a model attribute.

## Alternatives considered

- **A `ResponseBodyAdvice`-style `RequestBodyAdvice` that swaps the read value.** Rejected: it runs after the converter
  has been chosen from the parameter's declared type, which is the domain type.
- **Validating the mapped value.** Rejected as the default: constraints live on the wire DTO, and a `400` that names a
  domain field the client never sent would confuse it.
