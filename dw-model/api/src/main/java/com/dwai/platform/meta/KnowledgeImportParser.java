package com.dwai.platform.meta;

import com.dwai.platform.meta.support.AiCaps;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 与 packages/engine knowledgeIo.ts 对齐的 JSON / Markdown 解析。 */
public final class KnowledgeImportParser {
  public static final String KIND = "dw-ai.knowledge";
  private static final ObjectMapper M = new ObjectMapper();

  private KnowledgeImportParser() {}

  public record Article(
      String engine, String id, String title, String summary, String body,
      String sourceUrl, String sourceLabel, List<Map<String, Object>> sections, List<String> notes) {}

  public record Result(List<Article> articles, List<String> warnings, String format) {}

  public static Result parse(String filename, String text) {
    if (text == null || text.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件为空");
    }
    String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
    String trimmed = text.trim();
    if (name.endsWith(".json") || trimmed.startsWith("{")) return parseJson(trimmed);
    if (name.endsWith(".md") || name.endsWith(".markdown") || trimmed.startsWith("---")) {
      return parseMarkdown(text);
    }
    if (trimmed.startsWith("{")) return parseJson(trimmed);
    return parseMarkdown(text);
  }

  private static Result parseJson(String text) {
    List<String> warnings = new ArrayList<>();
    JsonNode root;
    try {
      root = M.readTree(text);
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "JSON 无法解析，请对照模版检查逗号和引号");
    }
    if (root == null || !root.isObject()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "JSON 根节点必须是对象");
    }
    if (root.has("kind") && !KIND.equals(root.path("kind").asText())) {
      warnings.add("kind 应为 " + KIND + "，当前是 " + root.path("kind").asText() + "，仍尝试读取");
    }
    List<JsonNode> list = new ArrayList<>();
    if (root.path("articles").isArray()) {
      root.path("articles").forEach(list::add);
    } else if (root.path("engines").isArray()) {
      for (JsonNode eng : root.path("engines")) {
        String engine = textOf(eng.path("engine"));
        if (eng.path("articles").isArray()) {
          for (JsonNode a : eng.path("articles")) {
            if (a.isObject()) {
              Map<String, Object> merged = M.convertValue(a, Map.class);
              merged.put("engine", engine);
              list.add(M.valueToTree(merged));
            }
          }
        }
      }
    } else {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要 articles 数组，或 engines[].articles。请下载模版");
    }
    List<Article> articles = new ArrayList<>();
    int i = 0;
    for (JsonNode item : list) {
      if (!item.isObject()) {
        warnings.add("第 " + (i + 1) + " 篇不是对象，已跳过");
        i++;
        continue;
      }
      Article row = normalize(item, i, warnings);
      if (row != null) articles.add(row);
      i++;
    }
    if (articles.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, warnings.isEmpty() ? "没有可导入的篇" : warnings.get(0));
    }
    return new Result(articles, warnings, "json");
  }

  private static Result parseMarkdown(String text) {
    List<String> warnings = new ArrayList<>();
    List<Article> articles = new ArrayList<>();
    List<String> docs = splitMarkdownDocs(text);
    int i = 0;
    for (String doc : docs) {
      FrontMatter fm = parseFrontMatter(doc);
      MdBody parsed = parseMarkdownSections(fm.body);
      Map<String, Object> raw = new LinkedHashMap<>();
      raw.put("engine", fm.meta.get("engine"));
      raw.put("id", fm.meta.get("id"));
      raw.put("title", fm.meta.get("title"));
      raw.put("summary", fm.meta.get("summary"));
      raw.put("body", parsed.body);
      raw.put("sourceUrl", fm.meta.get("sourceUrl"));
      raw.put("sourceLabel", fm.meta.get("sourceLabel"));
      raw.put("sections", parsed.sections);
      if (!parsed.notes.isEmpty()) raw.put("notes", parsed.notes);
      Article row = normalize(M.valueToTree(raw), i, warnings);
      if (row != null) articles.add(row);
      i++;
    }
    if (articles.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, warnings.isEmpty() ? "Markdown 里没有可导入的篇，请从 --- 头开始" : warnings.get(0));
    }
    return new Result(articles, warnings, "markdown");
  }

  private static Article normalize(JsonNode raw, int index, List<String> warnings) {
    String engine = textOf(raw.path("engine")).toLowerCase(Locale.ROOT);
    if (!AiCaps.ENGINES.contains(engine)) {
      warnings.add("第 " + (index + 1) + " 篇 engine 无效（" + raw.path("engine").asText("") + "），只接受 hive / spark / clickhouse / doris");
      return null;
    }
    String title = textOf(raw.path("title"));
    if (title.isBlank()) {
      warnings.add("第 " + (index + 1) + " 篇缺少 title，已跳过");
      return null;
    }
    List<Map<String, Object>> sections = parseSections(raw.path("sections"));
    String sql = textOf(raw.path("sql"));
    if (!sql.isBlank() && sections.isEmpty()) {
      Map<String, Object> sec = new LinkedHashMap<>();
      String cap = textOf(raw.path("sqlCaption"));
      sec.put("heading", cap.isBlank() ? "SQL" : cap);
      sec.put("body", "");
      sec.put("sql", sql);
      if (!cap.isBlank()) sec.put("sqlCaption", cap);
      sections.add(sec);
    }
    String body = textOf(raw.path("body"));
    if (body.isBlank() && sections.isEmpty()) {
      warnings.add("「" + title + "」没有 body / sections / sql，已跳过");
      return null;
    }
    String id = textOf(raw.path("id"));
    if (id.isBlank()) id = engine + "-" + slug(title);
    List<String> notes = new ArrayList<>();
    if (raw.path("notes").isArray()) {
      for (JsonNode n : raw.path("notes")) {
        String s = textOf(n);
        if (!s.isBlank()) notes.add(s);
      }
    }
    String summary = textOf(raw.path("summary"));
    return new Article(
        engine, id, title, summary.isBlank() ? title : summary,
        body,
        blankToNull(textOf(raw.path("sourceUrl"))),
        blankToNull(textOf(raw.path("sourceLabel"))),
        sections,
        notes.isEmpty() ? List.of() : notes);
  }

  private static List<Map<String, Object>> parseSections(JsonNode raw) {
    List<Map<String, Object>> out = new ArrayList<>();
    if (raw == null || !raw.isArray()) return out;
    for (JsonNode item : raw) {
      if (item == null || !item.isObject()) continue;
      String heading = textOf(item.path("heading"));
      String body = textOf(item.path("body"));
      String sql = textOf(item.path("sql"));
      if (heading.isBlank() && body.isBlank() && sql.isBlank()) continue;
      Map<String, Object> sec = new LinkedHashMap<>();
      sec.put("heading", heading.isBlank() ? "小节" : heading);
      sec.put("body", body);
      if (!sql.isBlank()) sec.put("sql", sql);
      String cap = textOf(item.path("sqlCaption"));
      if (!cap.isBlank()) sec.put("sqlCaption", cap);
      String note = textOf(item.path("note"));
      if (!note.isBlank()) sec.put("note", note);
      out.add(sec);
    }
    return out;
  }

  private static String slug(String input) {
    String s = input.trim().toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9\\u4e00-\\u9fff]+", "-")
        .replaceAll("^-+|-+$", "");
    if (s.length() > 40) s = s.substring(0, 40);
    return s.isBlank() ? "article" : s;
  }

  private static String textOf(JsonNode n) {
    if (n == null || n.isMissingNode() || n.isNull()) return "";
    return n.asText("").trim();
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  private record FrontMatter(Map<String, String> meta, String body) {}

  private static FrontMatter parseFrontMatter(String block) {
    Map<String, String> meta = new LinkedHashMap<>();
    String src = block.replaceFirst("^\\uFEFF", "");
    String[] lines = src.split("\\r?\\n", -1);
    int i = 0;
    if (lines.length > 0 && "---".equals(lines[0].trim())) i = 1;
    for (; i < lines.length; i++) {
      if ("---".equals(lines[i].trim())) {
        return new FrontMatter(meta, String.join("\n", java.util.Arrays.copyOfRange(lines, i + 1, lines.length)));
      }
      Matcher m = Pattern.compile("^([A-Za-z][\\w]*)\\s*:\\s*(.*)$").matcher(lines[i]);
      if (m.matches()) meta.put(m.group(1), m.group(2).trim());
    }
    return new FrontMatter(meta, block);
  }

  private static List<String> splitMarkdownDocs(String text) {
    String src = text.replaceFirst("^\\uFEFF", "").trim();
    if (src.isEmpty()) return List.of();
    if (!src.startsWith("---")) return List.of(src);
    String[] chunks = src.split("\\n(?=---\\s*\\n)");
    List<String> parts = new ArrayList<>();
    for (String c : chunks) {
      String t = c.trim();
      if (!t.isBlank()) parts.add(t);
    }
    return parts;
  }

  private record MdBody(String body, List<Map<String, Object>> sections, List<String> notes) {}

  private static MdBody parseMarkdownSections(String body) {
    List<String> intro = new ArrayList<>();
    List<Map<String, Object>> sections = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    String[] chunks = body.split("\\n(?=##\\s+)");
    for (String chunk : chunks) {
      String[] lines = chunk.replaceAll("\\s+$", "").split("\\r?\\n", -1);
      Matcher head = Pattern.compile("^##\\s+(.+)$").matcher(lines.length > 0 ? lines[0] : "");
      if (!head.matches()) {
        intro.add(chunk.trim());
        continue;
      }
      String rest = String.join("\n", java.util.Arrays.copyOfRange(lines, 1, lines.length));
      Matcher sqlMatch = Pattern.compile("```(?:sql)?\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE).matcher(rest);
      String sql = sqlMatch.find() ? sqlMatch.group(1).replaceAll("\\s+$", "") : "";
      Matcher noteMatch = Pattern.compile("^>\\s?(.+)$", Pattern.MULTILINE).matcher(rest);
      String note = noteMatch.find() ? noteMatch.group(1).trim() : "";
      String withoutSql = rest.replaceAll("```(?:sql)?\\s*\\n[\\s\\S]*?```", "").replaceFirst("(?m)^>\\s?.+$", "");
      Map<String, Object> sec = new LinkedHashMap<>();
      sec.put("heading", head.group(1).trim());
      sec.put("body", withoutSql.replaceAll("\\n{3,}", "\n\n").trim());
      if (!sql.isBlank()) sec.put("sql", sql);
      if (!note.isBlank()) sec.put("note", note);
      sections.add(sec);
    }
    String lead = String.join("\n\n", intro).trim();
    Matcher leadNote = Pattern.compile("^>\\s?(.+)$", Pattern.MULTILINE).matcher(lead);
    if (leadNote.find()) notes.add(leadNote.group(1).trim());
    return new MdBody(lead.replaceFirst("(?m)^>\\s?.+$", "").trim(), sections, notes);
  }
}
