ALTER TABLE operation_log ADD COLUMN business_object_type VARCHAR(64) NULL;
ALTER TABLE operation_log ADD COLUMN business_object_id VARCHAR(96) NULL;
ALTER TABLE operation_log ADD COLUMN failure_reason VARCHAR(500) NULL;
ALTER TABLE operation_log ADD COLUMN duration_ms BIGINT NULL;

CREATE TABLE stored_file (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    storage_name VARCHAR(255) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    storage_path VARCHAR(1000) NULL COMMENT '存储路径',
    media_type VARCHAR(128) NOT NULL,
    file_ext VARCHAR(20) NULL,
    byte_size BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    purpose VARCHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64) NOT NULL,
    CONSTRAINT pk_stored_file PRIMARY KEY (id),
    CONSTRAINT uk_stored_file__public_id UNIQUE (public_id),
    CONSTRAINT uk_stored_file__storage_name UNIQUE (storage_name)
);

CREATE TABLE import_job (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    dataset_type VARCHAR(32) NOT NULL,
    data_period CHAR(7) NOT NULL,
    period_start DATE NULL,
    period_end DATE NULL,
    city_code VARCHAR(12) NOT NULL,
    status VARCHAR(32) NOT NULL,
    source_file_id BIGINT NOT NULL,
    task_public_id CHAR(36) NOT NULL,
    total_rows INT NOT NULL DEFAULT 0,
    success_rows INT NOT NULL DEFAULT 0,
    error_rows INT NOT NULL DEFAULT 0,
    error_summary TEXT NULL,
    validation_detail JSON NULL,
    row_count INT NOT NULL DEFAULT 0,
    error_count INT NOT NULL DEFAULT 0,
    errors_json LONGTEXT NOT NULL,
    started_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    operator_id BIGINT NULL,
    completed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64) NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_import_job PRIMARY KEY (id),
    CONSTRAINT uk_import_job__public_id UNIQUE (public_id),
    CONSTRAINT fk_import_job__file FOREIGN KEY (source_file_id) REFERENCES stored_file (id),
    CONSTRAINT fk_import_job__city FOREIGN KEY (city_code) REFERENCES city (code)
);

CREATE INDEX idx_import_job__scope
    ON import_job (dataset_type, data_period, city_code, status, created_at, id);

CREATE TABLE monthly_import_status (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    city_code VARCHAR(12) NOT NULL,
    data_period CHAR(7) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    billing_point_ready BOOLEAN NOT NULL DEFAULT FALSE,
    payment_ready BOOLEAN NOT NULL DEFAULT FALSE,
    meter_ready BOOLEAN NOT NULL DEFAULT FALSE,
    benchmark_ready BOOLEAN NOT NULL DEFAULT FALSE,
    all_complete BOOLEAN NOT NULL DEFAULT FALSE,
    billing_point_job_id BIGINT NULL,
    payment_job_id BIGINT NULL,
    meter_job_id BIGINT NULL,
    benchmark_job_id BIGINT NULL,
    billing_point_time DATETIME(3) NULL,
    payment_time DATETIME(3) NULL,
    meter_time DATETIME(3) NULL,
    benchmark_time DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_monthly_import_status PRIMARY KEY (id),
    CONSTRAINT uk_monthly_import_status__public_id UNIQUE (public_id),
    CONSTRAINT uk_monthly_import_status__scope UNIQUE (city_code, data_period),
    CONSTRAINT fk_monthly_import_status__city FOREIGN KEY (city_code) REFERENCES city (code)
);

CREATE TABLE billing_point_master (
    billing_point_code       VARCHAR(100) PRIMARY KEY,
    billing_point_name       VARCHAR(255) NOT NULL,
    city_code                VARCHAR(32) NOT NULL,
    district_code            VARCHAR(32) NULL,
    billing_point_status     VARCHAR(100) NULL,
    current_data_period      CHAR(7) NOT NULL,
    current_snapshot_id      BIGINT NOT NULL,
    created_at               DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at               DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                             ON UPDATE CURRENT_TIMESTAMP(3),

    UNIQUE KEY uk_master_snapshot(current_snapshot_id),
    INDEX idx_master_city(city_code),
    INDEX idx_master_name(billing_point_name)
) COMMENT='报账点当前主数据指针';

