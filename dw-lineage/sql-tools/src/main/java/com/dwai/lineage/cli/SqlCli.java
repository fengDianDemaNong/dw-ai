package com.dwai.lineage.cli;

import com.dwai.lineage.conf.DatabaseProperties;
import com.dwai.lineage.util.SqlUtils;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 内置 SQL 命令行工具，用法与 beeline / trino cli 类似。
 *
 * <p>存在的意义：初始化数据库、排查问题时不必在服务器上另外安装
 * {@code mysql} / {@code psql} 客户端 —— 驱动已经打在应用 jar 里，
 * 有 JDK 就能用，且连接信息可直接复用 {@code conf/application.yml}。
 *
 * <pre>
 * bin/sql-cli.sh                      交互式，连接 conf 中配置的数据库
 * bin/sql-cli.sh -e "select 1"        执行一条 SQL
 * bin/sql-cli.sh -f sql/mysql/01_schema.sql   执行脚本
 * bin/sql-cli.sh -u jdbc:... -n user -p pass  直连指定库
 * </pre>
 */
public final class SqlCli {

    private static final String PROMPT = "sql> ";
    private static final String CONTINUE_PROMPT = "  -> ";

    private SqlCli() {
    }

    public static void main(String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("参数错误: " + e.getMessage());
            printUsage();
            System.exit(2);
            return;
        }

        if (options.help) {
            printUsage();
            return;
        }

        try (Connection conn = connect(options)) {
            if (options.execute != null) {
                System.exit(runScript(conn, options.execute, options) ? 0 : 1);
            } else if (options.file != null) {
                String content = Files.readString(Path.of(options.file), StandardCharsets.UTF_8);
                System.out.println("执行脚本: " + options.file);
                System.exit(runScript(conn, content, options) ? 0 : 1);
            } else {
                interactive(conn, options);
            }
        } catch (SQLException e) {
            System.err.println("数据库错误: " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("读取文件失败: " + e.getMessage());
            System.exit(1);
        }
    }

    // ==================================================================
    // 连接
    // ==================================================================

    private static Connection connect(Options options) throws SQLException {
        String url = options.url;
        String user = options.username;
        String password = options.password;

        if (url == null) {
            // 未显式指定连接串时，从 conf/application.yml 推导，避免重复填写
            DatabaseProperties props = ConfLoader.load(options.conf);
            url = props.resolveUrl();
            user = user != null ? user : props.resolveUsername();
            password = password != null ? password : props.resolvePassword();
            System.out.println("使用配置文件中的数据库: " + props.getType());
        }

        System.out.println("连接: " + mask(url));
        Connection conn = DriverManager.getConnection(url, user, password);
        conn.setAutoCommit(true);
        return conn;
    }

    /**
     * 抹掉 JDBC URL 里的密码，供打印。
     *
     * <p>包内可见是为了能被测试直接覆盖 —— 这个方法一旦失效，
     * 密码就会明文出现在终端和日志里，属于「坏了必须立刻知道」的那类。
     */
    static String mask(String url) {
        return url.replaceAll("(?i)(password=)[^&;]*", "$1***");
    }

    // ==================================================================
    // 执行
    // ==================================================================

    /**
     * 执行一段可能包含多条语句的 SQL。
     *
     * <p>用项目已有的 {@link SqlUtils#splitSql} 拆分，它会正确处理注释和引号内的分号。
     *
     * @return 是否全部成功
     */
    private static boolean runScript(Connection conn, String script, Options options) {
        List<String> statements;
        try {
            statements = new SqlUtils().splitSql(script);
        } catch (Exception e) {
            System.err.println("SQL 拆分失败: " + e.getMessage());
            return false;
        }

        int ok = 0;
        int failed = 0;
        for (String sql : statements) {
            String trimmed = sql.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (runOne(conn, trimmed, options.verbose)) {
                ok++;
            } else {
                failed++;
                if (!options.force) {
                    System.err.println("已中止。加 --force 可忽略错误继续执行。");
                    return false;
                }
            }
        }

        if (statements.size() > 1) {
            System.out.printf("共 %d 条语句，成功 %d，失败 %d%n", ok + failed, ok, failed);
        }
        return failed == 0;
    }

