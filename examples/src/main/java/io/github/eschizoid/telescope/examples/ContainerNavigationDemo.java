package io.github.eschizoid.telescope.examples;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.Telescope.ListPath;
import io.github.eschizoid.telescope.Telescope.MapPath;
import io.github.eschizoid.telescope.Telescope.OptionalPath;
import io.github.eschizoid.telescope.Telescope.SetPath;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Exercises the four typed container subclasses: {@code list / setField / mapField / optional} +
 * their typed terminals ({@code each / each / values / present}). All are compile-checked — no
 * runtime container dispatch.
 */
final class ContainerNavigationDemo {

  private ContainerNavigationDemo() {}

  static void main() {
    run();
  }

  record Tag(String name) {}

  record TagList(String owner, List<Tag> tags) {}

  record TagSet(String owner, Set<Tag> tags) {}

  record TagMap(String owner, Map<String, Tag> tagsByKey) {}

  record TagOptional(String owner, Optional<Tag> tag) {}

  static void run() {
    listPathDemo();
    setPathDemo();
    mapPathDemo();
    optionalPathDemo();
  }

  // .list(Accessor) returns ListPath; .each() steps into the elements.
  private static void listPathDemo() {
    final ListPath<TagList, Tag> tags = Telescope.of(TagList.class).list(TagList::tags);
    final var src = new TagList("alice", List.of(new Tag("a"), new Tag("b"), new Tag("c")));
    final var upper = tags.each().update(src, t -> new Tag(t.name().toUpperCase()));
    System.out.println("[list/each]    before    : " + src);
    System.out.println("[list/each]    after     : " + upper);
    System.out.println("[list/each]    toList    : " + tags.each().toList(src));
  }

  // .setField(Accessor) returns SetPath; renamed from .set in 1.0 to disambiguate from set(S, A).
  private static void setPathDemo() {
    final SetPath<TagSet, Tag> tags = Telescope.of(TagSet.class).setField(TagSet::tags);
    final var src = new TagSet("alice", new LinkedHashSet<>(List.of(new Tag("a"), new Tag("b"))));
    final var upper = tags.each().update(src, t -> new Tag(t.name().toUpperCase()));
    System.out.println("[setField/each] before   : " + src);
    System.out.println("[setField/each] after    : " + upper);
  }

  // .mapField(Accessor) returns MapPath; .values() updates values, keys preserved.
  private static void mapPathDemo() {
    final MapPath<TagMap, String, Tag> tagsByKey = Telescope.of(TagMap.class).mapField(TagMap::tagsByKey);
    final var src = new TagMap("alice", Map.of("a", new Tag("x"), "b", new Tag("y")));
    final var upper = tagsByKey.values().update(src, t -> new Tag(t.name().toUpperCase()));
    System.out.println("[mapField/values] before : " + src);
    System.out.println("[mapField/values] after  : " + upper);
  }

  // .optional(Accessor) returns OptionalPath; .present() is Affine — empty is a no-op.
  private static void optionalPathDemo() {
    final OptionalPath<TagOptional, Tag> tag = Telescope.of(TagOptional.class).optional(TagOptional::tag);

    final var withTag = new TagOptional("alice", Optional.of(new Tag("a")));
    final var withoutTag = new TagOptional("bob", Optional.empty());

    final var present = tag.present().update(withTag, t -> new Tag(t.name().toUpperCase()));
    final var absent = tag.present().update(withoutTag, t -> new Tag(t.name().toUpperCase()));

    System.out.println("[optional/present] present input  : " + withTag);
    System.out.println("[optional/present] present output : " + present);
    System.out.println("[optional/present] empty input    : " + withoutTag);
    System.out.println("[optional/present] empty output   : " + absent + " (no-op)");
  }
}
