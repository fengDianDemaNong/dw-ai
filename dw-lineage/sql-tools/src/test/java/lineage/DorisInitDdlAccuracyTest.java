package lineage;

import io.github.melin.superior.common.relational.Statement;
import io.github.melin.superior.common.relational.create.CreateTable;
import io.github.melin.superior.common.relational.table.ColumnRel;
import org.junit.Test;
import com.dwai.lineage.enums.DatabaseTypeEnum;
import com.dwai.lineage.service.metadata.DdlCatalogExtractor;
import com.dwai.lineage.service.metadata.provider.DdlMetadataProvider;
import com.dwai.lineage.util.SqlUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assume.assumeTrue;

/**
 * 用现网 dw/init 的 Doris 建表语句对照解析结果。目录不存在时跳过。
 */
public class DorisInitDdlAccuracyTest {

    private static final Path INIT = Path.of(
            "/Users/wang/Downloads/data/git-repo/projects/dw/init");

    @Test
    public void reportDorisInitDdlAccuracy() throws Exception {
        assumeTrue("init 目录不存在，跳过建表对照", Files.isDirectory(INIT));

        List<Path> files;
        try (Stream<Path> walk = Files.walk(INIT)) {
            files = walk.filter(p -> p.toString().endsWith(".sql")).sorted().toList();
        }
        assertFalse("init 下应有 sql 文件", files.isEmpty());

        int parseOk = 0;
        int createOk = 0;
        int exactNameMatch = 0;
        int typeMismatch = 0;
        int commentMismatch = 0;
        int nullableMismatch = 0;
        List<String> failures = new ArrayList<>();

        StringBuilder report = new StringBuilder();
        report.append("file\tparse\ttable\tcols\texpected\tmissing\textra\ttypeΔ\tcommentΔ\tnullΔ\tmodel\tpart\tcomment\n");

        for (Path file : files) {
            String sql = Files.readString(file);
            String rel = INIT.relativize(file).toString();
            List<ExpectedColumn> expected = expectedColumns(sql);

            String parseStatus = "ok";
            CreateTable create = null;
            try {
                List<Statement> statements = DatabaseTypeEnum.DORIS.parse(sql);
                parseOk++;
                for (Statement statement : statements) {
                    if (statement instanceof CreateTable ct) {
                        create = ct;
                        break;
                    }
                }
            } catch (Exception e) {
                parseStatus = "FAIL:" + compact(e.getMessage());
                failures.add(rel + " parse " + parseStatus);
            }

            if (create == null) {
                report.append(rel).append('\t').append(parseStatus)
                        .append("\t-\t0\t").append(expected.size())
                        .append("\t-\t-\t-\t-\t-\t-\t-\t-\n");
                if ("ok".equals(parseStatus)) {
                    failures.add(rel + " 未识别为 CreateTable");
                }
                continue;
            }
            createOk++;

            List<ColumnRel> actual = create.getColumnRels() == null ? List.of() : create.getColumnRels();
            Set<String> actualNames = new LinkedHashSet<>();
            actual.forEach(c -> actualNames.add(norm(c.getColumnName())));
            Set<String> expectedNames = new LinkedHashSet<>();
            expected.forEach(c -> expectedNames.add(c.name));

            Set<String> missing = diff(expectedNames, actualNames);
            Set<String> extra = diff(actualNames, expectedNames);
            if (missing.isEmpty() && extra.isEmpty()) {
                exactNameMatch++;
            } else {
                failures.add(rel + " columns missing=" + missing + " extra=" + extra);
            }

            int typeDelta = 0;
            int commentDelta = 0;
            int nullDelta = 0;
            for (ExpectedColumn exp : expected) {
                ColumnRel got = find(actual, exp.name);
                if (got == null) {
                    continue;
                }
                if (!typesEqual(exp.type, got.getTypeName())) {
                    typeDelta++;
                    failures.add(rel + " type " + exp.name + " expect=" + exp.type
                            + " got=" + got.getTypeName());
                }
                if (!commentsEqual(exp.comment, got.getComment())) {
                    commentDelta++;
                    failures.add(rel + " comment " + exp.name + " expect=" + exp.comment
                            + " got=" + got.getComment());
                }
                if (exp.nullable != null && exp.nullable != got.getNullable()) {
                    nullDelta++;
                    failures.add(rel + " nullable " + exp.name + " expect=" + exp.nullable
                            + " got=" + got.getNullable());
                }
            }
            typeMismatch += typeDelta;
            commentMismatch += commentDelta;
            nullableMismatch += nullDelta;

            DdlMetadataProvider ddl = new DdlMetadataProvider(List.of(create));
            if (ddl.allTables().isEmpty()) {
                failures.add(rel + " DdlMetadataProvider 未抽出表");
            }
            List<DdlCatalogExtractor.ExtractedTable> catalog =
                    DdlCatalogExtractor.extract(List.of(create), "doris");
            if (catalog.size() != 1 || catalog.getFirst().columns().size() != actual.size()) {
                failures.add(rel + " DdlCatalogExtractor 列数不一致");
            }

            report.append(rel).append('\t')
                    .append(parseStatus).append('\t')
                    .append(create.getTableId()).append('\t')
                    .append(actual.size()).append('\t')
                    .append(expected.size()).append('\t')
                    .append(String.join(",", missing)).append('\t')
                    .append(String.join(",", extra)).append('\t')
                    .append(typeDelta).append('\t')
                    .append(commentDelta).append('\t')
                    .append(nullDelta).append('\t')
                    .append(create.getModelType()).append('\t')
                    .append(create.getPartitionColumnNames()).append('\t')
                    .append(create.getComment() == null ? "" : create.getComment())
                    .append('\n');
        }

        System.out.println("===== Doris init DDL accuracy =====");
        System.out.println("files=" + files.size()
                + " parseOk=" + parseOk
                + " createOk=" + createOk
                + " nameMatch=" + exactNameMatch
                + " typeΔ=" + typeMismatch
                + " commentΔ=" + commentMismatch
                + " nullΔ=" + nullableMismatch);
        System.out.println(report);
        if (!failures.isEmpty()) {
            System.out.println("----- mismatches -----");
            failures.forEach(System.out::println);
        }
    }

