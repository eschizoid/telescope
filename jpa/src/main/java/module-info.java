/**
 * telescope-jpa: mapping JPA entities without loading what the mapping does not read. Written
 * against the Jakarta Persistence API alone, so it serves any provider that implements it.
 *
 * <p>The module exports nothing. It registers two providers with {@code telescope-core}:
 *
 * <ul>
 *   <li>a {@code ContainerCopy.Policy} that shares a same-typed container the provider reports as
 *       not loaded, rather than copying it, so neither a mapper nor a generated bridge loads a lazy
 *       collection, or throws for one outside its session;
 *   <li>a {@code ProxyResolver} that resolves a Hibernate proxy to the entity class it stands for,
 *       so a lazy association is read, merged and written as its entity.
 * </ul>
 *
 * <p>Both are inactive where what they consult is absent, so the module costs nothing in an
 * application without JPA.
 */
module io.github.eschizoid.telescope.jpa {
  requires io.github.eschizoid.telescope;
  requires static jakarta.persistence;

  provides io.github.eschizoid.telescope.conversion.ContainerCopy.Policy
    with io.github.eschizoid.telescope.jpa.LoadedContainers;

  provides io.github.eschizoid.telescope.conversion.ProxyResolver
    with io.github.eschizoid.telescope.jpa.HibernateProxies;
}