CREATE TABLE billing_point_snapshot (
    id                                    BIGINT PRIMARY KEY AUTO_INCREMENT,
    public_id                             CHAR(36) NOT NULL,

    /* 系统字段 */
    data_period                           CHAR(7) NOT NULL COMMENT 'YYYY-MM',
    period_start                          DATE NOT NULL,
    period_end                            DATE NOT NULL,
    city_code                             VARCHAR(32) NOT NULL COMMENT '系统映射地市编码',
    district_code                         VARCHAR(32) NULL COMMENT '系统映射区县编码',
    source_import_job_id                  BIGINT NOT NULL,
    source_row_no                         INT NOT NULL,
    raw_row_json                          JSON NOT NULL COMMENT '仅用于保留原始值格式',

    /* 1 审核状态 */
    source_audit_status                   VARCHAR(100) NULL COMMENT '审核状态',

    /* 2 */
    billing_point_code                    VARCHAR(100) NOT NULL COMMENT '报账点编码',

    /* 3 */
    billing_point_name                    VARCHAR(255) NOT NULL COMMENT '报账点名称',

    /* 4 */
    billing_point_type                    VARCHAR(100) NULL COMMENT '报账点类型',

    /* 5 */
    cost_center_name                      VARCHAR(255) NULL COMMENT '所属成本中心',

    /* 6 */
    cost_center_code                      VARCHAR(100) NULL COMMENT '成本中心编码',

    /* 7 */
    city_name                             VARCHAR(100) NOT NULL COMMENT '所属地市',

    /* 8 */
    district_name                         VARCHAR(100) NULL COMMENT '所属区县',

    /* 9 */
    department_name                       VARCHAR(255) NULL COMMENT '所属部门',

    /* 10 */
    billing_point_status                  VARCHAR(100) NULL COMMENT '报账点状态',

    /* 11 */
    billing_point_meter_multiplier        VARCHAR(100) NULL COMMENT '报账点计量倍数',

    /* 12 */
    planned_payment_date                  DATE NULL COMMENT '计划缴费日期',

    /* 13 */
    last_reimbursement_start              DATE NULL COMMENT '最后报账期始',

    /* 14 */
    last_reimbursement_end                DATE NULL COMMENT '最后报账期终',

    /* 15 */
    electricity_category                  VARCHAR(100) NULL COMMENT '用电类别',

    /* 16 */
    voltage_level                         VARCHAR(100) NULL COMMENT '电压等级',

    /* 17 */
    billing_method_1                      VARCHAR(100) NULL COMMENT '计费方式（第1项）',

    /* 18 */
    site_electricity_start                DATE NULL COMMENT '建站用电期始',

    /* 19 */
    direct_conversion_site_flag           VARCHAR(50) NULL COMMENT '是否转改直站点',

    /* 20 */
    direct_conversion_enable_time         DATETIME NULL COMMENT '转改直站点启用时间',

    /* 21 */
    base_station_fee_share_start          DATE NULL COMMENT '基站电费共享起始日期',

    /* 22 */
    source_daily_avg_kwh                  DECIMAL(18,6) NULL COMMENT '报账点日均耗电量',

    /* 23 */
    summary_text                          TEXT NULL COMMENT '摘要',

    /* 24 */
    remark                                TEXT NULL COMMENT '备注',

    /* 25 */
    keywords                              TEXT NULL COMMENT '关键字',

    /* 26 */
    deletion_reason                       TEXT NULL COMMENT '删除原因',

    /* 27 */
    system_universal_service_ratio        DECIMAL(10,6) NULL COMMENT '系统计算普服金额比例',

    /* 28 */
    actual_universal_service_ratio        DECIMAL(10,6) NULL COMMENT '实际普服金额比例',

    /* 29 */
    universal_service_remark              TEXT NULL COMMENT '普服备注',

    /* 30 */
    last_payment_bill_code                VARCHAR(100) NULL COMMENT '上次报账缴费单编码',

    /* 31 */
    last_reimbursement_daily_avg_kwh      DECIMAL(18,6) NULL COMMENT '上次报账日均耗电量',

    /* 32 */
    last_payment_start                    DATE NULL COMMENT '上次缴费期始',

    /* 33 */
    last_payment_end                      DATE NULL COMMENT '上次缴费期终',

    /* 34 */
    billing_method_2                      VARCHAR(100) NULL COMMENT '计费方式（第2项）',

    /* 35 */
    power_supply_type                     VARCHAR(100) NULL COMMENT '供电类型',

    /* 36 */
    electricity_payment_cycle             VARCHAR(100) NULL COMMENT '电费缴费周期',

    /* 37 */
    line_loss_calculation_method          VARCHAR(100) NULL COMMENT '电损计算方式',

    /* 38 */
    contract_or_fixed_code                VARCHAR(100) NULL COMMENT '合同或固化编码',

    /* 39 */
    contract_or_fixed_name                VARCHAR(255) NULL COMMENT '合同或固化名称',

    /* 40 */
    contract_or_fixed_status              VARCHAR(100) NULL COMMENT '合同或固化状态',

    /* 41 */
    contract_or_fixed_start               DATE NULL COMMENT '合同或固化期始',

    /* 42 */
    contract_or_fixed_end                 DATE NULL COMMENT '合同或固化期终',

    /* 43 */
    electricity_unit_price_tax_included   VARCHAR(100) NULL COMMENT '电费单价是否含税',

    /* 44 */
    tax_rate                              DECIMAL(10,6) NULL COMMENT '税率',

    /* 45 */
    package_flag                          VARCHAR(50) NULL COMMENT '是否包干',

    /* 46 */
    package_total_amount                  DECIMAL(18,6) NULL COMMENT '包干总金额',

    /* 47 */
    electricity_unit_price_1              DECIMAL(18,6) NULL COMMENT '电费单价1',

    /* 48 */
    electricity_unit_price_2              DECIMAL(18,6) NULL COMMENT '电费单价2',

    /* 49 */
    electricity_unit_price_3              DECIMAL(18,6) NULL COMMENT '电费单价3',

    /* 50 */
    electricity_unit_price_4              DECIMAL(18,6) NULL COMMENT '电费单价4',

    /* 51 */
    supplier_name                         VARCHAR(255) NULL COMMENT '供应商名称',

    /* 52 */
    supplier_code                         VARCHAR(100) NULL COMMENT '供应商编码',

    /* 53 */
    fixed_related_contract_name           VARCHAR(255) NULL COMMENT '固化关联合同名称',

    /* 54 */
    fixed_related_contract_code           VARCHAR(100) NULL COMMENT '固化关联合同编码',

    /* 55 */
    fixed_related_contract_status         VARCHAR(100) NULL COMMENT '固化关联合同状态',

    /* 56 */
    resource_code                         VARCHAR(100) NULL COMMENT '关联资源编码',

    /* 57 */
    resource_name                         VARCHAR(255) NULL COMMENT '关联资源名称',

    /* 58 */
    resource_type                         VARCHAR(100) NULL COMMENT '资源类型',

    /* 59 */
    business_type                         VARCHAR(100) NULL COMMENT '业务类型',

    /* 60 */
    main_equipment_power                  DECIMAL(18,6) NULL COMMENT '主设备功率',

    /* 61 */
    air_conditioner_total_power           DECIMAL(18,6) NULL COMMENT '空调总功率',

    /* 62 */
    tower_air_conditioner_rated_power     DECIMAL(18,6) NULL COMMENT '铁塔空调总额定功率',

    /* 63 */
    resource_status                       VARCHAR(100) NULL COMMENT '资源状态',

    /* 64 */
    tower_site_code                       VARCHAR(100) NULL COMMENT '铁塔站址编码',

    /* 65 */
    property_nature                       VARCHAR(100) NULL COMMENT '产权性质',

    /* 66 */
    property_owner                        VARCHAR(255) NULL COMMENT '产权单位',

    /* 67 */
    network_access_time                   DATETIME NULL COMMENT '入网时间',

    /* 68 */
    network_exit_time                     DATETIME NULL COMMENT '退网时间',

    /* 69 */
    related_meter_code                    VARCHAR(100) NULL COMMENT '关联电表编码',

    /* 70 */
    meter_account_no                      VARCHAR(100) NULL COMMENT '电表户号',

    /* 71 */
    meter_status                          VARCHAR(100) NULL COMMENT '电表状态',

    /* 72 */
    shared_multi_room_flag                VARCHAR(50) NULL COMMENT '是否多机房共用',

    /* 73 */
    meter_multiplier                      DECIMAL(18,6) NULL COMMENT '电表倍率',

    data_json                             LONGTEXT NOT NULL COMMENT '??????/?????? raw_row_json',

    created_at                            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at                            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                          ON UPDATE CURRENT_TIMESTAMP(3),

    UNIQUE KEY uk_bp_snapshot_public(public_id),
    UNIQUE KEY uk_bp_snapshot_business(
        billing_point_code,
        period_start,
        period_end
    ),

    INDEX idx_bp_city_period(city_code,data_period),
    INDEX idx_bp_district_period(district_code,data_period),
    INDEX idx_bp_name(billing_point_name),
    INDEX idx_bp_resource(resource_code),
    INDEX idx_bp_tower(tower_site_code),
    INDEX idx_bp_meter(related_meter_code),
    INDEX idx_bp_meter_account(meter_account_no)
) COMMENT='报账点月度快照，完整73源字段';

