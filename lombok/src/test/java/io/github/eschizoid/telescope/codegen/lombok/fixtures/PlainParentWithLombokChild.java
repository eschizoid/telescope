package io.github.eschizoid.telescope.codegen.lombok.fixtures;

import io.github.eschizoid.telescope.annotations.Bridge;

/**
 * Fixture: plain record parent — NO Lombok annotation — whose {@code @Bridge} target is also a
 * plain record, but whose {@code child} field type ({@link BridgedDataUser}) IS Lombok-annotated.
 *
 * <p>Exercises the recursive sub-pair deferral path: {@code BridgeProcessor} processes this parent
 * pair eagerly in round 1 (no Lombok on either parent side), but the recursive sub-pair discovery
 * into the child field's type (Lombok-annotated) must NOT push the sub-pair onto the eager queue —
 * it must route to deferredPairs and emit at {@code processingOver()}. Without that fix, the
 * sub-pair emits in round 1 with the un-patched member list and produces a no-op BRIDGE.
 */
@Bridge(PlainParentWithLombokChildDto.class)
public record PlainParentWithLombokChild(String id, BridgedDataUser child) {}
