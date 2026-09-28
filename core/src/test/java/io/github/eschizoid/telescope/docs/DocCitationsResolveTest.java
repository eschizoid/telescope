package io.github.eschizoid.telescope.docs;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every {@code path:line} reference in the documentation points inside the file it names.
 *
 * <p>An edit in another file falsifies a line number in prose without touching the prose, so
 * nothing goes red and no reader is warned. The documents carrying the most of them are the ones
 * offered as evidence for a claim, where a reference that resolves to nothing leaves a reader
 * unable to tell whether the claim moved or was never true.
 *
 * <p>A reference is resolved by its own path where that exists and by its file name otherwise,
 * since the documents cite both ways. A file name matching more than one file is reported too: it
 * resolves today by accident and stops resolving when a second file takes the name.
 */
class DocCitationsResolveTest {

  private static final Pattern CITATION = Pattern.compile("([A-Za-z0-9_./-]+\\.(?:java|md|kts)):(\\d+)(?:-(\\d+))?");

  private static Path repoRoot() {
    var dir = Path.of("").toAbsolutePath();
    while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
      dir = dir.getParent();
    }
    if (dir == null) throw new IllegalStateException("no settings.gradle.kts above " + Path.of("").toAbsolutePath());
    return dir;
  }

  private static Map<String, List<Path>> byFileName(final Path root) {
    final var out = new HashMap<String, List<Path>>();
    try (Stream<Path> all = Files.walk(root)) {
      all
        .filter(Files::isRegularFile)
        .filter(p -> !root.relativize(p).toString().contains("build/"))
        .filter(p -> !root.relativize(p).toString().startsWith("."))
        .forEach(p -> out.computeIfAbsent(p.getFileName().toString(), k -> new ArrayList<>()).add(p));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    return out;
  }

  private static long lineCount(final Path p) {
    try (Stream<String> lines = Files.lines(p)) {
      return lines.count();
    } catch (final IOException e) {
      // A file the JVM cannot decode is not a citation defect; treat it as unbounded.
      return Long.MAX_VALUE;
    }
  }

  @Test
  @DisplayName("every path:line reference in docs/ points inside the file it names")
  void everyCitationResolves() throws IOException {
    final var root = repoRoot();
    final var index = byFileName(root);
    final var problems = new ArrayList<String>();

    try (Stream<Path> docs = Files.walk(root.resolve("docs"))) {
      for (final var doc : docs.filter(p -> p.toString().endsWith(".md")).toList()) {
        var fenced = false;
        var lineNumber = 0;
        for (final var line : Files.readAllLines(doc)) {
          lineNumber++;
          if (line.stripLeading().startsWith("```")) {
            fenced = !fenced;
            continue;
          }
          // A stack frame reads exactly like a citation: `at Foo.bar(Foo.java:12)`. Inside a fence
          // the number belongs to a transcript of something that ran elsewhere, against generated
          // sources or the JDK, so it is not a reference into this repository and cannot rot.
          if (fenced) continue;

          final var matcher = CITATION.matcher(line);
          while (matcher.find()) {
            final var cited = matcher.group(1);
            final var end = Integer.parseInt(matcher.group(3) == null ? matcher.group(2) : matcher.group(3));
            final var direct = root.resolve(cited);
            final var candidates = Files.exists(direct)
              ? List.of(direct)
              : index.getOrDefault(Path.of(cited).getFileName().toString(), List.of());

            final var where = root.relativize(doc) + ":" + lineNumber + " -> " + matcher.group();
            if (candidates.isEmpty()) {
              problems.add(where + " names no file in the repository");
            } else if (candidates.size() > 1) {
              problems.add(where + " names " + candidates.size() + " files, so it resolves by accident");
            } else if (end > lineCount(candidates.getFirst())) {
              problems.add(where + " points past the end of " + root.relativize(candidates.getFirst()));
            }
          }
        }
      }
    }

    assertTrue(
      problems.isEmpty(),
      () -> problems.size() + " citation(s) do not resolve:\n  " + String.join("\n  ", problems)
    );
  }
}
