package lineage;

import io.github.melin.superior.common.relational.Statement;
import io.github.melin.superior.common.relational.dml.InsertTable;
import org.junit.Test;
import com.dwai.lineage.dto.LineageGraph;
import com.dwai.lineage.dto.TableLineageResponse;
import com.dwai.lineage.enums.DatabaseTypeEnum;
import com.dwai.lineage.service.SqlParseExecutor;
import com.dwai.lineage.service.TableLineageService;
import com.dwai.lineage.service.impl.LineageServiceImpl;
import com.dwai.lineage.service.metadata.MetadataServiceFactory;
import com.dwai.lineage.util.SqlUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assume.assumeTrue;

/**
 * 用现网 dw/ops 的 Doris SQL 做一次解析对照，不作为默认 CI 门槛。
 * 目录不存在时跳过。
 */
public class DorisOpsSqlAccuracyTest {

    private static final Path OPS = Path.of(
            "/Users/wang/Downloads/data/git-repo/projects/dw/ops");

    private static final Pattern INSERT_INTO = Pattern.compile(
            "(?is)\\bINSERT\\s+INTO\\s+([`\\w.]+)");
    private static final Pattern DELETE_FROM = Pattern.compile(
            "(?is)\\bDELETE\\s+FROM\\s+([`\\w.]+)");
    private static final Pattern FROM_OR_JOIN = Pattern.compile(
            "(?is)\\b(?:FROM|JOIN)\\s+([`\\w.]+)");
    private static final Pattern CTE_NAME = Pattern.compile(
            "(?is)(?:WITH|,)\\s*([`\\w]+)\\s+AS\\s*\\(");

