/**
 * Mapped request and response bodies on Quarkus REST. {@link
 * io.github.eschizoid.telescope.quarkus.rest.TelescopeResponse} on a resource method names the type
 * its response body is mapped to; {@link
 * io.github.eschizoid.telescope.quarkus.rest.TelescopeRequestBody} on its body parameter names the
 * type the body is read as, and a {@link
 * io.github.eschizoid.telescope.quarkus.rest.TelescopeRequest} parameter carries the body as read
 * and as mapped. The build finds every annotated method and refuses a misconfigured one by name;
 * the mappers are resolved once, at startup.
 */
package io.github.eschizoid.telescope.quarkus.rest;
