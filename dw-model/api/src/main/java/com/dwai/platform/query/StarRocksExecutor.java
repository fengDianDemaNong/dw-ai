package com.dwai.platform.query;

import com.dwai.platform.DwaiProperties;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class StarRocksExecutor {
  private final DwaiProperties props;

  public StarRocksExecutor(DwaiProperties props) {
    this.props = props;
  }

  public List<Map<String, Object>> query(String sql) {
    DwaiProperties.StarRocks sr = props.getStarrocks();
    if (!sr.isEnabled()) {
      return List.of();
    }
    String limited = sql.trim();
    if (!limited.toLowerCase().contains("limit")) {
      limited = limited.replaceAll(";\\s*$", "") + " LIMIT " + sr.getMaxRows();
    }
    try (Connection c = DriverManager.getConnection(sr.getUrl(), sr.getUsername(), sr.getPassword());
         Statement st = c.createStatement();
         ResultSet rs = st.executeQuery(limited)) {
      ResultSetMetaData md = rs.getMetaData();
      int n = md.getColumnCount();
      List<Map<String, Object>> rows = new ArrayList<>();
      int count = 0;
      while (rs.next() && count < sr.getMaxRows()) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= n; i++) {
          row.put(md.getColumnLabel(i), rs.getObject(i));
        }
        rows.add(row);
        count++;
      }
      return rows;
    } catch (Exception e) {
      throw new IllegalStateException("StarRocks 查询失败: " + e.getMessage(), e);
    }
  }
}
