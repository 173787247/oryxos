package io.oryxos.web.controller.dto;

import io.oryxos.core.flow.FlowDraftAuthor;

/** Flow 草稿预览：不落盘。 */
public record FlowDraftView(String id, String markdown, String entry) {

  public static FlowDraftView from(FlowDraftAuthor.Draft draft) {
    return new FlowDraftView(draft.flowId(), draft.markdown(), draft.definition().entry());
  }
}
