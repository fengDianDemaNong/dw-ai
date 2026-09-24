package com.dwai.platform.meta.support;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@MappedTypes(String.class)
@MappedJdbcTypes(JdbcType.OTHER)
public class JsonbStringTypeHandler extends BaseTypeHandler<String> {
  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
    String product = ps.getConnection().getMetaData().getDatabaseProductName();
    if (DbVendors.isPostgres(product)) {
      PGobject obj = new PGobject();
      obj.setType("jsonb");
      obj.setValue(parameter);
      ps.setObject(i, obj);
      return;
    }
    ps.setString(i, parameter);
  }

  @Override
  public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
    return asJsonText(rs.getString(columnName), rs.getObject(columnName));
  }

  @Override
  public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    return asJsonText(rs.getString(columnIndex), rs.getObject(columnIndex));
  }

  @Override
  public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    return asJsonText(cs.getString(columnIndex), cs.getObject(columnIndex));
  }

  private static String asJsonText(String asString, Object asObject) {
    if (asString != null && !asString.isBlank()) {
      return asString;
    }
    return asObject == null ? null : asObject.toString();
  }
}
