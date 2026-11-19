package io.github.eschizoid.telescope.jackson.fromjson;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.eschizoid.telescope.jackson.FromJson;

/** A record whose {@code @JsonIgnore(false)} ignores nothing, and whose alias is its own name. */
@FromJson
public record NotIgnored(@JsonIgnore(false) String a, @JsonAlias({ "b", "x" }) String b) {}