    private static boolean runOne(Connection conn, String sql, boolean verbose) {
        long start = System.currentTimeMillis();
        if (verbose) {
            System.out.println("> " + oneLine(sql));
        }
        try (Statement st = conn.createStatement()) {
            boolean hasResultSet = st.execute(sql);
            double seconds = (System.currentTimeMillis() - start) / 1000.0;

            if (hasResultSet) {
                try (ResultSet rs = st.getResultSet()) {
                    int rows = printTable(rs);
                    System.out.printf("%d 行 (%.3f 秒)%n%n", rows, seconds);
                }
            } else {
                int affected = st.getUpdateCount();
                System.out.printf("OK, %d 行受影响 (%.3f 秒)%n", Math.max(affected, 0), seconds);
            }
            return true;
        } catch (SQLException e) {
            System.err.println("错误: " + e.getMessage());
            if (!verbose) {
                System.err.println("  语句: " + oneLine(sql));
            }
            return false;
        }
    }

    private static String oneLine(String sql) {
        String s = sql.replaceAll("\\s+", " ").trim();
        return s.length() > 160 ? s.substring(0, 160) + " ..." : s;
    }

    // ==================================================================
    // 交互模式
    // ==================================================================

    private static void interactive(Connection conn, Options options) {
        System.out.println("输入 SQL 以分号结束执行；输入 !help 查看帮助，!quit 退出。");
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        StringBuilder buffer = new StringBuilder();

        while (true) {
            System.out.print(buffer.isEmpty() ? PROMPT : CONTINUE_PROMPT);
            System.out.flush();

            String line;
            try {
                line = reader.readLine();
            } catch (IOException e) {
                break;
            }
            if (line == null) {
                break;
            }

            String trimmed = line.trim();
            if (buffer.isEmpty() && trimmed.startsWith("!")) {
                if (handleCommand(trimmed, conn)) {
                    break;
                }
                continue;
            }
            if (buffer.isEmpty() && (trimmed.equalsIgnoreCase("quit") || trimmed.equalsIgnoreCase("exit"))) {
                break;
            }

            buffer.append(line).append('\n');
            // 以分号结尾才认为语句写完，支持多行输入
            if (trimmed.endsWith(";")) {
                runScript(conn, buffer.toString(), options);
                buffer.setLength(0);
            }
        }
        System.out.println("再见。");
    }

    /** @return 是否退出 */
    private static boolean handleCommand(String command, Connection conn) {
        String cmd = command.toLowerCase();
        switch (cmd) {
            case "!quit", "!exit", "!q" -> {
                return true;
            }
            case "!help", "!h" -> {
                System.out.println("""
                        !tables      列出当前库的表
                        !info        显示数据库版本信息
                        !help        显示本帮助
                        !quit        退出
                        直接输入 SQL，以分号结束即执行。""");
            }
            case "!tables" -> listTables(conn);
            case "!info" -> showInfo(conn);
            default -> System.out.println("未知命令: " + command + "（!help 查看帮助）");
        }
        return false;
    }

    private static void listTables(Connection conn) {
        try (ResultSet rs = conn.getMetaData().getTables(
                conn.getCatalog(), null, "%", new String[]{"TABLE"})) {
            List<String> names = new ArrayList<>();
            while (rs.next()) {
                names.add(rs.getString("TABLE_NAME"));
            }
            names.forEach(n -> System.out.println("  " + n));
            System.out.println(names.size() + " 张表");
        } catch (SQLException e) {
            System.err.println("错误: " + e.getMessage());
        }
    }

    private static void showInfo(Connection conn) {
        try {
            var md = conn.getMetaData();
            System.out.println("  数据库: " + md.getDatabaseProductName() + " " + md.getDatabaseProductVersion());
            System.out.println("  驱动:   " + md.getDriverName() + " " + md.getDriverVersion());
            System.out.println("  URL:    " + mask(md.getURL()));
        } catch (SQLException e) {
            System.err.println("错误: " + e.getMessage());
        }
    }

    // ==================================================================
    // 结果集输出
    // ==================================================================

    /** 输出为对齐的表格，风格与 beeline 一致。 */
    private static int printTable(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();

        List<String> headers = new ArrayList<>();
        for (int i = 1; i <= cols; i++) {
            headers.add(md.getColumnLabel(i));
        }

        List<List<String>> rows = new ArrayList<>();
        while (rs.next()) {
            List<String> row = new ArrayList<>(cols);
            for (int i = 1; i <= cols; i++) {
                Object v = rs.getObject(i);
                row.add(v == null ? "NULL" : String.valueOf(v));
            }
            rows.add(row);
        }

        int[] widths = new int[cols];
        for (int i = 0; i < cols; i++) {
            widths[i] = displayWidth(headers.get(i));
        }
        for (List<String> row : rows) {
            for (int i = 0; i < cols; i++) {
                widths[i] = Math.max(widths[i], displayWidth(row.get(i)));
            }
        }

        String separator = buildSeparator(widths);
        System.out.println(separator);
        System.out.println(buildRow(headers, widths));
        System.out.println(separator);
        for (List<String> row : rows) {
            System.out.println(buildRow(row, widths));
        }
        System.out.println(separator);
        return rows.size();
    }

