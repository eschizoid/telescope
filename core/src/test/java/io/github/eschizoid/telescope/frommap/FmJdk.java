package io.github.eschizoid.telescope.frommap;

import io.github.eschizoid.telescope.annotations.FromMap;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * JDK value types carried as Strings — Instant/UUID/BigDecimal/LocalDate via their String
 * factories.
 */
@FromMap
public record FmJdk(Instant ts, UUID id, BigDecimal amount, LocalDate day) {}
