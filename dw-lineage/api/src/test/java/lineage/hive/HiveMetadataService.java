package lineage.hive;

import com.google.common.collect.Lists;
import io.github.melin.sqlflow.metadata.MetadataService;
import io.github.melin.sqlflow.metadata.QualifiedObjectName;
import io.github.melin.sqlflow.metadata.SchemaTable;
import io.github.melin.sqlflow.metadata.ViewDefinition;
import io.github.melin.sqlflow.tree.QualifiedName;

import java.util.List;
import java.util.Optional;

public class HiveMetadataService implements MetadataService {

    @Override
    public Optional<String> getSchema() {
        return Optional.empty();
    }

    @Override
    public Optional<String> getCatalog() {
        return Optional.empty();
    }

    @Override
    public boolean isAggregationFunction(QualifiedName qualifiedName) {
        return false;
    }

    @Override
    public Optional<SchemaTable> getTableSchema(QualifiedObjectName qualifiedObjectName) {
        if (qualifiedObjectName.getObjectName().equalsIgnoreCase("tmp_record_info_sql")) {
            List<String> columns = Lists.newArrayList(
                    "database_name",
                    "table_id",
                    "table_name",
                    "pid",
                    "dp",
                    "evt_id",
                    "evt_records",
                    "day_rise_records",
                    "day_rise_size",
                    "day_rise_size_desc",
                    "total_records",
                    "total_size",
                    "total_size_desc"
            );
            return Optional.of(new SchemaTable("tmp_record_info_sql", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("stg_log_dp_alliance_base_log_hi")) {
            List<String> columns = Lists.newArrayList(
                    "data",
                    "pt"
            );
            return Optional.of(new SchemaTable("stg_log_dp_alliance_base_log_hi", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_common_all_info_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_common_all_info_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_recommand_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_recommand_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_rent_bigc_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_rent_bigc_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_rent_bigc_search_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_rent_bigc_search_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_rent_search_query_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_rent_search_query_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_rushi_all_info_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_rushi_all_info_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_lj_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_lj_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_nts_e_all_info_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_nts_e_all_info_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_nts_p_all_info_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_nts_p_all_info_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_exp_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_exp_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_p_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_p_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_e_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_e_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_b_exp_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_b_exp_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_b_p_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_b_p_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_b_e_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_b_e_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_commerce_flow_detail_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_commerce_flow_detail_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_utopia_all_info_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_utopia_all_info_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_nts_all_info_hi")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_nts_all_info_hi", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_delta_all_info_hi")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_delta_all_info_hi", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("stg_log_dp_lj_base_log_hi")) {
            List<String> columns = Lists.newArrayList(
                    "data",
                    "pt"
            );
            return Optional.of(new SchemaTable("stg_log_dp_lj_base_log_hi", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("stg_log_dp_bigc_base_log_hi")) {
            List<String> columns = Lists.newArrayList(
                    "data",
                    "pt"
            );
            return Optional.of(new SchemaTable("stg_log_dp_bigc_base_log_hi", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("stg_log_dp_nts_base_log_hi")) {
            List<String> columns = Lists.newArrayList(
                    "data",
                    "pt"
            );
            return Optional.of(new SchemaTable("stg_log_dp_nts_base_log_hi", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("stg_log_dp_delta_base_log_hi")) {
            List<String> columns = Lists.newArrayList(
                    "data",
                    "pt"
            );
            return Optional.of(new SchemaTable("stg_log_dp_delta_base_log_hi", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("stg_log_dp_utopia_base_log_hi")) {
            List<String> columns = Lists.newArrayList(
                    "data",
                    "pt"
            );
            return Optional.of(new SchemaTable("stg_log_dp_utopia_base_log_hi", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("stg_log_dp_common_base_log_hi")) {
            List<String> columns = Lists.newArrayList(
                    "data",
                    "pt"
            );
            return Optional.of(new SchemaTable("stg_log_dp_common_base_log_hi", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_xinfang_search_query_detail_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_xinfang_search_query_detail_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_xinfang_search_query_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_xinfang_search_query_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_search_query_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_search_query_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_search_query_detail_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_search_query_detail_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_rent_bigc_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_rent_bigc_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_rent_bigc_search_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_rent_bigc_search_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_rent_search_query_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_rent_search_query_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_rushi_all_info_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_rushi_all_info_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_lj_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_lj_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_nts_e_all_info_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_nts_e_all_info_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_nts_p_all_info_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_nts_p_all_info_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_exp_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_exp_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_p_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_p_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_e_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_e_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_b_exp_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_b_exp_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_b_p_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_b_p_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_b_e_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_b_e_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_commerce_flow_detail_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_commerce_flow_detail_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_utopia_all_info_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_utopia_all_info_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_nts_all_info_hi")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_nts_all_info_hi", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_delta_all_info_hi")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_delta_all_info_hi", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_b_visit_duration_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_b_visit_duration_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dw_log_device_visit_duration_di")) {
            List<String> columns = Lists.newArrayList(
                    "evt_id",
                    "pid",
                    "dp",
                    "line_cnt"
            );
            return Optional.of(new SchemaTable("dw_log_device_visit_duration_di", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dwd_dq_table_detail_da")) {
            List<String> columns = Lists.newArrayList(
                    "table_id",
                    "table_name",
                    "database_name",
                    "total_records",
                    "total_size",
                    "total_size_desc",
                    "day_rise_records",
                    "day_rise_size",
                    "day_rise_size_desc",
                    "pt"
            );
            return Optional.of(new SchemaTable("dwd_dq_table_detail_da", columns));
        } else if (qualifiedObjectName.getObjectName().equalsIgnoreCase("dws_dq_evt_record_info_da")) {
            List<String> columns = Lists.newArrayList(
                    "database_name",
                    "table_id",
                    "table_name",
                    "pid",
                    "dp",
                    "evt_id",
                    "evt_records",
                    "evt_rate",
                    "day_rise_records",
                    "day_rise_size",
                    "day_rise_size_desc",
                    "total_records",
                    "total_size",
                    "total_size_desc",
                    "pt"
            );
            return Optional.of(new SchemaTable("dws_dq_evt_record_info_da", columns));
        }

        return Optional.empty();
    }

    @Override
    public Optional<ViewDefinition> getView(QualifiedObjectName qualifiedObjectName) {
        return Optional.empty();
    }
}