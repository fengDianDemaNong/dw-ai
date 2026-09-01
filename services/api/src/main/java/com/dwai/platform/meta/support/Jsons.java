package com.dwai.platform.meta.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

public final class Jsons {
  private static final ObjectMapper M = new ObjectMapper();
  private static final TypeReference<List<String>> STR_LIST = new TypeReference<>() {};
  private static final TypeReference<List<Map<String, Object>>> MAP_LIST = new TypeReference<>() {};
  private static final TypeReference<Map<String, String>> STR_MAP = new TypeReference<>() {};
  private static final TypeReference<Map<String, Object>> OBJ_MAP = new TypeReference<>() {};

  private Jsons() {}

  public static String toJson(Object v) {
    if (v == null) return null;
    try {
      return M.writeValueAsString(v);
    } catch (Exception e) {
      throw new IllegalArgumentException("json encode", e);
    }
  }

  public static List<String> strings(String raw) {
    JsonNode n = tree(raw);
    if (n == null || !n.isArray()) return List.of();
    return M.convertValue(n, STR_LIST);
  }

  public static Map<String, String> stringMap(String raw) {
    JsonNode n = tree(raw);
    if (n == null || !n.isObject()) return Map.of();
    Map<String, String> m = M.convertValue(n, STR_MAP);
    return m == null ? Map.of() : m;
  }

  public static Map<String, Object> map(String raw) {
    JsonNode n = tree(raw);
    if (n == null || !n.isObject()) return null;
    return M.convertValue(n, OBJ_MAP);
  }

  public static List<Map<String, Object>> maps(String raw) {
    JsonNode n = tree(raw);
    if (n == null || !n.isArray()) return List.of();
    return M.convertValue(n, MAP_LIST);
  }

  /** H2 JSON 列常把数组存成带引号的字符串，先解开再当 JSON 用。 */
  static JsonNode tree(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      JsonNode n = M.readTree(raw.trim());
      int guard = 0;
      while (n != null && n.isTextual() && guard++ < 3) {
        String inner = n.asText();
        if (inner == null || inner.isBlank()) return n;
        char c = inner.trim().isEmpty() ? 0 : inner.trim().charAt(0);
        if (c != '[' && c != '{' && c != '"') return n;
        n = M.readTree(inner);
      }
      return n;
    } catch (Exception e) {
      return null;
    }
  }
}
