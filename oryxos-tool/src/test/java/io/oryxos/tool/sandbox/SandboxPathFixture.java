package io.oryxos.tool.sandbox;

import io.oryxos.core.testing.SymlinkAssumptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class SandboxPathFixture {

  private final Path allowed;
  private final Path outside;

  SandboxPathFixture(Path temp) throws IOException {
    this.allowed = Files.createDirectories(temp.resolve("allowed"));
    this.outside = Files.createDirectories(temp.resolve("outside"));
  }

  Path allowed() {
    return allowed;
  }

  Path outside() {
    return outside;
  }

  Path parentEscape() throws IOException {
    Path link = allowed.resolve("escape");
    SymlinkAssumptions.createSymbolicLinkOrAssume(link, outside);
    return link;
  }

  Path dangling() throws IOException {
    Path link = allowed.resolve("dangling");
    SymlinkAssumptions.createSymbolicLinkOrAssume(link, Path.of("missing"));
    return link;
  }

  Path multiHopEscape() throws IOException {
    SymlinkAssumptions.createSymbolicLinkOrAssume(allowed.resolve("second"), outside);
    Path first = allowed.resolve("first");
    SymlinkAssumptions.createSymbolicLinkOrAssume(first, Path.of("second"));
    return first;
  }

  Path[] cycle() throws IOException {
    Path one = allowed.resolve("one");
    Path two = allowed.resolve("two");
    SymlinkAssumptions.createSymbolicLinkOrAssume(one, Path.of("two"));
    SymlinkAssumptions.createSymbolicLinkOrAssume(two, Path.of("one"));
    return new Path[] {one, two};
  }
}
