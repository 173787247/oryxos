package io.oryxos.core.task;

import java.util.List;

/** Supplies known agent / profile names for the coordinator plan prompt. */
@FunctionalInterface
public interface TeamAgentCatalog {

  /**
   * @return existing agent directory names (may be empty)
   */
  List<String> names();
}
