package io.oryxos.knowledge.index;

/** 解析器输出的最小单元：markdown/纯文本为整篇一个单元（pageNo 为 null），PDF 每页一个单元 （pageNo 从 1 起，出处用页码，FR-003）。 */
public record ParsedUnit(String text, Integer pageNo) {}
