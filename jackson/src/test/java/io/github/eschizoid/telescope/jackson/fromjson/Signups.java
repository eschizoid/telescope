package io.github.eschizoid.telescope.jackson.fromjson;

import io.github.eschizoid.telescope.jackson.FromJson;
import java.util.List;

/** A list of records with a required key each. */
@FromJson
public record Signups(List<Signed> signups) {}
