package com.dwai.platform.meta.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class AiCaps {
  public static final String SPEC_DESIGN = "spec_design";
  public static final String SPEC_ASK = "spec_ask";
  public static final String MODEL_DESIGN = "model_design";
  public static final List<String> ALL = List.of(SPEC_DESIGN, SPEC_ASK, MODEL_DESIGN);
  private static final Set<String> ALLOWED = Set.of(SPEC_DESIGN, SPEC_ASK, MODEL_DESIGN);

  public static final String SLOT_SPEC = "spec.system";
  public static final String SLOT_SPEC_ASK = "spec.ask.system";
  public static final String SLOT_MODEL = "model.system";
  public static final Set<String> SLOTS = Set.of(SLOT_SPEC, SLOT_SPEC_ASK, SLOT_MODEL);

  public static final Set<String> ENGINES = Set.of("hive", "spark", "clickhouse", "doris");

  private AiCaps() {}

  public static List<String> normalize(List<String> raw) {
    if (raw == null || raw.isEmpty()) return List.of();
    List<String> out = new ArrayList<>();
    for (String c : raw) {
      if (c != null && ALLOWED.contains(c) && !out.contains(c)) out.add(c);
    }
    return out;
  }

  public static List<String> licensed(boolean warehouse, List<String> raw) {
    if (!warehouse) return List.of();
    List<String> caps = normalize(raw);
    return caps.isEmpty() ? ALL : caps;
  }

  public static List<String> effective(List<String> licensed, List<String> grant) {
    if (grant == null || grant.isEmpty()) return licensed == null ? List.of() : licensed;
    List<String> g = normalize(grant);
    if (g.isEmpty()) return licensed == null ? List.of() : licensed;
    List<String> out = new ArrayList<>();
    for (String c : licensed == null ? List.<String>of() : licensed) {
      if (g.contains(c)) out.add(c);
    }
    return out;
  }

  public static String capForSlot(String slot) {
    if (SLOT_SPEC.equals(slot)) return SPEC_DESIGN;
    if (SLOT_SPEC_ASK.equals(slot)) return SPEC_ASK;
    if (SLOT_MODEL.equals(slot)) return MODEL_DESIGN;
    return null;
  }

  public static List<String> normalizeEngines(List<String> raw) {
    if (raw == null || raw.isEmpty()) return List.of();
    List<String> out = new ArrayList<>();
    for (String e : raw) {
      if (e != null && ENGINES.contains(e) && !out.contains(e)) out.add(e);
    }
    return out;
  }
}