CREATE TABLE payment_detail (
    id                                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    public_id                           CHAR(36) NOT NULL,

    /* 系统字段 */
    data_period                         CHAR(7) NOT NULL,
    period_start                        DATE NOT NULL,
    period_end                          DATE NOT NULL,
    city_code                           VARCHAR(32) NOT NULL,
    district_code                       VARCHAR(32) NULL,
    source_import_job_id                BIGINT NOT NULL,
    source_row_no                       INT NOT NULL,
    raw_row_json                        JSON NOT NULL,

    /* 1-77 */
    audit_status                        VARCHAR(100) NULL COMMENT '1 审核状态',
    last_audit_time                     DATETIME NULL COMMENT '2 上次审核时间',
    last_auditor                        VARCHAR(100) NULL COMMENT '3 上次审核人',
    audit_result                        VARCHAR(100) NULL COMMENT '4 审核结果',
    audit_opinion                       TEXT NULL COMMENT '5 审核意见',
    current_audit_stage                 VARCHAR(100) NULL COMMENT '6 当前审核环节',
    current_auditor                     VARCHAR(100) NULL COMMENT '7 当前审核人',
    push_status                         VARCHAR(100) NULL COMMENT '8 推送状态',
    finance_audit_time                  DATETIME NULL COMMENT '9 财务审核时间',
    finance_return_no                   VARCHAR(100) NULL COMMENT '10 财务返回单号',
    summary_bill_code                   VARCHAR(100) NULL COMMENT '11 汇总单编码',
    payment_bill_code                   VARCHAR(100) NOT NULL COMMENT '12 缴费单编码',
    city_name                           VARCHAR(100) NOT NULL COMMENT '13 所属地市',
    district_name                       VARCHAR(100) NULL COMMENT '14 所属区县',
    billing_point_code                  VARCHAR(100) NOT NULL COMMENT '15 报账点编码',
    billing_point_name                  VARCHAR(255) NULL COMMENT '16 报账点名称',
    billing_point_type                  VARCHAR(100) NULL COMMENT '17 报账点类型',
    billing_point_meter_multiplier      VARCHAR(100) NULL COMMENT '18 报账点计量倍数',
    contract_code                       VARCHAR(100) NULL COMMENT '19 合同编码',
    contract_name                       VARCHAR(255) NULL COMMENT '20 合同名称',
    electricity_purchase_method         VARCHAR(100) NULL COMMENT '21 购电方式',
    power_supply_type                   VARCHAR(100) NULL COMMENT '22 供电类型',
    line_loss_calculation_method        VARCHAR(100) NULL COMMENT '23 电损计算方式',
    payment_application_date            DATE NULL COMMENT '24 缴费申请日期',
    payment_start                       DATE NOT NULL COMMENT '25 缴费期始',
    payment_end                         DATE NULL COMMENT '26 缴费期终',
    payment_days                        INT NULL COMMENT '27 缴费天数',
    daily_avg_kwh                       DECIMAL(18,6) NULL COMMENT '28 日均耗电量',
    avg_unit_price_excl_tax             DECIMAL(18,6) NULL COMMENT '29 不含税平均单价',
    actual_allocation_ratio             DECIMAL(10,6) NULL COMMENT '30 实际分摊比例',
    actual_electricity_tax_rate         DECIMAL(10,6) NULL COMMENT '31 实际电费税率（单位：%）',
    actual_electricity_amount_excl_tax  DECIMAL(18,6) NULL COMMENT '32 实际不含税电费金额',
    actual_electricity_tax              DECIMAL(18,6) NULL COMMENT '33 实际电费税金',
    actual_price_amount                 DECIMAL(18,6) NULL COMMENT '34 实际价款',
    actual_tax_amount                   DECIMAL(18,6) NULL COMMENT '35 实际税金',
    actual_report_amount                DECIMAL(18,6) NULL COMMENT '36 实际报账金额',
    system_calculated_amount            DECIMAL(18,6) NULL COMMENT '37 系统计算金额',
    actual_total_kwh                    DECIMAL(18,6) NULL COMMENT '38 实际总耗电量',
    data_source                         VARCHAR(100) NULL COMMENT '39 数据来源',
    entered_by                          VARCHAR(100) NULL COMMENT '40 录入人',
    reporter_three_fee_account          VARCHAR(100) NULL COMMENT '41 报账人三费账号',
    reporter_employee_no                VARCHAR(100) NULL COMMENT '42 报账人工号',
    reporter_smap_account               VARCHAR(100) NULL COMMENT '43 报账人SMAP账号',
    department_name                     VARCHAR(255) NULL COMMENT '44 所属部门',
    cost_center                         VARCHAR(255) NULL COMMENT '45 所属成本中心',
    cost_center_name                    VARCHAR(255) NULL COMMENT '46 所属成本中心名称',
    invoice_type                        VARCHAR(100) NULL COMMENT '47 票据类型',
    reimbursement_form_type             VARCHAR(100) NULL COMMENT '48 报账单类型',
    business_major_category             VARCHAR(100) NULL COMMENT '49 业务大类',
    business_minor_category             VARCHAR(100) NULL COMMENT '50 业务小类',
    business_activity                   VARCHAR(100) NULL COMMENT '51 业务活动',
    market_segment                      VARCHAR(100) NULL COMMENT '52 市场段',
    product_segment                     VARCHAR(100) NULL COMMENT '53 产品段',
    actual_universal_service_ratio      DECIMAL(10,6) NULL COMMENT '54 实际普服金额比例(%)',
    exception_status                    VARCHAR(100) NULL COMMENT '55 异常情况',
    benchmark_over_limit                VARCHAR(100) NULL COMMENT '56 标杆是否超标',
    historical_fee_benchmark_yoy        VARCHAR(100) NULL COMMENT '57 历史电费标杆-同比',
    historical_fee_benchmark_mom        VARCHAR(100) NULL COMMENT '58 历史电费标杆-环比',
    historical_daily_energy_yoy         DECIMAL(18,6) NULL COMMENT '59 历史日均电量标杆-同比',
    historical_daily_energy_mom         DECIMAL(18,6) NULL COMMENT '60 历史日均电量标杆-环比',
    rated_power_benchmark               DECIMAL(18,6) NULL COMMENT '61 额定功率标杆，仅保留原值不作为本系统额定阈值',
    smart_meter_benchmark_environment   VARCHAR(100) NULL COMMENT '62 智能电表标杆(动环)',
    smart_meter_benchmark_procurement   VARCHAR(100) NULL COMMENT '63 智能电表标杆(集采)',
    environment_load_benchmark          VARCHAR(100) NULL COMMENT '64 动环负载标杆',
    total_tou_average_price_benchmark   VARCHAR(100) NULL COMMENT '65 平峰谷按总量录入均价标杆',
    keywords                            TEXT NULL COMMENT '66 关键字',
    required_remark                     TEXT NULL COMMENT '67 必填备注',
    remark_type_1                       TEXT NULL COMMENT '68 备注类型1',
    remark_1                            TEXT NULL COMMENT '69 备注1',
    remark_type_2                       TEXT NULL COMMENT '70 备注类型2',
    remark_2                            TEXT NULL COMMENT '71 备注2',
    remark_type_3                       TEXT NULL COMMENT '72 备注类型3',
    remark_3                            TEXT NULL COMMENT '73 备注3',
    remark_type_4                       TEXT NULL COMMENT '74 备注类型4',
    remark_4                            TEXT NULL COMMENT '75 备注4',
    remark_type_5                       TEXT NULL COMMENT '76 备注类型5',
    remark_5                            TEXT NULL COMMENT '77 备注5',

    /* 78-89 其他费用1 */
    other_fee_name_1                    VARCHAR(255) NULL COMMENT '78 其他费用名称1',
    other_invoice_type_1                VARCHAR(100) NULL COMMENT '79 票据类型1',
    other_price_amount_1                DECIMAL(18,6) NULL COMMENT '80 价款1',
    other_tax_rate_1                    DECIMAL(10,6) NULL COMMENT '81 税率1',
    other_tax_amount_1                  DECIMAL(18,6) NULL COMMENT '82 税金1',
    other_tax_included_amount_1         DECIMAL(18,6) NULL COMMENT '83 含税金额1',
    other_energy_1                      DECIMAL(18,6) NULL COMMENT '84 其他电量1',
    other_business_major_1              VARCHAR(100) NULL COMMENT '85 业务大类1',
    other_business_minor_1              VARCHAR(100) NULL COMMENT '86 业务小类1',
    other_business_activity_1           VARCHAR(100) NULL COMMENT '87 业务活动1',
    other_market_segment_1              VARCHAR(100) NULL COMMENT '88 市场段1',
    other_product_segment_1             VARCHAR(100) NULL COMMENT '89 产品段1',

    /* 90-101 其他费用2 */
    other_fee_name_2                    VARCHAR(255) NULL COMMENT '90 其他费用名称2',
    other_invoice_type_2                VARCHAR(100) NULL COMMENT '91 票据类型2',
    other_price_amount_2                DECIMAL(18,6) NULL COMMENT '92 价款2',
    other_tax_rate_2                    DECIMAL(10,6) NULL COMMENT '93 税率2',
    other_tax_amount_2                  DECIMAL(18,6) NULL COMMENT '94 税金2',
    other_tax_included_amount_2         DECIMAL(18,6) NULL COMMENT '95 含税金额2',
    other_energy_2                      DECIMAL(18,6) NULL COMMENT '96 其他电量2',
    other_business_major_2              VARCHAR(100) NULL COMMENT '97 业务大类2',
    other_business_minor_2              VARCHAR(100) NULL COMMENT '98 业务小类2',
    other_business_activity_2           VARCHAR(100) NULL COMMENT '99 业务活动2',
    other_market_segment_2              VARCHAR(100) NULL COMMENT '100 市场段2',
    other_product_segment_2             VARCHAR(100) NULL COMMENT '101 产品段2',

    /* 102-113 其他费用3 */
    other_fee_name_3                    VARCHAR(255) NULL COMMENT '102 其他费用名称3',
    other_invoice_type_3                VARCHAR(100) NULL COMMENT '103 票据类型3',
    other_price_amount_3                DECIMAL(18,6) NULL COMMENT '104 价款3',
    other_tax_rate_3                    DECIMAL(10,6) NULL COMMENT '105 税率3',
    other_tax_amount_3                  DECIMAL(18,6) NULL COMMENT '106 税金3',
    other_tax_included_amount_3         DECIMAL(18,6) NULL COMMENT '107 含税金额3',
    other_energy_3                      DECIMAL(18,6) NULL COMMENT '108 其他电量3',
    other_business_major_3              VARCHAR(100) NULL COMMENT '109 业务大类3',
    other_business_minor_3              VARCHAR(100) NULL COMMENT '110 业务小类3',
    other_business_activity_3           VARCHAR(100) NULL COMMENT '111 业务活动3',
    other_market_segment_3              VARCHAR(100) NULL COMMENT '112 市场段3',
    other_product_segment_3             VARCHAR(100) NULL COMMENT '113 产品段3',

    /* 114-125 其他费用4 */
    other_fee_name_4                    VARCHAR(255) NULL COMMENT '114 其他费用名称4',
    other_invoice_type_4                VARCHAR(100) NULL COMMENT '115 票据类型4',
    other_price_amount_4                DECIMAL(18,6) NULL COMMENT '116 价款4',
    other_tax_rate_4                    DECIMAL(10,6) NULL COMMENT '117 税率4',
    other_tax_amount_4                  DECIMAL(18,6) NULL COMMENT '118 税金4',
    other_tax_included_amount_4         DECIMAL(18,6) NULL COMMENT '119 含税金额4',
    other_energy_4                      DECIMAL(18,6) NULL COMMENT '120 其他电量4',
    other_business_major_4              VARCHAR(100) NULL COMMENT '121 业务大类4',
    other_business_minor_4              VARCHAR(100) NULL COMMENT '122 业务小类4',
    other_business_activity_4           VARCHAR(100) NULL COMMENT '123 业务活动4',
    other_market_segment_4              VARCHAR(100) NULL COMMENT '124 市场段4',
    other_product_segment_4             VARCHAR(100) NULL COMMENT '125 产品段4',

    /* 126-137 其他费用5 */
    other_fee_name_5                    VARCHAR(255) NULL COMMENT '126 其他费用名称5',
    other_invoice_type_5                VARCHAR(100) NULL COMMENT '127 票据类型5',
    other_price_amount_5                DECIMAL(18,6) NULL COMMENT '128 价款5',
    other_tax_rate_5                    DECIMAL(10,6) NULL COMMENT '129 税率5',
    other_tax_amount_5                  DECIMAL(18,6) NULL COMMENT '130 税金5',
    other_tax_included_amount_5         DECIMAL(18,6) NULL COMMENT '131 含税金额5',
    other_energy_5                      DECIMAL(18,6) NULL COMMENT '132 其他电量5',
    other_business_major_5              VARCHAR(100) NULL COMMENT '133 业务大类5',
    other_business_minor_5              VARCHAR(100) NULL COMMENT '134 业务小类5',
    other_business_activity_5           VARCHAR(100) NULL COMMENT '135 业务活动5',
    other_market_segment_5              VARCHAR(100) NULL COMMENT '136 市场段5',
    other_product_segment_5             VARCHAR(100) NULL COMMENT '137 产品段5',

    /* 138-149 其他费用6 */
    other_fee_name_6                    VARCHAR(255) NULL COMMENT '138 其他费用名称6',
    other_invoice_type_6                VARCHAR(100) NULL COMMENT '139 票据类型6',
    other_price_amount_6                DECIMAL(18,6) NULL COMMENT '140 价款6',
    other_tax_rate_6                    DECIMAL(10,6) NULL COMMENT '141 税率6',
    other_tax_amount_6                  DECIMAL(18,6) NULL COMMENT '142 税金6',
    other_tax_included_amount_6         DECIMAL(18,6) NULL COMMENT '143 含税金额6',
    other_energy_6                      DECIMAL(18,6) NULL COMMENT '144 其他电量6',
    other_business_major_6              VARCHAR(100) NULL COMMENT '145 业务大类6',
    other_business_minor_6              VARCHAR(100) NULL COMMENT '146 业务小类6',
    other_business_activity_6           VARCHAR(100) NULL COMMENT '147 业务活动6',
    other_market_segment_6              VARCHAR(100) NULL COMMENT '148 市场段6',
    other_product_segment_6             VARCHAR(100) NULL COMMENT '149 产品段6',

    /* 150-161 其他费用7 */
    other_fee_name_7                    VARCHAR(255) NULL COMMENT '150 其他费用名称7',
    other_invoice_type_7                VARCHAR(100) NULL COMMENT '151 票据类型7',
    other_price_amount_7                DECIMAL(18,6) NULL COMMENT '152 价款7',
    other_tax_rate_7                    DECIMAL(10,6) NULL COMMENT '153 税率7',
    other_tax_amount_7                  DECIMAL(18,6) NULL COMMENT '154 税金7',
    other_tax_included_amount_7         DECIMAL(18,6) NULL COMMENT '155 含税金额7',
    other_energy_7                      DECIMAL(18,6) NULL COMMENT '156 其他电量7',
    other_business_major_7              VARCHAR(100) NULL COMMENT '157 业务大类7',
    other_business_minor_7              VARCHAR(100) NULL COMMENT '158 业务小类7',
    other_business_activity_7           VARCHAR(100) NULL COMMENT '159 业务活动7',
    other_market_segment_7              VARCHAR(100) NULL COMMENT '160 市场段7',
    other_product_segment_7             VARCHAR(100) NULL COMMENT '161 产品段7',

    /* 162-173 其他费用8 */
    other_fee_name_8                    VARCHAR(255) NULL COMMENT '162 其他费用名称8',
    other_invoice_type_8                VARCHAR(100) NULL COMMENT '163 票据类型8',
    other_price_amount_8                DECIMAL(18,6) NULL COMMENT '164 价款8',
    other_tax_rate_8                    DECIMAL(10,6) NULL COMMENT '165 税率8',
    other_tax_amount_8                  DECIMAL(18,6) NULL COMMENT '166 税金8',
    other_tax_included_amount_8         DECIMAL(18,6) NULL COMMENT '167 含税金额8',
    other_energy_8                      DECIMAL(18,6) NULL COMMENT '168 其他电量8',
    other_business_major_8              VARCHAR(100) NULL COMMENT '169 业务大类8',
    other_business_minor_8              VARCHAR(100) NULL COMMENT '170 业务小类8',
    other_business_activity_8           VARCHAR(100) NULL COMMENT '171 业务活动8',
    other_market_segment_8              VARCHAR(100) NULL COMMENT '172 市场段8',
    other_product_segment_8             VARCHAR(100) NULL COMMENT '173 产品段8',

    /* 174-185 其他费用9 */
    other_fee_name_9                    VARCHAR(255) NULL COMMENT '174 其他费用名称9',
    other_invoice_type_9                VARCHAR(100) NULL COMMENT '175 票据类型9',
    other_price_amount_9                DECIMAL(18,6) NULL COMMENT '176 价款9',
    other_tax_rate_9                    DECIMAL(10,6) NULL COMMENT '177 税率9',
    other_tax_amount_9                  DECIMAL(18,6) NULL COMMENT '178 税金9',
    other_tax_included_amount_9         DECIMAL(18,6) NULL COMMENT '179 含税金额9',
    other_energy_9                      DECIMAL(18,6) NULL COMMENT '180 其他电量9',
    other_business_major_9              VARCHAR(100) NULL COMMENT '181 业务大类9',
    other_business_minor_9              VARCHAR(100) NULL COMMENT '182 业务小类9',
    other_business_activity_9           VARCHAR(100) NULL COMMENT '183 业务活动9',
    other_market_segment_9              VARCHAR(100) NULL COMMENT '184 市场段9',
    other_product_segment_9             VARCHAR(100) NULL COMMENT '185 产品段9',

    /* 186-197 其他费用10 */
    other_fee_name_10                   VARCHAR(255) NULL COMMENT '186 其他费用名称10',
    other_invoice_type_10               VARCHAR(100) NULL COMMENT '187 票据类型10',
    other_price_amount_10               DECIMAL(18,6) NULL COMMENT '188 价款10',
    other_tax_rate_10                   DECIMAL(10,6) NULL COMMENT '189 税率10',
    other_tax_amount_10                 DECIMAL(18,6) NULL COMMENT '190 税金10',
    other_tax_included_amount_10        DECIMAL(18,6) NULL COMMENT '191 含税金额10',
    other_energy_10                     DECIMAL(18,6) NULL COMMENT '192 其他电量10',
    other_business_major_10             VARCHAR(100) NULL COMMENT '193 业务大类10',
    other_business_minor_10             VARCHAR(100) NULL COMMENT '194 业务小类10',
    other_business_activity_10          VARCHAR(100) NULL COMMENT '195 业务活动10',
    other_market_segment_10             VARCHAR(100) NULL COMMENT '196 市场段10',
    other_product_segment_10            VARCHAR(100) NULL COMMENT '197 产品段10',

    /* 198 */
    first_bill_flag                     VARCHAR(50) NULL COMMENT '198 是否为首单',

    values_json                          LONGTEXT NOT NULL COMMENT '??????/?????? raw_row_json',

    created_at                          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at                          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                        ON UPDATE CURRENT_TIMESTAMP(3),

    UNIQUE KEY uk_payment_public(public_id),

    UNIQUE KEY uk_payment_business(
        billing_point_code,
        payment_bill_code,
        period_start,
        period_end,
        payment_start
    ),

    INDEX idx_payment_bp_period(billing_point_code,data_period),
    INDEX idx_payment_city_period(city_code,data_period),
    INDEX idx_payment_bill(payment_bill_code),
    INDEX idx_payment_audit(billing_point_code,data_period,audit_status)
) COMMENT='缴费明细，完整198源字段';

