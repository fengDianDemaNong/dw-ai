-- stg.stg_log_dp_alliance_base_log_hi
CREATE TABLE stg.stg_log_dp_alliance_base_log_hi (
                                                     data STRING,
                                                     pt STRING
);

-- stg.stg_log_dp_lj_base_log_hi
CREATE TABLE stg.stg_log_dp_lj_base_log_hi (
                                               data STRING,
                                               pt STRING
);

-- stg.stg_log_dp_bigc_base_log_hi
CREATE TABLE stg.stg_log_dp_bigc_base_log_hi (
                                                 data STRING,
                                                 pt STRING
);

-- stg.stg_log_dp_nts_base_log_hi
CREATE TABLE stg.stg_log_dp_nts_base_log_hi (
                                                data STRING,
                                                pt STRING
);

-- stg.stg_log_dp_delta_base_log_hi
CREATE TABLE stg.stg_log_dp_delta_base_log_hi (
                                                  data STRING,
                                                  pt STRING
);

-- stg.stg_log_dp_utopia_base_log_hi
CREATE TABLE stg.stg_log_dp_utopia_base_log_hi (
                                                   data STRING,
                                                   pt STRING
);

-- stg.stg_log_dp_common_base_log_hi
CREATE TABLE stg.stg_log_dp_common_base_log_hi (
                                                   data STRING,
                                                   pt STRING
);

-- dw.dw_log_common_all_info_hi
CREATE TABLE dw.dw_log_common_all_info_hi (
                                              evt_id STRING,
                                              pid STRING,
                                              pt STRING
);

-- dw.dw_log_delta_all_info_di
CREATE TABLE dw.dw_log_delta_all_info_di (
                                             evt_id STRING,
                                             pid STRING,
                                             pt STRING
);

-- dw.dw_log_nts_all_info_di
CREATE TABLE dw.dw_log_nts_all_info_di (
                                           evt_id STRING,
                                           pid STRING,
                                           pt STRING
);

-- dw.dw_log_common_all_info_di
CREATE TABLE dw.dw_log_common_all_info_di (
                                              evt_id STRING,
                                              pid STRING,
                                              pt STRING
);

-- dw.dw_log_bigc_all_info_di
CREATE TABLE dw.dw_log_bigc_all_info_di (
                                            evt_id STRING,
                                            pid STRING,
                                            dp STRING,
                                            pt STRING
);

-- dw.dw_log_dig_all_info_new_di
CREATE TABLE dw.dw_log_dig_all_info_new_di (
                                               evt_id STRING,
                                               pid STRING,
                                               dp STRING,
                                               pt STRING
);

-- dw.dw_log_alliance_all_info_di
CREATE TABLE dw.dw_log_alliance_all_info_di (
                                                evt_id STRING,
                                                pid STRING,
                                                dp STRING,
                                                pt STRING
);

-- dw.dw_log_search_query_beike_lianjia_di
CREATE TABLE dw.dw_log_search_query_beike_lianjia_di (
                                                         evt_id STRING,
                                                         pid STRING,
                                                         dp STRING,
                                                         pt STRING
);

-- dw.dw_log_xinfang_search_query_detail_di
CREATE TABLE dw.dw_log_xinfang_search_query_detail_di (
                                                          evt_id STRING,
                                                          pid STRING,
                                                          dp STRING,
                                                          pt STRING
);

-- dw.dw_log_xinfang_search_query_di
CREATE TABLE dw.dw_log_xinfang_search_query_di (
                                                   evt_id STRING,
                                                   pid STRING,
                                                   dp STRING,
                                                   pt STRING
);

-- dw.dw_log_search_query_di
CREATE TABLE dw.dw_log_search_query_di (
                                           evt_id STRING,
                                           pid STRING,
                                           dp STRING,
                                           pt STRING
);

-- dw.dw_log_search_query_detail_di
CREATE TABLE dw.dw_log_search_query_detail_di (
                                                  evt_id STRING,
                                                  pid STRING,
                                                  dp STRING,
                                                  pt STRING
);

-- dw.dw_log_recommand_di
CREATE TABLE dw.dw_log_recommand_di (
                                        evt_id STRING,
                                        pt STRING
);

-- dw.dw_log_rent_bigc_di
CREATE TABLE dw.dw_log_rent_bigc_di (
                                        evt_id STRING,
                                        pid STRING,
                                        dp STRING,
                                        pt STRING
);

-- dw.dw_log_rent_bigc_search_di
CREATE TABLE dw.dw_log_rent_bigc_search_di (
                                               evt_id STRING,
                                               pid STRING,
                                               dp STRING,
                                               pt STRING
);

-- dw.dw_log_rent_search_query_di
CREATE TABLE dw.dw_log_rent_search_query_di (
                                                evt_id STRING,
                                                pid STRING,
                                                dp STRING,
                                                pt STRING
);