    @Test
    public void reportDorisOpsSqlAccuracy() throws Exception {
        assumeTrue("ops 目录不存在，跳过现网 SQL 对照", Files.isDirectory(OPS));

        MetadataServiceFactory factory = new MetadataServiceFactory();
        SqlParseExecutor executor = new SqlParseExecutor();
        TableLineageService tableLineage = new TableLineageService(factory, executor);
        LineageServiceImpl columnLineage = new LineageServiceImpl(factory, executor);
        columnLineage.init();

        List<Path> files;
        try (Stream<Path> walk = Files.walk(OPS)) {
            files = walk.filter(p -> p.toString().endsWith(".sql")).sorted().toList();
        }
        assertFalse("ops 下应有 sql 文件", files.isEmpty());

        int parseOk = 0;
        int tableOk = 0;
        int columnOk = 0;
        int insertRecognized = 0;
        int sourceRecallHits = 0;
        int sourceRecallTotal = 0;

        StringBuilder report = new StringBuilder();
        report.append("file\tparse\tstmts\tinsert\ttable_out\ttable_in\texpected_src\tmissing_src\textra_src\tcol_edges\tcol_fail\tunresolved\twarn\n");

        for (Path file : files) {
            String sql = Files.readString(file);
            String rel = OPS.relativize(file).toString();

            String parseStatus = "ok";
            int stmtCount = 0;
            int insertCount = 0;
            try {
                List<Statement> statements = DatabaseTypeEnum.DORIS.parse(sql);
                stmtCount = statements.size();
                insertCount = (int) statements.stream().filter(s -> s instanceof InsertTable).count();
                parseOk++;
                if (insertCount > 0) {
                    insertRecognized++;
                }
            } catch (Exception e) {
                parseStatus = "FAIL:" + compact(e.getMessage());
            }

            Set<String> expectedTargets = names(INSERT_INTO, sql);
            expectedTargets.addAll(names(DELETE_FROM, sql));
            Set<String> cte = names(CTE_NAME, sql);
            Set<String> expectedSources = names(FROM_OR_JOIN, sql);
            expectedSources.removeAll(cte);
            expectedSources.removeAll(expectedTargets);

            String tableOut = "-";
            String tableIn = "-";
            Set<String> missing = Set.of();
            Set<String> extra = Set.of();
            try {
                TableLineageResponse table = tableLineage.analyze("doris", sql);
                tableOk++;
                tableOut = String.join(",", table.statements().stream()
                        .flatMap(s -> s.outputTables().stream()).toList());
                tableIn = String.join(",", table.statements().stream()
                        .flatMap(s -> s.inputTables().stream()).toList());
                Set<String> parsedSources = new LinkedHashSet<>();
                table.statements().forEach(s -> parsedSources.addAll(normalizeAll(s.inputTables())));
                Set<String> want = normalizeAll(expectedSources);
                missing = diff(want, parsedSources);
                extra = diff(parsedSources, want);
                sourceRecallTotal += want.size();
                sourceRecallHits += want.size() - missing.size();
            } catch (Exception e) {
                tableOut = "FAIL:" + compact(e.getMessage());
            }

            int colEdges = 0;
            int colFail = 0;
            int unresolved = 0;
            int warnings = 0;
            try {
                LineageGraph graph = columnLineage.analyzeSqlLineage("doris", false, null, sql);
                columnOk++;
                if (graph.data() != null && graph.data().withProcessData() != null
                        && graph.data().withProcessData().data() != null) {
                    colEdges = graph.data().withProcessData().data().size();
                }
                colFail = graph.failedStatements() == null ? 0 : graph.failedStatements().size();
                unresolved = graph.unresolvedTables() == null ? 0 : graph.unresolvedTables().size();
                warnings = graph.warnings() == null ? 0 : graph.warnings().size();
            } catch (Exception e) {
                colFail = -1;
                report.append(rel).append("\t").append(parseStatus).append("\t")
                        .append(stmtCount).append("\t").append(insertCount).append("\t")
                        .append(tableOut).append("\t").append(tableIn).append("\t")
                        .append(String.join(",", expectedSources)).append("\t")
                        .append(String.join(",", missing)).append("\t")
                        .append(String.join(",", extra)).append("\t")
                        .append("THROW:").append(compact(e.getMessage())).append("\t-1\t-\t-\n");
                System.out.println(report.substring(report.lastIndexOf("\n", report.length() - 2) + 1));
                continue;
            }

            report.append(rel).append('\t')
                    .append(parseStatus).append('\t')
                    .append(stmtCount).append('\t')
                    .append(insertCount).append('\t')
                    .append(tableOut).append('\t')
                    .append(tableIn).append('\t')
                    .append(String.join(",", expectedSources)).append('\t')
                    .append(String.join(",", missing)).append('\t')
                    .append(String.join(",", extra)).append('\t')
                    .append(colEdges).append('\t')
                    .append(colFail).append('\t')
                    .append(unresolved).append('\t')
                    .append(warnings).append('\n');
        }

        System.out.println("===== Doris ops SQL accuracy =====");
        System.out.println("files=" + files.size()
                + " parseOk=" + parseOk
                + " insertRecognized=" + insertRecognized
                + " tableOk=" + tableOk
                + " columnOk=" + columnOk
                + " sourceRecall=" + sourceRecallHits + "/" + sourceRecallTotal);
        System.out.println(report);

        List<String> splitSample = new SqlUtils().splitSql(Files.readString(files.getFirst()));
        System.out.println("split first file stmts=" + splitSample.size());
    }

    private static Set<String> names(Pattern pattern, String sql) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = pattern.matcher(sql);
        while (m.find()) {
            String name = m.group(1).replace("`", "").toLowerCase(Locale.ROOT);
            if (name.isBlank() || name.equals("select") || name.equals("lateral")) {
                continue;
            }
            out.add(name);
        }
        return out;
    }

    private static Set<String> normalizeAll(Iterable<String> names) {
        Set<String> out = new LinkedHashSet<>();
        for (String name : names) {
            out.add(name.replace("`", "").toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static Set<String> diff(Set<String> left, Set<String> right) {
        Set<String> out = new LinkedHashSet<>(left);
        out.removeAll(right);
        return out;
    }

    private static String compact(String message) {
        if (message == null) {
            return "null";
        }
        return message.replace('\n', ' ').replace('\t', ' ').strip();
    }
}