CREATE TABLE meter_reading (
    id                           BIGINT PRIMARY KEY AUTO_INCREMENT,
    public_id                    CHAR(36) NOT NULL,

    /* 系统字段 */
    data_period                  CHAR(7) NOT NULL,
    period_start                 DATE NOT NULL,
    period_end                   DATE NOT NULL,
    city_code                    VARCHAR(32) NOT NULL,
    district_code                VARCHAR(32) NULL,
    source_import_job_id         BIGINT NOT NULL,
    source_row_no                INT NOT NULL,
    raw_row_json                 JSON NOT NULL,

    /* 1-42 */
    billing_point_name           VARCHAR(255) NULL COMMENT '1 报账点名称',
    billing_point_code           VARCHAR(100) NOT NULL COMMENT '2 报账点编码',
    payment_bill_code            VARCHAR(100) NOT NULL COMMENT '3 缴费单编码',
    payment_start                DATE NOT NULL COMMENT '4 缴费期始',
    payment_end                  DATE NULL COMMENT '5 缴费期终',
    meter_code                   VARCHAR(100) NOT NULL COMMENT '6 电表编码',
    meter_account_no             VARCHAR(100) NULL COMMENT '7 电表户号',
    meter_multiplier             DECIMAL(18,6) NULL COMMENT '8 电表倍率',
    actual_allocation_ratio      DECIMAL(10,6) NULL COMMENT '9 实际分摊比例',
    previous_allocation_ratio    DECIMAL(10,6) NULL COMMENT '10 上次分摊比例',
    previous_reading             DECIMAL(18,6) NULL COMMENT '11 电表上期读数',
    current_reading              DECIMAL(18,6) NULL COMMENT '12 本期读数',
    meter_consumption_kwh        DECIMAL(18,6) NULL COMMENT '13 电表耗电量',
    allocated_kwh                DECIMAL(18,6) NULL COMMENT '14 分摊后度数',
    unit_price_1                 DECIMAL(18,6) NULL COMMENT '15 单价1',
    electricity_1               DECIMAL(18,6) NULL COMMENT '16 电量1',
    line_loss_electricity_1      DECIMAL(18,6) NULL COMMENT '17 电损电量1',
    flat_previous_reading        DECIMAL(18,6) NULL COMMENT '18 平上期读数',
    flat_current_reading         DECIMAL(18,6) NULL COMMENT '19 平本期读数',
    flat_reset_count             INT NULL COMMENT '20 平归零次数',
    unit_price_2                 DECIMAL(18,6) NULL COMMENT '21 单价2',
    electricity_2               DECIMAL(18,6) NULL COMMENT '22 电量2',
    line_loss_electricity_2      DECIMAL(18,6) NULL COMMENT '23 电损电量2',
    peak_previous_reading        DECIMAL(18,6) NULL COMMENT '24 峰上期读数',
    peak_current_reading         DECIMAL(18,6) NULL COMMENT '25 峰本期读数',
    peak_reset_count             INT NULL COMMENT '26 峰归零次数',
    unit_price_3                 DECIMAL(18,6) NULL COMMENT '27 单价3',
    electricity_3               DECIMAL(18,6) NULL COMMENT '28 电量3',
    line_loss_electricity_3      DECIMAL(18,6) NULL COMMENT '29 电损电量3',
    valley_previous_reading      DECIMAL(18,6) NULL COMMENT '30 谷上期读数',
    valley_current_reading       DECIMAL(18,6) NULL COMMENT '31 谷本期读数',
    valley_reset_count           INT NULL COMMENT '32 谷归零次数',
    unit_price_4                 DECIMAL(18,6) NULL COMMENT '33 单价4',
    electricity_4               DECIMAL(18,6) NULL COMMENT '34 电量4',
    line_loss_electricity_4      DECIMAL(18,6) NULL COMMENT '35 电损电量4',
    sharp_previous_reading       DECIMAL(18,6) NULL COMMENT '36 尖上期读数',
    sharp_current_reading        DECIMAL(18,6) NULL COMMENT '37 尖本期读数',
    sharp_reset_count            INT NULL COMMENT '38 尖归零次数',
    electricity_amount_excl_tax DECIMAL(18,6) NULL COMMENT '39 电费不含税金额',
    electricity_tax             DECIMAL(18,6) NULL COMMENT '40 电费税金',
    line_loss_amount_excl_tax    DECIMAL(18,6) NULL COMMENT '41 电损不含税金额',
    line_loss_tax                DECIMAL(18,6) NULL COMMENT '42 电损税金',

    values_json                   LONGTEXT NOT NULL COMMENT '??????/?????? raw_row_json',

    created_at                   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at                   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                 ON UPDATE CURRENT_TIMESTAMP(3),

    UNIQUE KEY uk_meter_public(public_id),

    UNIQUE KEY uk_meter_business(
        payment_bill_code,
        meter_code,
        payment_start
    ),

    INDEX idx_meter_bp_period(billing_point_code,data_period),
    INDEX idx_meter_city_period(city_code,data_period),
    INDEX idx_meter_code(meter_code),
    INDEX idx_meter_account(meter_account_no),
    INDEX idx_meter_payment(payment_bill_code)
) COMMENT='电表读数，完整42源字段';

