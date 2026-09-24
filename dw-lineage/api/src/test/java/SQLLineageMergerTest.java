/**
 * @description:
 * @projectName:demo
 * @see:PACKAGE_NAME
 * @author:wang
 * @createTime:2025/3/31 17:09
 * @version:1.0
 */

import org.junit.Test;
import com.dwai.lineage.util.SQLLineageMerger;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SQLLineageMergerTest {

    @Test
    public void test01() {
        String lineageJson = "{\n" +
                "  \"catalogName\" : null,\n" +
                "  \"schema\" : \"default\",\n" +
                "  \"table\" : \"PROCESSED_MDM_PRODUCT_ENRICHMENT\",\n" +
                "  \"columns\" : [ {\n" +
                "    \"column\" : \"PROD_ID\",\n" +
                "    \"sourceColumns\" : [ {\n" +
                "      \"tableName\" : \"default.RETEK_XX_ITEM_ATTR_TRANSLATE_PRODUCT_ENRICHMENT\",\n" +
                "      \"columnName\" : \"ITEM\"\n" +
                "    } ]\n" +
                "  }, {\n" +
                "    \"column\" : \"ENRICHMENT_ID\",\n" +
                "    \"sourceColumns\" : [ {\n" +
                "      \"tableName\" : \"default.RETEK_XX_ITEM_ATTR_TRANSLATE_PRODUCT_ENRICHMENT\",\n" +
                "      \"columnName\" : \"ENRICHMENT_ID\"\n" +
                "    } ]\n" +
                "  } ]\n" +
                "}";

        List<String> lineageStrings = Arrays.asList(lineageJson);

        String result = SQLLineageMerger.mergeSQLLineage(lineageStrings);
        System.out.println(result);
    }


    @Test
    public void test2() {
        String lineageJson = "{\n" +
                "  \"catalogName\" : null,\n" +
                "  \"schema\" : \"default\",\n" +
                "  \"table\" : \"cc\",\n" +
                "  \"columns\" : [ {\n" +
                "    \"column\" : \"ITEM\",\n" +
                "    \"sourceColumns\" : [ {\n" +
                "      \"tableName\" : \"tmp.bb\",\n" +
                "      \"columnName\" : \"ITEM\"\n" +
                "    } ]\n" +
                "  }, {\n" +
                "    \"column\" : \"ENRICHMENT_ID\",\n" +
                "    \"sourceColumns\" : [ {\n" +
                "      \"tableName\" : \"tmp.bb\",\n" +
                "      \"columnName\" : \"ENRICHMENT_ID\"\n" +
                "    } ]\n" +
                "  } ]\n" +
                "}";
        String lineageJson2 ="{\n" +
                "  \"catalogName\" : null,\n" +
                "  \"schema\" : \"tmp\",\n" +
                "  \"table\" : \"bb\",\n" +
                "  \"columns\" : [ {\n" +
                "    \"column\" : \"ITEM\",\n" +
                "    \"sourceColumns\" : [ {\n" +
                "      \"tableName\" : \"stg.aa\",\n" +
                "      \"columnName\" : \"ITEM\"\n" +
                "    } ]\n" +
                "  }, {\n" +
                "    \"column\" : \"ENRICHMENT_ID\",\n" +
                "    \"sourceColumns\" : [ {\n" +
                "      \"tableName\" : \"stg.aa\",\n" +
                "      \"columnName\" : \"ENRICHMENT_ID\"\n" +
                "    } ]\n" +
                "  } ]\n" +
                "}";
        List<String> lineageStrings =new ArrayList();
        lineageStrings.add(lineageJson);
        lineageStrings.add(lineageJson2);

        String result = SQLLineageMerger.mergeSQLLineage(lineageStrings);
        System.out.println(result);
    }

    @Test
    public void test3() {
        String lineageJson = "{\n" +
                "  \"catalogName\" : null,\n" +
                "  \"schema\" : \"default\",\n" +
                "  \"table\" : \"cc\",\n" +
                "  \"columns\" : [ {\n" +
                "    \"column\" : \"ITEM\",\n" +
                "    \"sourceColumns\" : [ {\n" +
                "      \"tableName\" : \"tmp.bb\",\n" +
                "      \"columnName\" : \"ITEM\"\n" +
                "    } ]\n" +
                "  }, {\n" +
                "    \"column\" : \"ENRICHMENT_ID\",\n" +
                "    \"sourceColumns\" : [ {\n" +
                "      \"tableName\" : \"tmp.bb\",\n" +
                "      \"columnName\" : \"ENRICHMENT_ID\"\n" +
                "    } ]\n" +
                "  } ]\n" +
                "}";
        String lineageJson2 ="{\n" +
                "  \"catalogName\" : null,\n" +
                "  \"schema\" : \"tmp\",\n" +
                "  \"table\" : \"bb\",\n" +
                "  \"columns\" : [ {\n" +
                "    \"column\" : \"ITEM\",\n" +
                "    \"sourceColumns\" : [ {\n" +
                "      \"tableName\" : \"stg.aa\",\n" +
                "      \"columnName\" : \"ITEM\"\n" +
                "    } ]\n" +
                "  }, {\n" +
                "    \"column\" : \"ENRICHMENT_ID\",\n" +
                "    \"sourceColumns\" : [ {\n" +
                "      \"tableName\" : \"stg.aa\",\n" +
                "      \"columnName\" : \"ENRICHMENT_ID\"\n" +
                "    } ]\n" +
                "  } ]\n" +
                "}";
        List<String> lineageStrings =new ArrayList();
        lineageStrings.add(lineageJson);
        lineageStrings.add(lineageJson2);

        String result = SQLLineageMerger.mergeSQLLineage(lineageStrings,"ENRICHMENT_ID");
        System.out.println(result);
    }
}