package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.enumTo;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link io.github.eschizoid.telescope.mapping.Mapping#enumTo} — the convenience factory for
 * by-name enum correspondence. Validates the happy-path round-trip + the exhaustiveness diagnostic
 * fires at factory time with a clear message naming the missing constants on either side.
 */
class MappingEnumToTest {

  enum EntityStatus {
    ACTIVE,
    SUSPENDED,
    CLOSED,
  }

  enum DtoStatus {
    ACTIVE,
    SUSPENDED,
    CLOSED,
  }

  enum DtoStatusMissingClosed {
    ACTIVE,
    SUSPENDED,
  }

  enum DtoStatusExtraPending {
    ACTIVE,
    SUSPENDED,
    CLOSED,
    PENDING,
  }

  record UserEntity(String id, EntityStatus status) {}

  record UserDto(String id, DtoStatus status) {}

  record UserDtoMissing(String id, DtoStatusMissingClosed status) {}

  record UserDtoExtra(String id, DtoStatusExtraPending status) {}

  @Nested
  @DisplayName("Happy path — enums with identical constants line up by name")
  class HappyPath {

    @Test
    @DisplayName("forward + backward round-trip via Enum.valueOf on both sides")
    void roundTrip() {
      final var mapper = Telescope.mapper(
        UserEntity.class,
        UserDto.class,
        enumTo(UserEntity::status, UserDto::status, EntityStatus.class, DtoStatus.class)
      );
      final var entity = new UserEntity("u1", EntityStatus.SUSPENDED);
      final var dto = mapper.forward(entity);
      assertEquals(DtoStatus.SUSPENDED, dto.status());
      assertEquals(entity, mapper.backward(dto));
    }

    @Test
    @DisplayName("Composes with other Mapping rows on the same pair")
    void composesWithOtherRows() {
      final var mapper = Telescope.mapper(
        UserEntity.class,
        UserDto.class,
        to(UserEntity::id, UserDto::id),
        enumTo(UserEntity::status, UserDto::status, EntityStatus.class, DtoStatus.class)
      );
      assertEquals(DtoStatus.ACTIVE, mapper.forward(new UserEntity("u1", EntityStatus.ACTIVE)).status());
    }
  }

  @Nested
  @DisplayName("Exhaustiveness validation at factory time")
  class Exhaustiveness {

    @Test
    @DisplayName("Source constant missing on target → IAE naming the missing constant")
    void missingOnTarget() {
      final var ex = assertThrows(IllegalArgumentException.class, () ->
        enumTo(UserEntity::status, UserDtoMissing::status, EntityStatus.class, DtoStatusMissingClosed.class)
      );
      assertTrue(ex.getMessage().contains("CLOSED"), () -> ex.getMessage());
      assertTrue(ex.getMessage().contains("missing on target"), () -> ex.getMessage());
      assertTrue(ex.getMessage().contains("DtoStatusMissingClosed"), () -> ex.getMessage());
    }

    @Test
    @DisplayName("Target constant missing on source → IAE naming the missing constant")
    void missingOnSource() {
      final var ex = assertThrows(IllegalArgumentException.class, () ->
        enumTo(UserEntity::status, UserDtoExtra::status, EntityStatus.class, DtoStatusExtraPending.class)
      );
      assertTrue(ex.getMessage().contains("PENDING"), () -> ex.getMessage());
      assertTrue(ex.getMessage().contains("missing on source"), () -> ex.getMessage());
      assertTrue(ex.getMessage().contains("EntityStatus"), () -> ex.getMessage());
    }

    @Test
    @DisplayName("Message points the user at the explicit-Function escape hatch for asymmetric enums")
    void messageNamesEscapeHatch() {
      final var ex = assertThrows(IllegalArgumentException.class, () ->
        enumTo(UserEntity::status, UserDtoExtra::status, EntityStatus.class, DtoStatusExtraPending.class)
      );
      assertTrue(
        ex.getMessage().contains("Mapping.to(src, tgt, fwd, bwd)"),
        () -> "Diagnostic should point at the typed-transform escape hatch; saw: " + ex.getMessage()
      );
    }
  }
}