CREATE TABLE benchmark_value (
    id                       BIGINT PRIMARY KEY AUTO_INCREMENT,
    public_id                CHAR(36) NOT NULL,

    /* 系统字段 */
    data_period              CHAR(7) NOT NULL,
    period_start             DATE NOT NULL,
    period_end               DATE NOT NULL,
    city_code                VARCHAR(32) NOT NULL,
    district_code            VARCHAR(32) NULL,
    source_import_job_id     BIGINT NOT NULL,
    source_row_no            INT NOT NULL,
    raw_row_json             JSON NOT NULL,

    /* 1-39 */
    billing_point_code       VARCHAR(100) NOT NULL COMMENT '1 报账点编码',
    billing_point_name       VARCHAR(255) NULL COMMENT '2 报账点名称',
    billing_point_status     VARCHAR(100) NULL COMMENT '3 报账点状态',
    city_name                VARCHAR(100) NOT NULL COMMENT '4 所属地市',
    district_name            VARCHAR(100) NULL COMMENT '5 所属区县',
    benchmark_year           INT NOT NULL COMMENT '6 年份',
    benchmark_month          INT NOT NULL COMMENT '7 月份',
    month_avg_benchmark      DECIMAL(18,6) NOT NULL COMMENT '8 月平均标杆',
    day_01                   DECIMAL(18,6) NULL COMMENT '9 日标杆值_1日',
    day_02                   DECIMAL(18,6) NULL COMMENT '10 日标杆值_2日',
    day_03                   DECIMAL(18,6) NULL COMMENT '11 日标杆值_3日',
    day_04                   DECIMAL(18,6) NULL COMMENT '12 日标杆值_4日',
    day_05                   DECIMAL(18,6) NULL COMMENT '13 日标杆值_5日',
    day_06                   DECIMAL(18,6) NULL COMMENT '14 日标杆值_6日',
    day_07                   DECIMAL(18,6) NULL COMMENT '15 日标杆值_7日',
    day_08                   DECIMAL(18,6) NULL COMMENT '16 日标杆值_8日',
    day_09                   DECIMAL(18,6) NULL COMMENT '17 日标杆值_9日',
    day_10                   DECIMAL(18,6) NULL COMMENT '18 日标杆值_10日',
    day_11                   DECIMAL(18,6) NULL COMMENT '19 日标杆值_11日',
    day_12                   DECIMAL(18,6) NULL COMMENT '20 日标杆值_12日',
    day_13                   DECIMAL(18,6) NULL COMMENT '21 日标杆值_13日',
    day_14                   DECIMAL(18,6) NULL COMMENT '22 日标杆值_14日',
    day_15                   DECIMAL(18,6) NULL COMMENT '23 日标杆值_15日',
    day_16                   DECIMAL(18,6) NULL COMMENT '24 日标杆值_16日',
    day_17                   DECIMAL(18,6) NULL COMMENT '25 日标杆值_17日',
    day_18                   DECIMAL(18,6) NULL COMMENT '26 日标杆值_18日',
    day_19                   DECIMAL(18,6) NULL COMMENT '27 日标杆值_19日',
    day_20                   DECIMAL(18,6) NULL COMMENT '28 日标杆值_20日',
    day_21                   DECIMAL(18,6) NULL COMMENT '29 日标杆值_21日',
    day_22                   DECIMAL(18,6) NULL COMMENT '30 日标杆值_22日',
    day_23                   DECIMAL(18,6) NULL COMMENT '31 日标杆值_23日',
    day_24                   DECIMAL(18,6) NULL COMMENT '32 日标杆值_24日',
    day_25                   DECIMAL(18,6) NULL COMMENT '33 日标杆值_25日',
    day_26                   DECIMAL(18,6) NULL COMMENT '34 日标杆值_26日',
    day_27                   DECIMAL(18,6) NULL COMMENT '35 日标杆值_27日',
    day_28                   DECIMAL(18,6) NULL COMMENT '36 日标杆值_28日',
    day_29                   DECIMAL(18,6) NULL COMMENT '37 日标杆值_29日',
    day_30                   DECIMAL(18,6) NULL COMMENT '38 日标杆值_30日',
    day_31                   DECIMAL(18,6) NULL COMMENT '39 日标杆值_31日',

    /* 系统计算字段，不属于39个源字段 */
    day_total                DECIMAL(18,6) NOT NULL COMMENT '有效日标杆合计',
    calculated_month_avg     DECIMAL(18,6) NOT NULL COMMENT '系统计算月平均',
    validation_status        VARCHAR(20) NOT NULL COMMENT 'PASS/FAILED',
    validation_message       TEXT NULL,

    benchmark_month_value     DECIMAL(24,6) NULL COMMENT '??????????? month_avg_benchmark',
    calculated_day_total      DECIMAL(24,6) NULL COMMENT '???????',
    values_json               LONGTEXT NOT NULL COMMENT '??????/?????? raw_row_json',

    created_at               DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at               DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                             ON UPDATE CURRENT_TIMESTAMP(3),

    UNIQUE KEY uk_benchmark_public(public_id),
    UNIQUE KEY uk_benchmark_business(
        billing_point_code,
        benchmark_year,
        benchmark_month
    ),

    INDEX idx_benchmark_bp_period(billing_point_code,data_period),
    INDEX idx_benchmark_city_period(city_code,data_period)
) COMMENT='标杆值，完整39源字段';

