import org.junit.Test;
import com.dwai.lineage.util.SqlUtils;

import java.util.List;

/**
 * @description:
 * @projectName:sql-tools
 * @see:PACKAGE_NAME
 * @author:wang
 * @createTime:2025/5/21 15:03
 * @version:1.0
 */
public class TestSqlSplit {

    @Test
    public void testCommentAtEnd() {
        String sql = "\n" +
                "select\n" +
                "  'one'\n" +
                "  , 'two' --comment\n;" +
                "select\n" +
                "  'ttt'\n" +
                "  , '444' -- comment\n;" +
                "select 1;" +
                "select '--11' as a,--111\n" +
                "'- 11' ,-- 111\n" +
                "'-- 11' as b,-- 111 \n" +
                "\"--11\" as c,--  111\n ;";
        SqlUtils sqltools = new SqlUtils();
        List<String> sqls = sqltools.splitSql(sql);
        System.out.println(sql);
        for (String s : sqls) {
            System.out.println("==========");
            System.out.println(s);
        }
        System.out.println(sqls.size());

        System.out.println(SqlUtils.removeComments(sql));
    }
}
