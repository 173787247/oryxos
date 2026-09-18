package io.oryxos.web.controller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 行级统一 diff（#544）。快照通常很短，用 LCS DP 即可；无第三方依赖。 */
final class GovernanceUnifiedDiff {

  private GovernanceUnifiedDiff() {}

  static String unified(long fromId, long toId, String fromText, String toText) {
    List<String> a = lines(fromText);
    List<String> b = lines(toText);
    StringBuilder out = new StringBuilder();
    out.append("--- a/revision/").append(fromId).append('\n');
    out.append("+++ b/revision/").append(toId).append('\n');
    if (a.equals(b)) {
      return out.toString();
    }
    int[][] lcs = lcsTable(a, b);
    List<String> ops = new ArrayList<>();
    int i = a.size();
    int j = b.size();
    while (i > 0 || j > 0) {
      if (i > 0 && j > 0 && a.get(i - 1).equals(b.get(j - 1))) {
        ops.add(" " + a.get(i - 1));
        i--;
        j--;
      } else if (j > 0 && (i == 0 || lcs[i][j - 1] >= lcs[i - 1][j])) {
        ops.add("+" + b.get(j - 1));
        j--;
      } else {
        ops.add("-" + a.get(i - 1));
        i--;
      }
    }
    // ops 是逆序
    int startA = 1;
    int startB = 1;
    List<String> hunk = new ArrayList<>();
    int countA = 0;
    int countB = 0;
    for (int k = ops.size() - 1; k >= 0; k--) {
      String op = ops.get(k);
      hunk.add(op);
      if (op.charAt(0) != '+') {
        countA++;
      }
      if (op.charAt(0) != '-') {
        countB++;
      }
    }
    out.append("@@ -")
        .append(startA)
        .append(',')
        .append(countA)
        .append(" +")
        .append(startB)
        .append(',')
        .append(countB)
        .append(" @@\n");
    for (String line : hunk) {
      out.append(line).append('\n');
    }
    return out.toString();
  }

  private static List<String> lines(String text) {
    if (text == null || text.isEmpty()) {
      return List.of();
    }
    String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
    if (normalized.endsWith("\n")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    if (normalized.isEmpty()) {
      return List.of();
    }
    return Arrays.asList(normalized.split("\n", -1));
  }

  private static int[][] lcsTable(List<String> a, List<String> b) {
    int n = a.size();
    int m = b.size();
    int[][] dp = new int[n + 1][m + 1];
    for (int i = 1; i <= n; i++) {
      for (int j = 1; j <= m; j++) {
        if (a.get(i - 1).equals(b.get(j - 1))) {
          dp[i][j] = dp[i - 1][j - 1] + 1;
        } else {
          dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
        }
      }
    }
    return dp;
  }
}