    private static List<ExpectedColumn> expectedColumns(String sql) {
        String body = columnList(SqlUtils.removeComments(sql));
        List<String> parts = splitColumns(body);
        List<ExpectedColumn> out = new ArrayList<>();
        for (String part : parts) {
            ExpectedColumn col = parseExpected(part.strip());
            if (col != null) {
                out.add(col);
            }
        }
        return out;
    }

    /** 建表语句第一对括号里的列定义。 */
    private static String columnList(String sql) {
        String lower = sql.toLowerCase(Locale.ROOT);
        int create = lower.indexOf("create table");
        if (create < 0) {
            return "";
        }
        int open = sql.indexOf('(', create);
        if (open < 0) {
            return "";
        }
        int depth = 0;
        for (int i = open; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return sql.substring(open + 1, i);
                }
            }
        }
        return "";
    }

    private static List<String> splitColumns(String body) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (!inSingle && !inDouble) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    parts.add(cur.toString());
                    cur.setLength(0);
                    continue;
                }
            }
            cur.append(c);
        }
        if (!cur.toString().isBlank()) {
            parts.add(cur.toString());
        }
        return parts;
    }

    private static ExpectedColumn parseExpected(String fragment) {
        String[] tokens = fragment.trim().split("\\s+");
        if (tokens.length < 2) {
            return null;
        }
        String name = tokens[0].replace("`", "").toLowerCase(Locale.ROOT);
        if (name.equals("index") || name.equals("key") || name.equals("unique")
                || name.equals("aggregate") || name.equals("duplicate")) {
            return null;
        }
        String type = tokens[1];
        String rest = fragment.substring(fragment.indexOf(tokens[1]) + tokens[1].length());
        Boolean nullable = null;
        String restLower = rest.toLowerCase(Locale.ROOT);
        if (restLower.matches("(?s).*\\bnot\\s+null\\b.*")) {
            nullable = false;
        } else if (restLower.matches("(?s).*\\bnull\\b.*")) {
            nullable = true;
        }
        String comment = extractComment(rest);
        return new ExpectedColumn(name, type, comment, nullable);
    }

    private static String extractComment(String rest) {
        int c1 = indexOfWord(rest, "comment");
        if (c1 < 0) {
            return null;
        }
        String after = rest.substring(c1 + "comment".length()).strip();
        if (after.isEmpty()) {
            return null;
        }
        char q = after.charAt(0);
        if (q != '\'' && q != '"') {
            return after.replaceAll("[,;].*$", "").strip();
        }
        int end = after.indexOf(q, 1);
        if (end < 0) {
            return after.substring(1).strip();
        }
        return after.substring(1, end);
    }

    private static int indexOfWord(String text, String word) {
        String lower = text.toLowerCase(Locale.ROOT);
        int from = 0;
        while (from < lower.length()) {
            int at = lower.indexOf(word, from);
            if (at < 0) {
                return -1;
            }
            boolean startOk = at == 0 || !Character.isLetterOrDigit(lower.charAt(at - 1));
            int end = at + word.length();
            boolean endOk = end >= lower.length() || !Character.isLetterOrDigit(lower.charAt(end));
            if (startOk && endOk) {
                return at;
            }
            from = at + 1;
        }
        return -1;
    }

    private static ColumnRel find(List<ColumnRel> columns, String name) {
        for (ColumnRel column : columns) {
            if (norm(column.getColumnName()).equals(name)) {
                return column;
            }
        }
        return null;
    }

    private static boolean typesEqual(String expected, String actual) {
        return normType(expected).equals(normType(actual));
    }

    private static String normType(String type) {
        if (type == null) {
            return "";
        }
        return type.replace(" ", "").toLowerCase(Locale.ROOT);
    }

    private static boolean commentsEqual(String expected, String actual) {
        return normalizeComment(expected).equals(normalizeComment(actual));
    }

    private static String normalizeComment(String comment) {
        return comment == null ? "" : comment.strip();
    }

    private static Set<String> diff(Set<String> left, Set<String> right) {
        Set<String> out = new LinkedHashSet<>(left);
        out.removeAll(right);
        return out;
    }

    private static String norm(String name) {
        return name == null ? "" : name.replace("`", "").toLowerCase(Locale.ROOT);
    }

    private static String compact(String message) {
        if (message == null) {
            return "null";
        }
        return message.replace('\n', ' ').replace('\t', ' ').strip();
    }

    private record ExpectedColumn(String name, String type, String comment, Boolean nullable) {}
}
