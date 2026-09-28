package io.github.eschizoid.telescope.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Every {@code path:line} reference in the documentation points inside the file it names.
 *
 * <p>An edit in another file falsifies a line number in prose without touching the prose, so
 * nothing goes red and no reader is warned. The documents carrying the most of them are the ones
 * offered as evidence for a claim, where a reference that resolves to nothing leaves a reader
 * unable to tell whether the claim moved or was never true.
 *
 * <p>What this checks is that the reference lands inside the file, not that the lines it lands on
 * still say what the sentence claims. A reference that has drifted by fifty lines within a long
 * file stays green here, and only a reader can catch it.
 */
class DocCitationsResolveTest {

  /** What the repository looks like to the scan: which files exist, and how long each one is. */
  interface Repo {
    /** Every tracked path, repository-relative and slash-separated. */
    List<String> paths();

    /** Lines in this tracked path. */
    long lineCount(String path);
  }

  private static final Pattern CITATION = Pattern.compile("([A-Za-z0-9_./-]+\\.(?:java|md|kts)):(\\d+)(?:-(\\d+))?");

  /** A fence opens on three or more backticks or tildes, and closes on no fewer of the same one. */
  private static final Pattern FENCE = Pattern.compile("(`{3,}|~{3,}).*");

  /**
   * Every reference in one document that does not resolve, each named with the line it sits on.
   *
   * <p>A reference carrying a directory is resolved by that path alone. Falling back to the file
   * name would accept any path at all, since a name is unique across this repository and the
   * fallback would always rescue it, leaving a renamed directory to print a path no reader can
   * open. A reference that is only a file name has nothing else to resolve by, and is ambiguous
   * rather than resolved when two files answer to it.
   *
   * <p>A reference inside a fenced block is skipped: there it is a frame from a transcript of
   * something that ran against generated sources or the JDK, and names no file here. An indented
   * block is not skipped, because indentation alone does not distinguish one from a continued list
   * item.
   */
  static List<String> unresolved(final String docPath, final String text, final Repo repo) {
    final var byName = new HashMap<String, List<String>>();
    for (final var path : repo.paths()) {
      byName.computeIfAbsent(path.substring(path.lastIndexOf('/') + 1), k -> new ArrayList<>()).add(path);
    }

    final var problems = new ArrayList<String>();
    var openFence = "";
    var lineNumber = 0;
    for (final var line : text.split("\n", -1)) {
      lineNumber++;
      final var fence = FENCE.matcher(line.stripLeading());
      if (fence.matches()) {
        final var marker = fence.group(1);
        if (openFence.isEmpty()) {
          openFence = marker;
          continue;
        }
        if (marker.charAt(0) == openFence.charAt(0) && marker.length() >= openFence.length()) openFence = "";
        continue;
      }
      if (!openFence.isEmpty()) continue;

      final var matcher = CITATION.matcher(line);
      while (matcher.find()) {
        final var cited = matcher.group(1);
        final var start = Integer.parseInt(matcher.group(2));
        final var end = matcher.group(3) == null ? start : Integer.parseInt(matcher.group(3));
        final var where = docPath + ":" + lineNumber + " -> " + matcher.group();

        final List<String> candidates;
        if (cited.contains("/") || repo.paths().contains(cited)) {
          // A path, including the no-directory kind a file at the repository root has. Resolved by
          // itself: falling back to the file name would accept any directory, and a name is unique
          // enough here that the fallback would always rescue one, leaving a renamed directory to
          // print a path no reader can open.
          candidates = repo.paths().contains(cited) ? List.of(cited) : List.of();
        } else {
          candidates = byName.getOrDefault(cited, List.of());
        }

        if (candidates.isEmpty()) {
          problems.add(where + " names no tracked file");
        } else if (candidates.size() > 1) {
          problems.add(where + " names " + candidates.size() + " files, so it resolves by accident");
        } else if (start < 1) {
          problems.add(where + " starts before the first line");
        } else {
          final var lines = repo.lineCount(candidates.getFirst());
          if (Math.max(start, end) > lines) {
            problems.add(
              where + " points past the end of " + candidates.getFirst() + ", which has " + lines + " lines"
            );
          }
        }
      }
    }
    // An unclosed fence would otherwise switch the scan off for the rest of the file, so every
    // reference below it goes unchecked and the document reports clean.
    if (!openFence.isEmpty()) problems.add(docPath + " leaves a " + openFence + " fence open");
    return problems;
  }

  @Test
  @DisplayName("every path:line reference in docs/ points inside the file it names")
  void everyCitationResolves() {
    final var repo = new GitRepo(repoRoot());
    final var problems = new ArrayList<String>();
    for (final var doc : repo.paths()) {
      if (!doc.startsWith("docs/") || !doc.endsWith(".md")) continue;
      problems.addAll(unresolved(doc, repo.read(doc), repo));
    }
    assertTrue(
      problems.isEmpty(),
      () -> problems.size() + " reference(s) do not resolve:\n  " + String.join("\n  ", problems)
    );
  }

  /**
   * The scan's own rules, against a repository small enough to state.
   *
   * <p>Three of its branches cannot be reached from committed documentation, which is the point of
   * the documentation being correct, and would otherwise be free to break unnoticed.
   */
  @Nested
  class TheScan {

    private final Repo repo = fake(
      Map.of("src/A.java", 10L, "src/deep/A.java", 10L, "src/B.java", 3L, "README.md", 5L, "docs/README.md", 5L)
    );

    @Test
    @DisplayName("a file at the repository root is a path even though it carries no directory")
    void rootLevelFilesArePaths() {
      // Two files answer to this name, so resolving it by name would report it ambiguous while the
      // reference is exact.
      assertEquals(List.of(), unresolved("d.md", "`README.md:5`", repo));
      assertEquals(1, unresolved("d.md", "`README.md:6`", repo).size(), "and is still bounded");
    }