CREATE TABLE audit_result (
    id                              BIGINT PRIMARY KEY AUTO_INCREMENT,
    public_id                       CHAR(36) NOT NULL,

    billing_point_code              VARCHAR(100) NOT NULL,
    billing_point_name              VARCHAR(255) NOT NULL,
    city_code                       VARCHAR(32) NOT NULL,
    district_code                   VARCHAR(32) NULL,

    data_period                     CHAR(7) NOT NULL,
    period_start                    DATE NOT NULL,
    period_end                      DATE NOT NULL,

    /* 缴费汇总 */
    payment_count                   INT NOT NULL DEFAULT 0,
    payment_eligible                TINYINT(1) NOT NULL DEFAULT 0,
    payment_eligibility_reason      VARCHAR(500) NULL,
    aggregated_payment_days         INT NULL,
    actual_report_amount            DECIMAL(18,6) NULL,

    /* 本期用电 */
    actual_total_kwh                DECIMAL(18,6) NULL,
    current_daily_avg_kwh           DECIMAL(18,6) NULL,

    /* 同比 */
    yoy_applicable                  TINYINT(1) NOT NULL DEFAULT 0,
    yoy_na_reason                   VARCHAR(500) NULL,
    yoy_reference_period            CHAR(7) NULL,
    yoy_reference_start             DATE NULL,
    yoy_reference_end               DATE NULL,
    yoy_reference_total_kwh         DECIMAL(18,6) NULL,
    yoy_reference_daily_kwh_c       DECIMAL(18,6) NULL,
    yoy_current_benchmark_avg_a     DECIMAL(18,6) NULL,
    yoy_reference_benchmark_avg_b   DECIMAL(18,6) NULL,
    yoy_factor_k                     DECIMAL(18,6) NULL,
    yoy_threshold_daily_kwh          DECIMAL(18,6) NULL,
    yoy_exceed_ratio                 DECIMAL(18,6) NULL,
    yoy_result                       VARCHAR(20) NULL COMMENT 'NORMAL/OVER_LIMIT/NA',

    /* 环比 */
    mom_applicable                  TINYINT(1) NOT NULL DEFAULT 0,
    mom_na_reason                   VARCHAR(500) NULL,
    mom_reference_period            CHAR(7) NULL,
    mom_reference_start             DATE NULL,
    mom_reference_end               DATE NULL,
    mom_reference_total_kwh         DECIMAL(18,6) NULL,
    mom_reference_daily_kwh_c       DECIMAL(18,6) NULL,
    mom_current_benchmark_avg_a     DECIMAL(18,6) NULL,
    mom_reference_benchmark_avg_b   DECIMAL(18,6) NULL,
    mom_factor_k                     DECIMAL(18,6) NULL,
    mom_threshold_daily_kwh          DECIMAL(18,6) NULL,
    mom_exceed_ratio                 DECIMAL(18,6) NULL,
    mom_result                       VARCHAR(20) NULL COMMENT 'NORMAL/OVER_LIMIT/NA',

    /* 额定标杆 */
    rated_applicable                TINYINT(1) NOT NULL DEFAULT 0,
    rated_na_reason                 VARCHAR(500) NULL,
    rated_total_kwh                 DECIMAL(18,6) NULL,
    rated_month_avg_kwh             DECIMAL(18,6) NULL,
    rated_exceed_ratio              DECIMAL(18,6) NULL,
    rated_result                    VARCHAR(20) NULL COMMENT 'NORMAL/OVER_LIMIT/NA',

    /* 最终 */
    audit_status                    VARCHAR(20) NOT NULL
                                    COMMENT 'NORMAL/OVER_LIMIT/NA',
    exceed_type                     VARCHAR(30) NULL
                                    COMMENT 'YOY_ONLY/MOM_ONLY/RATED_ONLY/MULTIPLE/NA',
    max_exceed_ratio                DECIMAL(18,6) NULL,

    report_status                   VARCHAR(20) NOT NULL DEFAULT 'NA'
                                    COMMENT 'WAITING/GENERATING/GENERATED/NA',

    calculation_detail              JSON NULL,
    calculated_at                   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at                      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                    ON UPDATE CURRENT_TIMESTAMP(3),

    /* ??????????????????? formal_report ???? */
    actual_energy                   DECIMAL(24,6) NULL,
    actual_amount                   DECIMAL(24,6) NULL,
    yoy_reference_energy            DECIMAL(24,6) NULL,
    mom_reference_energy            DECIMAL(24,6) NULL,
    rated_benchmark_energy          DECIMAL(24,6) NULL,
    yoy_ratio                       DECIMAL(18,8) NULL,
    mom_ratio                       DECIMAL(18,8) NULL,
    rated_ratio                     DECIMAL(18,8) NULL,
    max_ratio                       DECIMAL(18,8) NULL,
    over_limit_type                 VARCHAR(64) NULL,
    detail_json                     LONGTEXT NULL,
    version                         BIGINT NOT NULL DEFAULT 0,

    UNIQUE KEY uk_audit_public(public_id),

    UNIQUE KEY uk_audit_business(
        billing_point_code,
        period_start,
        period_end
    ),

    INDEX idx_audit_city_period(city_code,data_period),
    INDEX idx_audit_task(
        city_code,
        data_period,
        audit_status,
        report_status,
        max_exceed_ratio
    )
) COMMENT='报账点月度稽核结果';