-- dw.dw_log_rushi_all_info_di
CREATE TABLE dw.dw_log_rushi_all_info_di (
                                             evt_id STRING,
                                             pid STRING,
                                             dp STRING,
                                             pt STRING
);

-- dw.dw_log_lj_di
CREATE TABLE dw.dw_log_lj_di (
                                 evt_id STRING,
                                 pid STRING,
                                 dp STRING,
                                 pt STRING
);

-- dw.dw_log_nts_e_all_info_di
CREATE TABLE dw.dw_log_nts_e_all_info_di (
                                             evt_id STRING,
                                             pid STRING,
                                             dp STRING,
                                             pt STRING
);

-- dw.dw_log_nts_p_all_info_di
CREATE TABLE dw.dw_log_nts_p_all_info_di (
                                             evt_id STRING,
                                             pid STRING,
                                             dp STRING,
                                             pt STRING
);

-- dw.dw_log_exp_di
CREATE TABLE dw.dw_log_exp_di (
                                  evt_id STRING,
                                  pid STRING,
                                  dp STRING,
                                  pt STRING
);

-- dw.dw_log_p_di
CREATE TABLE dw.dw_log_p_di (
                                evt_id STRING,
                                pid STRING,
                                dp STRING,
                                pt STRING
);

-- dw.dw_log_e_di
CREATE TABLE dw.dw_log_e_di (
                                evt_id STRING,
                                pid STRING,
                                dp STRING,
                                pt STRING
);

-- dw.dw_log_b_exp_di
CREATE TABLE dw.dw_log_b_exp_di (
                                    evt_id STRING,
                                    pid STRING,
                                    dp STRING,
                                    pt STRING
);

-- dw.dw_log_b_p_di
CREATE TABLE dw.dw_log_b_p_di (
                                  evt_id STRING,
                                  pid STRING,
                                  dp STRING,
                                  pt STRING
);

-- dw.dw_log_b_e_di
CREATE TABLE dw.dw_log_b_e_di (
                                  evt_id STRING,
                                  pid STRING,
                                  dp STRING,
                                  pt STRING
);

-- dw.dw_log_commerce_flow_detail_di
CREATE TABLE dw.dw_log_commerce_flow_detail_di (
                                                   evt_id STRING,
                                                   pt STRING
);

-- dw.dw_log_utopia_all_info_di
CREATE TABLE dw.dw_log_utopia_all_info_di (
                                              evt_id STRING,
                                              pid STRING,
                                              dp STRING,
                                              pt STRING
);

-- dw.dw_log_nts_all_info_hi
CREATE TABLE dw.dw_log_nts_all_info_hi (
                                           evt_id STRING,
                                           pid STRING,
                                           pt STRING
);

-- dw.dw_log_delta_all_info_hi
CREATE TABLE dw.dw_log_delta_all_info_hi (
                                             evt_id STRING,
                                             pid STRING,
                                             pt STRING
);

-- dw.dw_log_b_visit_duration_di
CREATE TABLE dw.dw_log_b_visit_duration_di (
                                               evt_id STRING,
                                               pid STRING,
                                               dp STRING,
                                               pt STRING
);

-- dw.dw_log_device_visit_duration_di
CREATE TABLE dw.dw_log_device_visit_duration_di (
                                                    evt_id STRING,
                                                    pid STRING,
                                                    dp STRING,
                                                    pt STRING
);

-- dwd.dwd_dq_table_detail_da
CREATE TABLE dwd.dwd_dq_table_detail_da (
                                            table_id STRING,
                                            table_name STRING,
                                            database_name STRING,
                                            total_records BIGINT,
                                            total_size BIGINT,
                                            total_size_desc STRING,
                                            day_rise_records BIGINT,
                                            day_rise_size BIGINT,
                                            day_rise_size_desc STRING,
                                            pt STRING
);

-- tmp.tmp_record_info_sql
CREATE TABLE tmp.tmp_record_info_sql (
                                         database_name STRING,
                                         table_id STRING,
                                         table_name STRING,
                                         pid STRING,
                                         dp STRING,
                                         evt_id STRING,
                                         evt_records BIGINT,
                                         day_rise_records BIGINT,
                                         day_rise_size BIGINT,
                                         day_rise_size_desc STRING,
                                         total_records BIGINT,
                                         total_size BIGINT,
                                         total_size_desc STRING
);

-- dws.dws_dq_evt_record_info_da
CREATE TABLE dws.dws_dq_evt_record_info_da (
                                               database_name STRING,
                                               table_id STRING,
                                               table_name STRING,
                                               pid STRING,
                                               dp STRING,
                                               evt_id STRING,
                                               evt_records BIGINT,
                                               evt_rate DOUBLE,
                                               day_rise_records BIGINT,
                                               day_rise_size BIGINT,
                                               day_rise_size_desc STRING,
                                               total_records BIGINT,
                                               total_size BIGINT,
                                               total_size_desc STRING,
                                               pt STRING
);