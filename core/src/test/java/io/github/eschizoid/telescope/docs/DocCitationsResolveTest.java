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
   * A stack frame, which is the one place a {@code path:line} appears without being a reference
   * into this repository.
   *
   * <p>A frame is a transcript of something that ran, and its file and line belong to whatever was
   * on that machine's classpath: generated sources, or the JDK. Recognising the frame is what tells
   * those apart from a reference, rather than recognising the block a frame is usually written in.
   *
   * <p>Taken from the grammar {@link StackTraceElement#toString()} prints, which is closed: a class
   * loader, then a module with an optional version, then the class, the method, the file and the
   * line. A version carries dots, a hyphen for a qualifier and a plus for build metadata, and the
   * method may be a constructor or a static initialiser in angle brackets. Deriving this from that
   * grammar rather than from remembered examples is what makes it checkable, instead of a list that
   * grows each time someone pastes a trace.
   *
   * <p>The extensions are the ones a reference can be written in. An extension this file never
   * reads as a reference cannot be mistaken for one, so a Kotlin, Scala or Groovy source frame
   * needs no alternative here.
   */
  private static final Pattern FRAME = Pattern.compile("\\bat\\s+[\\w.$/<>@+-]+\\([\\w$.]+\\.(?:java|kts):\\d+\\)");

  /**
   * Every reference in one document that does not resolve, each named with the line it sits on.
   *
   * <p>A reference carrying a directory is resolved by that path alone. Falling back to the file
   * name would accept any path at all, since a name is unique across this repository and the
   * fallback would always rescue it, leaving a renamed directory to print a path no reader can
   * open. A reference that is only a file name has nothing else to resolve by, and is ambiguous
   * rather than resolved when two files answer to it.
   *
   * <p>A reference inside a stack frame is skipped, a frame's file and line belonging to whatever
   * ran rather than to anything here. Nothing else is skipped: a reference written inside a code
   * block is one a reader would still follow, and it rots the same way, so it is checked like any
   * other.
   */
  static List<String> unresolved(final String docPath, final String text, final Repo repo) {
    final var byName = new HashMap<String, List<String>>();
    for (final var path : repo.paths()) {
      byName.computeIfAbsent(path.substring(path.lastIndexOf('/') + 1), k -> new ArrayList<>()).add(path);
    }

    final var problems = new ArrayList<String>();
    var lineNumber = 0;
    // The three real terminators and nothing else: the \\R class also takes a form feed and four
    // other separators, and the document line a reference sits on is the one fact this message has
    // to be right about.
    for (final var line : text.split("\\r\\n|\\n|\\r", -1)) {
      lineNumber++;
      // Where this line's frames are, so a reference inside one can be told from a reference beside
      // one: a sentence can quote a frame and cite a file, and only the frame's own span is exempt.
      final var frames = FRAME.matcher(line)
        .results()
        .map(r -> new int[] { r.start(), r.end() })
        .toList();

      final var matcher = CITATION.matcher(line);
      while (matcher.find()) {
        final var cited = matcher.group(1);
        final var start = Integer.parseInt(matcher.group(2));
        final var end = matcher.group(3) == null ? start : Integer.parseInt(matcher.group(3));
        if (frames.stream().anyMatch(f -> matcher.start() >= f[0] && matcher.end() <= f[1])) continue;
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

    /**
     * The same repository plus a root build script, so a Kotlin-DSL frame has something to resolve.
     */
    private final Repo scriptRepo = fake(Map.of("B.java", 3L, "build.gradle.kts", 3L));

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
    @DisplayName("a reference inside a stack frame belongs to whatever ran, not to this repository")
    void framesAreSkipped() {
      // A frame names a file without a path, which is how the JVM writes one, and the name resolves
      // here by itself, so without the exemption both of these would be reported.
      final var doc = "It threw:\n\n```\nat Foo.bar(B.java:900)\n  at Baz.qux(B.java:901)\n```\n";
      assertEquals(List.of(), unresolved("d.md", doc, repo));
    }

    @Test
    @DisplayName("every shape the JDK prints a frame in is a frame")
    void frameSyntaxVariesMoreThanTheBlockAroundIt() {
      // What this rule branches on is the frame's own syntax, so that is what varies here: the
      // grammar StackTraceElement prints, walked through its loader, module, version and method
      // forms. A version with a qualifier is what a snapshot build of this project emits.
      //
      // It holds only where the frame survives intact. This repository's formatter reflows prose at
      // 120 columns and the sole space inside a frame is the one after "at", so a frame long enough
      // to wrap loses that word and is read as a reference. A fenced block is the one place
      // prettier
      // leaves alone, which is a reason to write a transcript in one that has nothing to do with
      // what a fence means.
      for (final var frame : List.of(
        "at Foo.bar(B.java:900)",
        "\tat Foo.bar(B.java:900)",
        "at io.foo.Bar.<init>(B.java:900)",
        "at io.foo.Bar.<clinit>(B.java:900)",
        "at java.base/io.foo.Bar.baz(B.java:900)",
        "at app//io.foo.Bar.baz(B.java:900)",
        "at app/io.foo@1.8.0/io.foo.Bar.baz(B.java:900)",
        "at app/io.foo@1.8.0-SNAPSHOT/io.foo.Bar.baz(B.java:900)",
        "at app/io.foo@1.0+build.7/io.foo.Bar.baz(B.java:900)",
        "at Build_gradle.main(build.gradle.kts:900)"
      )) {
        assertEquals(List.of(), unresolved("d.md", frame + "\n", scriptRepo), () -> frame);
      }
    }

    @Test
    @DisplayName("a reference beside a frame on the same line is still checked")
    void aReferenceBesideAFrameIsChecked() {
      // Only the frame's own span is exempt. A sentence that quotes a frame and cites a file is two
      // things, and the citation half is a reference a reader would follow.
      final var problems = unresolved("d.md", "thrown at Foo.bar(B.java:900), see `src/B.java:99`\n", repo);
      assertEquals(1, problems.size(), problems::toString);
      assertTrue(problems.getFirst().contains("src/B.java:99"), problems::toString);
    }

    @Test
    @DisplayName("a reference in a code block is one a reader would follow, so it is checked")
    void codeBlocksAreChecked() {
      // The exemption is for frames rather than for blocks. A sample that points a reader at a file
      // is a reference like any other, whichever block it is written in, and it rots the same way.
      final var doc = "```java\n// see src/B.java:900 for the guard\n```\n";
      final var problems = unresolved("d.md", doc, repo);
      assertEquals(1, problems.size(), problems::toString);
      assertTrue(problems.getFirst().contains("points past the end"), problems::toString);
    }

    @Test
    @DisplayName("a working tree with carriage returns reports the same line numbers")
    void carriageReturns() {
      final var problems = unresolved("d.md", "one\r\ntwo `src/B.java:900`\r\n", repo);
      assertEquals(1, problems.size(), problems::toString);
      assertTrue(problems.getFirst().startsWith("d.md:2 "), problems::toString);
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