CREATE TABLE report_month_sequence (
    biz_month CHAR(6) NOT NULL COMMENT 'YYYYMM',
    current_value INT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_report_month_sequence PRIMARY KEY (biz_month)
) COMMENT='正式报告月流水';

CREATE TABLE formal_report (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    report_no VARCHAR(50) NOT NULL,
    billing_point_code VARCHAR(100) NOT NULL,
    billing_point_name VARCHAR(255) NOT NULL,
    city_code VARCHAR(32) NOT NULL,
    city_name VARCHAR(100) NOT NULL,
    district_code VARCHAR(32) NULL,
    district_name VARCHAR(100) NULL,
    data_period CHAR(7) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    source_type VARCHAR(20) NOT NULL COMMENT 'GENERATED/IMPORTED',
    actual_total_kwh DECIMAL(18,6) NULL,
    actual_report_amount DECIMAL(18,6) NULL,
    exceed_type VARCHAR(30) NULL,
    max_exceed_ratio DECIMAL(18,6) NULL,
    report_title VARCHAR(500) NULL,
    situation_text LONGTEXT NULL,
    analysis_text LONGTEXT NULL,
    rectification_text LONGTEXT NULL,
    final_content LONGTEXT NULL,
    billing_snapshot_json JSON NOT NULL,
    payment_snapshot_json JSON NULL,
    meter_snapshot_json JSON NULL,
    benchmark_snapshot_json JSON NULL,
    audit_snapshot_json JSON NOT NULL,
    rule_snapshot_json JSON NOT NULL,
    word_file_id BIGINT NOT NULL,
    pdf_file_id BIGINT NULL,
    last_correction_reason TEXT NULL,
    generated_by BIGINT NOT NULL,
    generated_at DATETIME(3) NOT NULL,
    updated_by BIGINT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_formal_report PRIMARY KEY (id),
    CONSTRAINT uk_formal_report__public_id UNIQUE (public_id),
    CONSTRAINT uk_formal_report__report_no UNIQUE (report_no),
    CONSTRAINT uk_formal_report__business UNIQUE (billing_point_code, period_start, period_end),
    CONSTRAINT fk_formal_report__word FOREIGN KEY (word_file_id) REFERENCES stored_file (id),
    CONSTRAINT fk_formal_report__pdf FOREIGN KEY (pdf_file_id) REFERENCES stored_file (id)
) COMMENT='唯一正式报告，仅保存最终确定内容';

