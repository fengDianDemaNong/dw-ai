package com.dwai.lineage.service;

import com.dwai.lineage.dto.SqlValidateResponse;
import com.dwai.lineage.enums.DatabaseTypeEnum;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 语法校验与关键字，供编辑器做实时提示。
 *
 * <p>能力来自 superior-sql-parser 的 {@code checkSqlSyntax} 与 {@code sqlKeywords}，
 * 此前一直没有暴露成接口。
 */
@Service
public class SqlSyntaxService {

    /**
     * 从报错文本中抽出行列号，让编辑器能精确定位。
     *
     * <p>需要同时兼容两种格式：
     * <ul>
     *   <li>ANTLR 原生：{@code line 3:15 mismatched input 'xxx'}</li>
     *   <li>superior-sql-parser 包装后：{@code ...(line 3, pos 0)}</li>
     * </ul>
     * 后者更常见，若只匹配前者会让所有错误的行号都退化成 1。
     */
    private static final Pattern POSITION =
            Pattern.compile("line\\s+(\\d+)\\s*(?::|,\\s*pos\\s+)(\\d+)");

    public SqlValidateResponse validate(String dbType, String sql) {
        DatabaseTypeEnum dialect = DatabaseTypeEnum.fromString(dbType);
        try {
            dialect.checkSyntax(sql);
            return SqlValidateResponse.ok();
        } catch (Exception e) {
            return SqlValidateResponse.failed(List.of(toError(e.getMessage())));
        }
    }

    public List<String> keywords(String dbType) {
        return DatabaseTypeEnum.fromString(dbType).keywords();
    }

    private static SqlValidateResponse.SyntaxError toError(String rawMessage) {
        String message = rawMessage == null ? "语法错误" : rawMessage;
        Matcher m = POSITION.matcher(message);
        if (m.find()) {
            return new SqlValidateResponse.SyntaxError(
                    Integer.parseInt(m.group(1)),
                    // ANTLR 的列号从 0 开始，Monaco 从 1 开始
                    Integer.parseInt(m.group(2)) + 1,
                    message);
        }
        return new SqlValidateResponse.SyntaxError(1, 1, message);
    }
}