    @Test
    @DisplayName("a reference inside its file resolves")
    void insideResolves() {
      assertEquals(List.of(), unresolved("d.md", "see `src/B.java:3` for it", repo));
    }

    @Test
    @DisplayName("a reference past the end of its file is reported with the bound")
    void pastTheEnd() {
      final var problems = unresolved("d.md", "see `src/B.java:4`", repo);
      assertEquals(1, problems.size(), problems::toString);
      assertTrue(problems.getFirst().contains("which has 3 lines"), problems::toString);
    }

    @Test
    @DisplayName("a range is judged by its end, in either order, and never starts before line one")
    void ranges() {
      assertEquals(List.of(), unresolved("d.md", "`src/B.java:1-3`", repo));
      assertEquals(1, unresolved("d.md", "`src/B.java:1-4`", repo).size());
      assertEquals(1, unresolved("d.md", "`src/B.java:9-1`", repo).size(), "a descending range still has an end");
      assertEquals(1, unresolved("d.md", "`src/B.java:0`", repo).size(), "there is no line zero");
    }

    @Test
    @DisplayName("a reference carrying a directory is resolved by that path and not by its file name")
    void pathsAreNotRescuedByName() {
      // The name is unique here, so a fallback would accept any directory at all and a renamed one
      // would print a path no reader can open.
      final var problems = unresolved("d.md", "`src/nosuch/B.java:1`", repo);
      assertEquals(1, problems.size(), problems::toString);
      assertTrue(problems.getFirst().contains("names no tracked file"), problems::toString);
    }

    @Test
    @DisplayName("a bare file name that two files answer to is reported rather than resolved")
    void ambiguousNames() {
      final var problems = unresolved("d.md", "`A.java:1`", repo);
      assertEquals(1, problems.size(), problems::toString);
      assertTrue(problems.getFirst().contains("names 2 files"), problems::toString);
      assertEquals(List.of(), unresolved("d.md", "`B.java:1`", repo), "one match is still a resolution");
    }

    @Test
    @DisplayName("a reference inside a fenced block is a transcript frame, whichever fence encloses it")
    void fencedBlocksAreSkipped() {
      for (final var fence : List.of("```", "```java", "~~~", "~~~~~")) {
        final var doc = fence + "\nat Foo.bar(src/B.java:900)\n" + fence.replaceAll("[^`~]", "") + "\n";
        assertEquals(List.of(), unresolved("d.md", doc, repo), () -> "inside " + fence);
      }
    }

    @Test
    @DisplayName("a shorter fence does not close a longer one")
    void aShorterFenceDoesNotClose() {
      final var problems = unresolved("d.md", "~~~~~\nsrc/B.java:900\n~~~\n", repo);
      assertEquals(1, problems.size(), problems::toString);
      assertTrue(problems.getFirst().contains("fence open"), problems::toString);
    }

    @Test
    @DisplayName("a longer fence nested inside a shorter one does not close it")
    void nestedFences() {
      assertEquals(List.of(), unresolved("d.md", "`````\n```\nsrc/B.java:900\n```\n`````\n", repo));
    }

    @Test
    @DisplayName("an unclosed fence is reported rather than silently ending the scan")
    void unclosedFence() {
      final var problems = unresolved("d.md", "```java\nnothing closes this\n", repo);
      assertEquals(1, problems.size(), problems::toString);
      assertTrue(problems.getFirst().contains("fence open"), problems::toString);
    }

    @Test
    @DisplayName("a reference in an inline code span is prose and is checked")
    void inlineSpansAreChecked() {
      assertEquals(1, unresolved("d.md", "as `src/B.java:99` shows", repo).size());
    }
  }

  private static Repo fake(final Map<String, Long> files) {
    return new Repo() {
      @Override
      public List<String> paths() {
        return List.copyOf(files.keySet());
      }

      @Override
      public long lineCount(final String path) {
        return files.get(path);
      }
    };
  }

  private static Path repoRoot() {
    var dir = Path.of("").toAbsolutePath();
    while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
      dir = dir.getParent();
    }
    if (dir == null) throw new IllegalStateException("no settings.gradle.kts above " + Path.of("").toAbsolutePath());
    return dir;
  }

  /**
   * The tracked files, as git lists them.
   *
   * <p>Walking the filesystem instead would index whatever is untracked or ignored in the working
   * copy, so a scratch file sharing a cited name makes that reference ambiguous on one machine and
   * not another, and an ignored document would be scanned locally and never in CI.
   */
  private static final class GitRepo implements Repo {

    private final Path root;
    private final List<String> paths;

    private GitRepo(final Path root) {
      this.root = root;
      this.paths = tracked(root);
    }

    private static List<String> tracked(final Path root) {
      try {
        final var git = new ProcessBuilder("git", "ls-files", "-z").directory(root.toFile()).start();
        final var out = new String(git.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (git.waitFor() != 0) throw new IllegalStateException("git ls-files failed in " + root);
        return List.of(out.split("\0"));
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted listing tracked files", e);
      }
    }

    @Override
    public List<String> paths() {
      return paths;
    }

    /** Counted over bytes, so a source file this JVM's charset cannot decode still has a length. */
    @Override
    public long lineCount(final String path) {
      final byte[] bytes;
      try {
        bytes = Files.readAllBytes(root.resolve(path));
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
      var lines = 0L;
      for (final var b : bytes) {
        if (b == '\n') lines++;
      }
      return bytes.length > 0 && bytes[bytes.length - 1] != '\n' ? lines + 1 : lines;
    }

    private String read(final String path) {
      try {
        return Files.readString(root.resolve(path));
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
}
