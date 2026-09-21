package gravitino;


import org.apache.gravitino.*;
import org.apache.gravitino.client.GravitinoAdminClient;
import org.apache.gravitino.client.GravitinoClient;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.TableCatalog;

import java.util.Arrays;

/**
 * @description:
 * @projectName:sql-tools
 * @see:gravitino
 * @author:wang
 * @createTime:2025/5/16 11:55
 * @version:1.0
 */
public class Test {

    String url = System.getProperty("gravitino.url", "http://localhost:8090");


    @org.junit.Test
    public void test00() {
        GravitinoAdminClient gravitinoAdminClient = GravitinoAdminClient
                .builder(url)
                .build();

        GravitinoMetalake[] gravitinoMetalakes = gravitinoAdminClient.listMetalakes();
        for (GravitinoMetalake gravitinoMetalake : gravitinoMetalakes) {
            println("============");
            println(gravitinoMetalake.name());
            GravitinoMetalake loaded = gravitinoAdminClient.loadMetalake(gravitinoMetalake.name());
            String[] catalogs = loaded.listCatalogs();
            for (String catalog : catalogs) {
                Catalog loadCatalog = loaded.loadCatalog(catalog);
                String[] listSchemas = loadCatalog.asSchemas().listSchemas();
                for (String schema : listSchemas) {
                    if(loadCatalog instanceof TableCatalog){
                        TableCatalog tableCatalog = loadCatalog.asTableCatalog();
                        NameIdentifier[] nameIdentifiers = tableCatalog.listTables(Namespace.of(schema));
                        for (NameIdentifier nameIdentifier : nameIdentifiers) {
                            Table table = tableCatalog.loadTable(nameIdentifier);
                            println(catalog+"."+table.name()+","+table.comment());
                        }
                    }
                }
            }
        }

    }

    @org.junit.Test
    public void test01() {
        GravitinoClient gravitinoClient = GravitinoClient
                .builder(url)
                .withMetalake("test")
                .build();

        // 获取所有catalogs
        String[] catalogNames = gravitinoClient.listCatalogs();
        for (String catalogName : catalogNames) {
            println(catalogName);
        }

    }


    @org.junit.Test
    public void test02() {
        GravitinoClient gravitinoClient = GravitinoClient
                .builder(url)
                .withMetalake("test")
                .build();
        Catalog catalog = gravitinoClient.loadCatalog("doris_test");
//      获取指定catalog下的所有scheams
        final String[] listSchemas = catalog.asSchemas().listSchemas();
        for (String schema : listSchemas) {
            println(schema);
        }

    }

    @org.junit.Test
    public void test03() {
        GravitinoClient gravitinoClient = GravitinoClient
                .builder(url)
                .withMetalake("test")
                .build();


        Catalog catalog = gravitinoClient.loadCatalog("doris_test");
        TableCatalog tableCatalog = catalog.asTableCatalog();
        // 获取指定catalog下的指定scheam下的所有表
        NameIdentifier[] identifiers =
                tableCatalog.listTables(Namespace.of("dwd"));
        for (NameIdentifier identifier : identifiers) {
            Table table = tableCatalog.loadTable(identifier);
            println(table.name()+","+table.comment());
        }

    }


    @org.junit.Test
    public void test04() {
        GravitinoClient gravitinoClient = GravitinoClient
                .builder(url)
                .withMetalake("test")
                .build();


        Catalog catalog = gravitinoClient.loadCatalog("doris_test");
        TableCatalog tableCatalog = catalog.asTableCatalog();
        // 获取指定catalog下的指定scheam下指定表的所有字段
        Table table = tableCatalog.loadTable(NameIdentifier.of("dwd", "dwd_instorage"));
        println("表名："+table.comment());
        Arrays.stream(table.columns()).forEach(column -> {
            println("column:"+column.name()+",类型："+column.dataType().name()+",注释："+column.comment());
        });

    }


    @org.junit.Test
    public void test05() {
        GravitinoClient gravitinoClient = GravitinoClient
                .builder(url)
                .withMetalake("test")
                .build();

        // 获取所有catalogs
        String[] catalogNames = gravitinoClient.listCatalogs();
        for (String catalogName : catalogNames) {
            println("\n++++++++++++++++++++++++");
            println("catalog:"+catalogName);
            Catalog catalog = gravitinoClient.loadCatalog(catalogName);
            //获取指定catalog下的所有scheams
            final String[] listSchemas = catalog.asSchemas().listSchemas();
            for (String schema : listSchemas) {
                println("schema:"+schema);
                TableCatalog tableCatalog = catalog.asTableCatalog();
                // 获取指定catalog下的指定scheam下的所有表
                NameIdentifier[] identifiers =
                        tableCatalog.listTables(Namespace.of(schema));
                for (NameIdentifier identifier : identifiers) {
                    // 获取指定catalog下的指定scheam下指定表的所有字段
                    Table table = tableCatalog.loadTable(identifier);
                    println("表名："+table.comment());
                    Arrays.stream(table.columns()).forEach(column -> {
                        println("column:"+column.name()+",类型："+column.dataType().name()+",注释："+column.comment());
                    });
                }

            }

        }

    }

    @org.junit.Test
    public void test06() {
        GravitinoClient gravitinoClient = GravitinoClient
                .builder(url)
                .withMetalake("test")
                .build();

        // 获取所有catalogs
        String[] catalogNames = gravitinoClient.listCatalogs();
        for (String catalogName : catalogNames) {
            println("\n++++++++++++++++++++++++");
            println("catalog:"+catalogName);
            Catalog catalog = gravitinoClient.loadCatalog(catalogName);
            //获取指定catalog下的所有scheams
            String[] listSchemas = catalog.asSchemas().listSchemas();
            for (String schema : listSchemas) {
                println("schema:"+schema);
                if(catalog instanceof  TableCatalog){
                    TableCatalog tableCatalog = catalog.asTableCatalog();
                    // 获取指定catalog下的指定scheam下的所有表
                    NameIdentifier[] identifiers =
                            tableCatalog.listTables(Namespace.of(schema));

                    for (NameIdentifier identifier : identifiers) {
                        println("==="+identifier);
                        // 获取指定catalog下的指定scheam下指定表的所有字段
                        Table table = tableCatalog.loadTable(identifier);
                        println("表名："+table.comment());
                        Arrays.stream(table.columns()).forEach(column -> {
                            println("column:"+column.name()+",类型："+column.dataType().name()+",注释："+column.comment());
                        });
                    }
                }

            }
        }
    }




    public void println(Object o) {
        System.out.println(o);
    }
}
