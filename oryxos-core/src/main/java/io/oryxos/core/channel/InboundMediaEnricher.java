package io.oryxos.core.channel;

/** 将归一化入站消息（含图片附件）转为 Agent 可消费的文本输入。 */
public interface InboundMediaEnricher {

  String toAgentInput(InboundMessage message);
}
