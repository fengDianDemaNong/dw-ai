package com.dwai.platform.meta.support;

import com.dwai.platform.db.MetaDb;
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
    if (MetaDb.isPostgres(product)) {
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
    Object v = rs.getObject(columnName);
    return v == null ? null : v.toString();
  }

  @Override
  public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    Object v = rs.getObject(columnIndex);
    return v == null ? null : v.toString();
  }

  @Override
  public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    Object v = cs.getObject(columnIndex);
    return v == null ? null : v.toString();
  }
}
