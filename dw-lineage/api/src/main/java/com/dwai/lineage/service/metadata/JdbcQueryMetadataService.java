package com.dwai.lineage.service.metadata;

import io.github.melin.sqlflow.metadata.MetadataService;
import io.github.melin.sqlflow.metadata.QualifiedObjectName;
import io.github.melin.sqlflow.metadata.SchemaTable;
import io.github.melin.sqlflow.metadata.ViewDefinition;
import io.github.melin.sqlflow.tree.QualifiedName;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class JdbcQueryMetadataService implements MetadataService {

    private final List<SchemaTable> tables = new ArrayList();
    private Optional<String> defaultSchema=Optional.empty();
    private final Optional<String> defaultCatalog=Optional.empty();

    public JdbcQueryMetadataService() {
    }
    public JdbcQueryMetadataService(String schema) {
        this.defaultSchema = Optional.of(schema);
    }

    @Override
    public Optional<String> getSchema() {
        return defaultSchema;
    }

    @Override
    public Optional<String> getCatalog() {
        return defaultCatalog;
    }

    @Override
    public boolean isAggregationFunction(QualifiedName name) {
        return false;
    }

    @Override
    public Optional<SchemaTable> getTableSchema(QualifiedObjectName targetTable) {
//        return tables.stream()
//                .filter(table -> (table.getSchemaName()+ "." + table.getTableName()).equals(targetTable.toString()))
//                .findFirst();
        for(SchemaTable schemaTable : this.tables) {
            if (schemaTable.toString().equalsIgnoreCase(targetTable.toString())) {
                return Optional.of(schemaTable);
            }
        }

        return Optional.empty();
    }

    @Override
    public Optional<ViewDefinition> getView(QualifiedObjectName viewName) {
        return Optional.empty();
    }

    public MetadataService addTableMetadata(List<SchemaTable> schemaTables) {
        this.tables.addAll(schemaTables);
        return this;
    }

    public MetadataService addTableMetadata(SchemaTable schemaTable) {
        this.tables.add(schemaTable);
        return this;
    }
}
