package io.oryxos.core.memory;

import java.time.Instant;

/**
 * 归档记忆条目视图（015）：时间新近路与索引对账的取数形状。content 是该档 recall/索引共用的「条目原文」 （markdown 档 = 整行含时间戳前缀，sqlite 档 =
 * content 列）——entry_hash 以它为准，三路候选才能对齐。 time 解析不出为 null，时间路按最旧处理。
 */
public record MemoryEntryView(String content, Instant time) {}
