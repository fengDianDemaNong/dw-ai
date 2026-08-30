package com.dwai.platform.meta.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

public final class Jsons {
  private static final ObjectMapper M = new ObjectMapper();
  private static final TypeReference<List<String>> STR_LIST = new TypeReference<>() {};
  private static final TypeReference<List<Map<String, Object>>> MAP_LIST = new TypeReference<>() {};
  private static final TypeReference<Map<String, String>> STR_MAP = new TypeReference<>() {};

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
    if (raw == null || raw.isBlank()) return List.of();
    try {
      return M.readValue(raw, STR_LIST);
    } catch (Exception e) {
      return List.of();
    }
  }

  public static Map<String, String> stringMap(String raw) {
    if (raw == null || raw.isBlank()) return Map.of();
    try {
      Map<String, String> m = M.readValue(raw, STR_MAP);
      return m == null ? Map.of() : m;
    } catch (Exception e) {
      return Map.of();
    }
  }

  public static List<Map<String, Object>> maps(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    try {
      return M.readValue(raw, MAP_LIST);
    } catch (Exception e) {
      return List.of();
    }
  }
}
