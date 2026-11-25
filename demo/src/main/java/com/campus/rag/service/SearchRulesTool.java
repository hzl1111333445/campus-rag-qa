package com.campus.rag.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * 工具：检索校内规章原文。
 *
 * <p>内部复用 {@link RetrievalService#searchBestGated}，即线上那套
 * 双路召回 → RRF → 精排 → 展开邻居（Recall@3 90.68%），不在 Agent 里另起一套。
 */
@Component
public class SearchRulesTool implements AgentTool {

    private static final int DEFAULT_TOPK = 5;
    private static final int MAX_TOPK = 10;
    // 单条命中最多给模型 900 字：expandHits 会把相邻块并进来（3 块约 750 字），
    // 之前只给 260 字，会把「(4) 51学时以上…(5) 留校察看期内再犯…」这些后续档位直接切掉，
    // 模型只能诚实地说「条款被截断」——那是我们造成的假缺失。
    private static final int SNIPPET_CHARS = 900;

    private final RetrievalService retrievalService;

    public SearchRulesTool(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @Override
    public String name() {
        return "search_rules";
    }

    @Override
    public String description() {
        return "检索校内规章制度的原文条款。凡涉及学校规定、处分、条件、时限、金额、办理流程的问题，必须先用它查证据。";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "query", "检索语句，陈述式短句，例如「旷课学时 处分 档位」",
                "topK", "返回条数 1~10，默认 5");
    }

    @Override
    public ToolResult run(Map<String, Object> args) {
        String query = str(args.get("query"));
        if (query.isBlank()) {
            return ToolResult.fail("query 不能为空");
        }
        // 至少取 5 条：条文常是「A 档…B 档…」连着的，只取 3 条会把更高档位截掉，
        // 模型只能诚实说"没拿到完整条款"——那是假缺失（expandHits 会带相邻块，但起点要多）
        int topK = Math.max(clampInt(args.get("topK"), DEFAULT_TOPK, 1, MAX_TOPK), 5);
        try {
            RetrievalService.Best best = retrievalService.searchBestGated(query, topK);
            List<RetrievalService.Hit> hits = best.hits();
            if (hits == null || hits.isEmpty()) {
                return new ToolResult(false, "未检索到任何条款（最高分 " + round(best.topScore()) + "）", List.of());
            }
            StringBuilder sb = new StringBuilder();
            sb.append("检索到 ").append(hits.size()).append(" 条（最高分 ").append(round(best.topScore()));
            if (best.reranked()) {
                sb.append("，已精排");
            }
            sb.append("）：\n");
            List<Long> ids = new ArrayList<>(hits.size());
            for (int i = 0; i < hits.size(); i++) {
                RetrievalService.Hit h = hits.get(i);
                ids.add(h.chunkId());
                sb.append('[').append(i + 1).append("] 《").append(h.documentTitle())
                  .append("》第 ").append(h.chunkIndex()).append(" 块（相关度 ")
                  .append(round(h.score())).append("）：")
                  .append(snippet(h.content())).append('\n');
            }
            sb.append("提示：引用编号只能用上面出现过的序号。");
            return new ToolResult(true, sb.toString(), ids);
        } catch (RuntimeException e) {
            return ToolResult.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static String snippet(String content) {
        if (content == null) {
            return "";
        }
        String s = content.replaceAll("\\s+", " ").trim();
        return s.length() <= SNIPPET_CHARS ? s : s.substring(0, SNIPPET_CHARS) + "…";
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static int clampInt(Object v, int dft, int min, int max) {
        try {
            int n = v == null ? dft : (int) Double.parseDouble(String.valueOf(v));
            return Math.max(min, Math.min(max, n));
        } catch (RuntimeException e) {
            return dft;
        }
    }

    private static double round(double v) {
        return Math.round(v * 10000d) / 10000d;
    }
}