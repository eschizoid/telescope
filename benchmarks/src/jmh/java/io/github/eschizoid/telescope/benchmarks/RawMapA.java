package io.github.eschizoid.telescope.benchmarks;

import java.util.HashMap;

/** Raw container subtype — no sized constructor, because Java does not inherit constructors. */
public class RawMapA extends HashMap<String, RawUrlA> {}