CREATE INDEX idx_formal_report__city_period ON formal_report (city_code, data_period);
CREATE INDEX idx_formal_report__time ON formal_report (generated_at);

CREATE TABLE report_attachment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    report_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    attachment_type VARCHAR(30) NOT NULL COMMENT 'IMAGE/OTHER',
    caption VARCHAR(500) NULL,
    sort_no INT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_report_attachment PRIMARY KEY (id),
    CONSTRAINT fk_report_attachment__report
        FOREIGN KEY (report_id) REFERENCES formal_report (id) ON DELETE CASCADE,
    CONSTRAINT fk_report_attachment__file FOREIGN KEY (file_id) REFERENCES stored_file (id)
) COMMENT='正式报告附件';

CREATE INDEX idx_report_attachment__report ON report_attachment (report_id);

CREATE TABLE report_correction_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    report_id BIGINT NOT NULL,
    correction_reason TEXT NOT NULL,
    correction_type VARCHAR(30) NOT NULL COMMENT 'EDIT_CONTENT/REPLACE_WORD',
    operator_id BIGINT NOT NULL,
    operation_status VARCHAR(20) NOT NULL COMMENT 'SUCCESS/FAILED',
    failure_reason TEXT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_report_correction_log PRIMARY KEY (id),
    CONSTRAINT fk_report_correction_log__report FOREIGN KEY (report_id) REFERENCES formal_report (id)
) COMMENT='正式报告更正操作日志，不构成正式报告版本';

CREATE INDEX idx_report_correction_log__report ON report_correction_log (report_id);
CREATE INDEX idx_report_correction_log__time ON report_correction_log (created_at);

CREATE TABLE report_draft (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    billing_point_code VARCHAR(100) NULL,
    billing_point_name VARCHAR(255) NULL,
    data_period CHAR(7) NULL,
    period_start DATE NULL,
    period_end DATE NULL,
    billing_snapshot_id BIGINT NULL,
    audit_result_id BIGINT NULL,
    draft_mode VARCHAR(20) NULL DEFAULT 'NEW' COMMENT 'NEW/CORRECTION',
    base_report_id BIGINT NULL,
    report_title VARCHAR(500) NULL,
    situation_text LONGTEXT NULL COMMENT '一、情况说明',
    analysis_text LONGTEXT NULL COMMENT '二、排查分析',
    rectification_text LONGTEXT NULL COMMENT '三、整改小结',
    full_content LONGTEXT NULL,
    billing_point_snapshot_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    title VARCHAR(500) NOT NULL,
    situation LONGTEXT NOT NULL,
    analysis LONGTEXT NOT NULL,
    rectification LONGTEXT NOT NULL,
    current_version_no INT NOT NULL,
    formal_report_public_id CHAR(36) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64) NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_report_draft PRIMARY KEY (id),
    CONSTRAINT uk_report_draft__public_id UNIQUE (public_id),
    CONSTRAINT uk_report_draft__snapshot UNIQUE (billing_point_snapshot_id),
    CONSTRAINT fk_report_draft__snapshot
        FOREIGN KEY (billing_point_snapshot_id) REFERENCES billing_point_snapshot (id)
);

CREATE TABLE audit_report (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    report_number VARCHAR(32) NOT NULL,
    billing_point_snapshot_id BIGINT NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    title VARCHAR(500) NOT NULL,
    situation LONGTEXT NOT NULL,
    analysis LONGTEXT NOT NULL,
    rectification LONGTEXT NOT NULL,
    word_file_id BIGINT NOT NULL,
    pdf_file_id BIGINT NOT NULL,
    business_snapshot_json LONGTEXT NOT NULL,
    generated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_audit_report PRIMARY KEY (id),
    CONSTRAINT uk_audit_report__public_id UNIQUE (public_id),
    CONSTRAINT uk_audit_report__number UNIQUE (report_number),
    CONSTRAINT uk_audit_report__snapshot UNIQUE (billing_point_snapshot_id),
    CONSTRAINT fk_audit_report__snapshot
        FOREIGN KEY (billing_point_snapshot_id) REFERENCES billing_point_snapshot (id),
    CONSTRAINT fk_audit_report__word FOREIGN KEY (word_file_id) REFERENCES stored_file (id),
    CONSTRAINT fk_audit_report__pdf FOREIGN KEY (pdf_file_id) REFERENCES stored_file (id)
);

CREATE TABLE report_correction (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    report_id BIGINT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    correction_type VARCHAR(32) NOT NULL,
    task_public_id CHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64) NOT NULL,
    completed_at DATETIME(3) NULL,
    CONSTRAINT pk_report_correction PRIMARY KEY (id),
    CONSTRAINT uk_report_correction__public_id UNIQUE (public_id),
    CONSTRAINT fk_report_correction__report FOREIGN KEY (report_id) REFERENCES audit_report (id)
);

CREATE TABLE report_number_sequence (
    business_month CHAR(6) NOT NULL,
    next_value BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_report_number_sequence PRIMARY KEY (business_month)
);