    private static String buildSeparator(int[] widths) {
        StringBuilder sb = new StringBuilder("+");
        for (int w : widths) {
            sb.append("-".repeat(w + 2)).append('+');
        }
        return sb.toString();
    }

    private static String buildRow(List<String> cells, int[] widths) {
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < cells.size(); i++) {
            String cell = cells.get(i);
            sb.append(' ').append(cell)
                    .append(" ".repeat(widths[i] - displayWidth(cell)))
                    .append(" |");
        }
        return sb.toString();
    }

    /** 中日韩字符占两列，按显示宽度对齐才不会错位。 */
    /** 包内可见：中文对齐是这个 CLI 的主要卖点之一，值得单独测。 */
    static int displayWidth(String s) {
        // 调用方目前都保证非空（printTable 把 NULL 单元格换成了字面量 "NULL"），
        // 但排版工具崩在渲染中途会把整个结果集吞掉，不值得为省一行赌这个
        if (s == null) {
            return 0;
        }
        int width = 0;
        for (int i = 0; i < s.length(); i++) {
            width += isWide(s.charAt(i)) ? 2 : 1;
        }
        return width;
    }

    private static boolean isWide(char c) {
        return (c >= 0x1100 && c <= 0x115F)
                || (c >= 0x2E80 && c <= 0xA4CF)
                || (c >= 0xAC00 && c <= 0xD7A3)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFE30 && c <= 0xFE6F)
                || (c >= 0xFF00 && c <= 0xFF60)
                || (c >= 0xFFE0 && c <= 0xFFE6);
    }

    // ==================================================================
    // 参数
    // ==================================================================

    private static void printUsage() {
        System.out.println("""
                SQL 命令行工具

                用法:
                  sql-cli.sh [选项]

                选项:
                  -u, --url <jdbc-url>     JDBC 连接串；不指定则读 conf/application.yml
                  -n, --username <user>    用户名
                  -p, --password <pass>    密码
                  -e, --execute <sql>      执行给定 SQL 后退出
                  -f, --file <path>        执行 SQL 脚本文件后退出
                  -c, --conf <path>        指定配置文件，默认 conf/application.yml
                      --force              脚本中某条语句失败时继续执行后续语句
                  -v, --verbose            打印执行的每条语句
                  -h, --help               显示帮助

                示例:
                  sql-cli.sh
                  sql-cli.sh -e "select count(*) from lineage_version"
                  sql-cli.sh -f sql/mysql/01_schema.sql
                  sql-cli.sh -u jdbc:mysql://host:3306/db -n root -p secret""");
    }

    /** 包内可见：参数解析的错误分支要能被测试直接触发。 */
    static final class Options {
        String url;
        String username;
        String password;
        String execute;
        String file;
        String conf;
        boolean force;
        boolean verbose;
        boolean help;

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "-u", "--url" -> o.url = next(args, ++i, a);
                    case "-n", "--username" -> o.username = next(args, ++i, a);
                    case "-p", "--password" -> o.password = next(args, ++i, a);
                    case "-e", "--execute" -> o.execute = next(args, ++i, a);
                    case "-f", "--file" -> o.file = next(args, ++i, a);
                    case "-c", "--conf" -> o.conf = next(args, ++i, a);
                    case "--force" -> o.force = true;
                    case "-v", "--verbose" -> o.verbose = true;
                    case "-h", "--help" -> o.help = true;
                    default -> throw new IllegalArgumentException("未知选项 " + a);
                }
            }
            if (o.execute != null && o.file != null) {
                throw new IllegalArgumentException("-e 与 -f 不能同时使用");
            }
            // 密码为空时交互式读取，避免出现在命令历史与进程列表中
            if (o.password == null && o.url != null && o.username != null) {
                Console console = System.console();
                if (console != null) {
                    char[] pwd = console.readPassword("密码: ");
                    o.password = pwd == null ? "" : new String(pwd);
                }
            }
            return o;
        }

        private static String next(String[] args, int i, String option) {
            if (i >= args.length) {
                throw new IllegalArgumentException(option + " 缺少参数值");
            }
            return args[i];
        }
    }
}
