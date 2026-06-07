package io.github.eschizoid.telescope.examples;

import io.github.eschizoid.telescope.Telescope;
import java.util.List;
import java.util.function.Function;

/**
 * Exercises the {@code Telescope.from(A).to(B).using(fwd, back)} Iso factory and its composition
 * with longer paths via {@code .then(...)}. The Iso is bidirectional, so {@code read} and {@code
 * update} both round-trip through both directions of the conversion.
 */
final class ConversionDemo {

  private ConversionDemo() {}

  static void main() {
    run();
  }

  record UserEntity(String id, String email, String name) {}

  record UserDto(String id, String email, String name) {}

  record EntityPage(List<UserEntity> items, int total) {}

  static void run() {
    basicRoundTrip();
    composesIntoLongerPath();
  }

  private static void basicRoundTrip() {
    final Function<UserEntity, UserDto> fwd = e -> new UserDto(e.id(), e.email(), e.name());
    final Function<UserDto, UserEntity> back = d -> new UserEntity(d.id(), d.email(), d.name());

    final var conv = Telescope.from(UserEntity.class).to(UserDto.class).using(fwd, back);
    final var entity = new UserEntity("u1", "ALICE@X", "Alice");

    System.out.println("[from/to/using] read fwd     : " + conv.read(entity));

    // update through the iso: forward to DTO, apply fn, back to entity.
    final var lowered = conv.update(entity, dto -> new UserDto(dto.id(), dto.email().toLowerCase(), dto.name()));
    System.out.println("[from/to/using] round-trip   : " + lowered);
  }

  // Compose the iso into a longer path: walk into a list of entities, view each as a DTO, focus
  // the email — all in one chain.
  private static void composesIntoLongerPath() {
    final Function<UserEntity, UserDto> fwd = e -> new UserDto(e.id(), e.email(), e.name());
    final Function<UserDto, UserEntity> back = d -> new UserEntity(d.id(), d.email(), d.name());

    final var asDto = Telescope.from(UserEntity.class).to(UserDto.class).using(fwd, back);
    final var emailsViaDto = Telescope.of(EntityPage.class).each(EntityPage::items).then(asDto).field(UserDto::email);

    final var page = new EntityPage(
      List.of(new UserEntity("u1", "ALICE@X", "Alice"), new UserEntity("u2", "BOB@X", "Bob")),
      2
    );

    System.out.println("[then(iso)/field] read emails : " + emailsViaDto.toList(page));
    final var normalized = emailsViaDto.update(page, String::toLowerCase);
    System.out.println("[then(iso)/field] update      : " + normalized);
  }
}
