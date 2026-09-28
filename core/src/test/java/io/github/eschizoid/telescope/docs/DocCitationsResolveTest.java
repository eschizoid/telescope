package io.github.eschizoid.telescope.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
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

  /**
   * The blockquote and list markers a fence may sit behind. A fenced block inside a callout or a
   * list item is still a fenced block, and the marker is whatever follows what encloses it.
   */
  private static final Pattern CONTAINERS = Pattern.compile(
    "^(?: {0,3}(?:(?:>[ \\t]?)+|(?:[-*+]|\\d{1,9}[.)])[ \\t]+))*"
  );

  /**
   * A fence opens on three or more backticks or tildes and closes on no fewer of the same one, at
   * up to three spaces of indent inside whatever encloses it.
   *
   * <p>An opener and a closer are not interchangeable. An opener may carry an info string and a
   * closer may not, so a document showing how to write a fenced block would otherwise have its
   * inner ```` ```java ```` read as closing the outer fence. A backtick opener's info string may
   * hold no backtick of its own, which is what keeps an inline span at the start of a line from
   * being read as a fence.
   *
   * <p>Four spaces of indent is an indented code block rather than a fence, which is why the indent
   * is bounded rather than stripped: a lone marker that deep is literal text, and the prose after
   * it is still prose. What such a block holds is read as prose, since indentation alone does not
   * separate one from a continued list item.
   */
  private static final Pattern FENCE = Pattern.compile(" {0,3}(`{3,}|~{3,})(.*)");

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
    // The three real terminators and nothing else: the \\R class also takes a form feed and four
    // other separators, and the document line a reference sits on is the one fact this message
    // has to be right about.
    for (final var line : text.split("\\r\\n|\\n|\\r", -1)) {
      lineNumber++;
      final var fence = FENCE.matcher(CONTAINERS.matcher(line).replaceFirst("").stripTrailing());
      if (fence.matches()) {
        final var marker = fence.group(1);
        final var info = fence.group(2);
        if (openFence.isEmpty()) {
          // A backtick fence's info string holds no backtick, so a line beginning with an inline
          // span is prose and is read as prose rather than skipped for having looked like a fence.
          if (marker.charAt(0) != '`' || !info.contains("`")) {
            openFence = marker;
            continue;
          }
        } else {
          final var closes =
            info.isEmpty() && marker.charAt(0) == openFence.charAt(0) && marker.length() >= openFence.length();
          if (closes) openFence = "";
          continue;
        }
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
        } else if (Math.min(start, end) < 1) {
          problems.add(where + " names a line before the first");
        } else {
          final var lines = repo.lineCount(candidates.getFirst());
          if (Math.max(start, end) > lines) {
            problems.add(
              where +
                " points past the end of " +
                candidates.getFirst() +
                ", which has " +
                lines +
                (lines == 0 ? " lines, or the working tree does not have it" : " lines")
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
      assertEquals(1, unresolved("d.md", "`src/B.java:2-0`", repo).size(), "at either end of a range");
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
    @DisplayName("a closing fence carries no info string, so a document showing a fence stays fenced")
    void aClosingFenceCarriesNoInfoString() {
      // The shape a document about fenced blocks has. Read the inner opener as a closer and the
      // frame beneath it is scanned as prose, while the real closer opens a fence nothing shuts.
      final var doc = "```\nprose\n```java\nsrc/B.java:900\n```\n";
      assertEquals(List.of(), unresolved("d.md", doc, repo));
    }

    @Test
    @DisplayName("an inline span at the start of a line is not a fence")
    void inlineSpanAtLineStart() {
      assertEquals(1, unresolved("d.md", "```src/B.java:900``` is a span\n", repo).size());
    }

    @Test
    @DisplayName("a working tree with carriage returns is read the same way")
    void carriageReturns() {
      assertEquals(List.of(), unresolved("d.md", "```\r\nat Foo.bar(src/B.java:900)\r\n```\r\n", repo));
      assertEquals(1, unresolved("d.md", "see `src/B.java:900`\r\n", repo).size());
    }

    @Test
    @DisplayName("a file the working tree does not have counts as no lines rather than throwing")
    void anAbsentFileCountsAsNone() {
      // The rule the case below relies on, asked of the thing that implements it: git lists what
      // its
      // index holds, so a deleted file is still a path, and reading one must not end the run before
      // the other references have been read.
      assertEquals(0, linesIn(Path.of("no", "such", "file.java")));
    }

    @Test
    @DisplayName("a reference into a file with no lines points past its end, and says why it might")
    void aFileWithNoLinesIsReported() {
      final var problems = unresolved("d.md", "`src/gone.java:1`", fake(Map.of("src/gone.java", 0L)));
      assertEquals(1, problems.size(), problems::toString);
      assertTrue(problems.getFirst().contains("the working tree does not have it"), problems::toString);
    }

    @Test
    @DisplayName("a fence behind a blockquote or a list marker is still a fence")
    void containersCarryTheirFences() {
      // A callout wrapping a transcript is ordinary, and this repository already writes one.
      // Reading
      // the marker only at the start of a line reports every frame inside such a block as prose.
      assertEquals(List.of(), unresolved("d.md", "> ```\n> at Foo.bar(src/B.java:900)\n> ```\n", repo));
      assertEquals(List.of(), unresolved("d.md", "- ```\n  at Foo.bar(src/B.java:900)\n  ```\n", repo));
      assertEquals(List.of(), unresolved("d.md", "1. ```\n   at Foo.bar(src/B.java:900)\n   ```\n", repo));
    }

    @Test
    @DisplayName("four spaces of indent is an indented block, not a fence")
    void indentedMarkersAreNotFences() {
      // A lone marker that deep is literal text. Reading it as a fence leaves one open and swallows
      // every reference below it, which is the opposite of what this gate is for.
      final var problems = unresolved("d.md", "    ```\n\nsee `src/B.java:900`\n", repo);
      assertEquals(1, problems.size(), problems::toString);
      assertTrue(problems.getFirst().contains("points past the end"), problems::toString);
    }

    @Test
    @DisplayName("a reference in an inline code span is prose and is checked")
    void inlineSpansAreChecked() {
      assertEquals(1, unresolved("d.md", "as `src/B.java:99` shows", repo).size());
    }
  }

  /**
   * Lines in a file, counted over bytes so a source this JVM's charset cannot decode still has a
   * length.
   *
   * <p>A path git has in its index and the working tree does not answers with none, which makes
   * every reference into it point past its end. Deleting a cited file is exactly the edit this gate
   * is for, so it arrives as a reference that cannot be followed rather than as an exception that
   * ends the run before the other references are read.
   */
  static long linesIn(final Path path) {
    final byte[] bytes;
    try {
      bytes = Files.readAllBytes(path);
    } catch (final NoSuchFileException absent) {
      return 0;
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    var lines = 0L;
    for (final var b : bytes) {
      if (b == '\n') lines++;
    }
    return bytes.length > 0 && bytes[bytes.length - 1] != '\n' ? lines + 1 : lines;
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
        final var git = new ProcessBuilder("git", "ls-files", "-z")
          .directory(root.toFile())
          .redirectError(ProcessBuilder.Redirect.INHERIT)
          .start();
        final var out = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
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

    @Override
    public long lineCount(final String path) {
      return linesIn(root.resolve(path));
    }

    /** A tracked document the working tree no longer has holds no references to check. */
    private String read(final String path) {
      try {
        return Files.readString(root.resolve(path));
      } catch (final NoSuchFileException e) {
        return "";
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
}
