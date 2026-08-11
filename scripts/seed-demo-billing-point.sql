-- Development-only demo data for the billing point detail prototype.
-- It keeps database records atomic; any merged display remains in the frontend.

START TRANSACTION;

SET @period = '2026-06';
SET @city_code = '320400';
SET @bp_code = 'ZDBZD-JS-2019-103102';
SET @bp_name = '陈堡电费测试旺';

DELETE rdv
  FROM report_draft_version rdv
  JOIN report_draft rd ON rd.id = rdv.draft_id
  JOIN billing_point_snapshot s ON s.id = rd.billing_point_snapshot_id
 WHERE s.billing_point_code = @bp_code AND s.data_period = @period;

DELETE rdm
  FROM report_draft_message rdm
  JOIN report_draft rd ON rd.id = rdm.draft_id
  JOIN billing_point_snapshot s ON s.id = rd.billing_point_snapshot_id
 WHERE s.billing_point_code = @bp_code AND s.data_period = @period;

DELETE rd
  FROM report_draft rd
  JOIN billing_point_snapshot s ON s.id = rd.billing_point_snapshot_id
 WHERE s.billing_point_code = @bp_code AND s.data_period = @period;

DELETE FROM audit_result
 WHERE billing_point_code = @bp_code AND data_period = @period;

DELETE FROM imported_record
 WHERE billing_point_code = @bp_code AND data_period = @period;

DELETE FROM billing_point_master
 WHERE billing_point_code = @bp_code;

DELETE FROM billing_point_snapshot
 WHERE billing_point_code = @bp_code AND data_period = @period;

INSERT INTO stored_file
    (public_id, storage_name, original_name, media_type, byte_size, sha256, purpose, created_by)
VALUES
    ('00000000-0000-0000-0000-000000000101', 'demo-billing-point-202606.xlsx', 'demo-billing-point-202606.xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 1, REPEAT('0', 64), 'IMPORT_SOURCE', 'demo'),
    ('00000000-0000-0000-0000-000000000102', 'demo-payment-202606.xlsx', 'demo-payment-202606.xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 1, REPEAT('0', 64), 'IMPORT_SOURCE', 'demo'),
    ('00000000-0000-0000-0000-000000000103', 'demo-meter-reading-202606.xlsx', 'demo-meter-reading-202606.xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 1, REPEAT('0', 64), 'IMPORT_SOURCE', 'demo'),
    ('00000000-0000-0000-0000-000000000104', 'demo-benchmark-202606.xlsx', 'demo-benchmark-202606.xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 1, REPEAT('0', 64), 'IMPORT_SOURCE', 'demo')
ON DUPLICATE KEY UPDATE original_name = VALUES(original_name);

INSERT INTO import_batch
    (public_id, dataset_type, data_period, city_code, status, source_file_id, task_public_id,
     row_count, error_count, errors_json, activated_at, created_by, updated_by)
VALUES
    ('10000000-0000-0000-0000-000000000101', 'BILLING_POINT', @period, @city_code, 'ACTIVE', (SELECT id FROM stored_file WHERE public_id = '00000000-0000-0000-0000-000000000101'), '20000000-0000-0000-0000-000000000101', 1, 0, '[]', NOW(3), 'demo', 'demo'),
    ('10000000-0000-0000-0000-000000000102', 'PAYMENT', @period, @city_code, 'ACTIVE', (SELECT id FROM stored_file WHERE public_id = '00000000-0000-0000-0000-000000000102'), '20000000-0000-0000-0000-000000000102', 2, 0, '[]', NOW(3), 'demo', 'demo'),
    ('10000000-0000-0000-0000-000000000103', 'METER_READING', @period, @city_code, 'ACTIVE', (SELECT id FROM stored_file WHERE public_id = '00000000-0000-0000-0000-000000000103'), '20000000-0000-0000-0000-000000000103', 3, 0, '[]', NOW(3), 'demo', 'demo'),
    ('10000000-0000-0000-0000-000000000104', 'BENCHMARK', @period, @city_code, 'ACTIVE', (SELECT id FROM stored_file WHERE public_id = '00000000-0000-0000-0000-000000000104'), '20000000-0000-0000-0000-000000000104', 3, 0, '[]', NOW(3), 'demo', 'demo')
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    row_count = VALUES(row_count),
    error_count = 0,
    errors_json = '[]',
    activated_at = NOW(3),
    updated_at = NOW(3),
    updated_by = 'demo';

SET @billing_batch_id = (SELECT id FROM import_batch WHERE public_id = '10000000-0000-0000-0000-000000000101');
SET @payment_batch_id = (SELECT id FROM import_batch WHERE public_id = '10000000-0000-0000-0000-000000000102');
SET @meter_batch_id = (SELECT id FROM import_batch WHERE public_id = '10000000-0000-0000-0000-000000000103');
SET @benchmark_batch_id = (SELECT id FROM import_batch WHERE public_id = '10000000-0000-0000-0000-000000000104');

SET @overview_json = JSON_OBJECT(
    '审核状态', '超标',
    '报账点编码', @bp_code,
    '报账点名称', @bp_name,
    '报账点类型', '楼宇类报账点',
    '所属成本中心', '常州市分公司',
    '成本中心编码', '302332102000',
    '所属地市', '常州市',
    '所属区县', '钟楼区',
    '所属部门', '网络部',
    '报账点状态', '启用',
    '用电类别', '商用电',
    '电压等级', '10kV',
    '计费方式（第1项）', '非平峰谷',
    '供电类型', '直供电',
    '电费缴费周期', '月度',
    '电损计算方式', '按电量比例',
    '合同或固化编码', 'ZDGH-JS-2018-020295',
    '合同或固化名称', '江苏铁塔站点供电合同',
    '合同或固化状态', '有效',
    '合同或固化期终', '2027-12-31',
    '供应商名称', '国网江苏省电力有限公司常州供电分公司',
    '供应商编码', 'JS-DL-000118',
    '关联资源编码', 'ZY-JS-821261-0046',
    '关联资源名称', '陈堡电费测试旺',
    '资源类型', '机房',
    '资源状态', '在用',
    '关联电表编码', '1548366369',
    '电表户号', '3205103991060',
    '电表状态', '在用',
    '电表倍率', '1.0'
);

INSERT INTO billing_point_snapshot
    (public_id, billing_point_code, city_code, billing_point_name, data_period,
     period_start, period_end, data_json, source_batch_id)
VALUES
    ('30000000-0000-0000-0000-000000000101', @bp_code, @city_code, @bp_name, @period,
     '2026-06-01', '2026-06-30', @overview_json, @billing_batch_id)
ON DUPLICATE KEY UPDATE
    city_code = VALUES(city_code),
    billing_point_name = VALUES(billing_point_name),
    period_start = VALUES(period_start),
    period_end = VALUES(period_end),
    data_json = VALUES(data_json),
    source_batch_id = VALUES(source_batch_id),
    updated_at = NOW(3);

INSERT INTO billing_point_master
    (billing_point_code, city_code, billing_point_name, current_period, status, data_json, source_batch_id)
VALUES
    (@bp_code, @city_code, @bp_name, @period, '启用', @overview_json, @billing_batch_id)
ON DUPLICATE KEY UPDATE
    city_code = VALUES(city_code),
    billing_point_name = VALUES(billing_point_name),
    current_period = VALUES(current_period),
    status = VALUES(status),
    data_json = VALUES(data_json),
    source_batch_id = VALUES(source_batch_id),
    updated_at = NOW(3);

INSERT INTO imported_record
    (batch_id, dataset_type, data_period, city_code, billing_point_code, payment_code,
     meter_code, business_key, source_row, values_json, is_active)
VALUES
    (@payment_batch_id, 'PAYMENT', @period, @city_code, @bp_code, 'JFZDPT-JS-20260714-10960',
     NULL, CONCAT(@bp_code, '|JFZDPT-JS-20260714-10960|', @period), 2,
     JSON_OBJECT(
        '审核状态', '审核通过',
        '审核结果', '通过',
        '当前审核环节', '城市财务复核',
        '当前审核人', '王毅',
        '财务返回单号', 'CW202607150086',
        '缴费单编码', 'JFZDPT-JS-20260714-10960',
        '所属地市', '常州市',
        '所属区县', '钟楼区',
        '报账点编码', @bp_code,
        '报账点名称', @bp_name,
        '合同编码', 'ZDGH-JS-2018-020295',
        '购电方式', '直接购电',
        '供电类型', '直供电',
        '缴费申请日期', '2026-07-14',
        '缴费期始', '2026-06-01',
        '缴费期终', '2026-06-30',
        '缴费天数', '30',
        '日均耗电量', '42.18',
        '实际报账金额', '523.51',
        '系统计算金额', '523.51',
        '实际总耗电量', '780.77',
        '业务大类', '用电成本',
        '业务小类', '基站电费',
        '市场段', '公众市场',
        '产品段', '基础资源',
        '异常情况', '无',
        '标杆是否超标', '是',
        '额定功率标杆', '1265.40',
        '必填备注', '本月抄表周期完整',
        '是否为首单', '否'
     ), TRUE),
    (@payment_batch_id, 'PAYMENT', @period, @city_code, @bp_code, 'JFZDPT-JS-20260714-10961',
     NULL, CONCAT(@bp_code, '|JFZDPT-JS-20260714-10961|', @period), 3,
     JSON_OBJECT(
        '审核状态', '审核通过',
        '审核结果', '通过',
        '当前审核环节', '归档',
        '当前审核人', '系统',
        '财务返回单号', 'CW202607150087',
        '缴费单编码', 'JFZDPT-JS-20260714-10961',
        '所属地市', '常州市',
        '所属区县', '钟楼区',
        '报账点编码', @bp_code,
        '报账点名称', @bp_name,
        '合同编码', 'ZDGH-JS-2018-020295',
        '购电方式', '直接购电',
        '供电类型', '直供电',
        '缴费申请日期', '2026-07-14',
        '缴费期始', '2026-06-01',
        '缴费期终', '2026-06-30',
        '缴费天数', '30',
        '日均耗电量', '18.38',
        '实际报账金额', '372.46',
        '系统计算金额', '372.46',
        '实际总耗电量', '551.14',
        '业务大类', '用电成本',
        '业务小类', '基站电费',
        '市场段', '公众市场',
        '产品段', '基础资源',
        '异常情况', '无',
        '标杆是否超标', '否',
        '额定功率标杆', '1265.40',
        '必填备注', '辅助电表分摊',
        '是否为首单', '否'
     ), TRUE)
ON DUPLICATE KEY UPDATE
    payment_code = VALUES(payment_code),
    values_json = VALUES(values_json),
    is_active = TRUE;

INSERT INTO imported_record
    (batch_id, dataset_type, data_period, city_code, billing_point_code, payment_code,
     meter_code, business_key, source_row, values_json, is_active)
VALUES
    (@meter_batch_id, 'METER_READING', @period, @city_code, @bp_code, 'JFZDPT-JS-20260714-10960',
     '1548366369', 'JFZDPT-JS-20260714-10960|1548366369|2026-06-01', 2,
     JSON_OBJECT(
        '报账点名称', @bp_name,
        '报账点编码', @bp_code,
        '缴费单编码', 'JFZDPT-JS-20260714-10960',
        '缴费期始', '2026-06-01',
        '缴费期终', '2026-06-30',
        '电表编码', '1548366369',
        '电表户号', '3205103991060',
        '电表倍率', '1.000000',
        '实际分摊比例', '100%',
        '上次分摊比例', '100%',
        '电表上期读数', '67520.11',
        '本期读数', '68300.88',
        '电表耗电量', '780.77',
        '分摊后度数', '780.77',
        '电费不含税金额', '463.28',
        '电费税金', '60.23',
        '电损不含税金额', '0.00',
        '电损税金', '0.00',
        '平上期读数', '23100.11',
        '平本期读数', '23460.21',
        '平归零次数', '0',
        '电量2', '360.10',
        '峰上期读数', '18500.00',
        '峰本期读数', '18820.44',
        '峰归零次数', '0',
        '电量3', '320.44',
        '谷上期读数', '25920.00',
        '谷本期读数', '26020.23',
        '谷归零次数', '0',
        '电量4', '100.23',
        '尖上期读数', '0.00',
        '尖本期读数', '0.00',
        '尖归零次数', '0'
     ), TRUE),
    (@meter_batch_id, 'METER_READING', @period, @city_code, @bp_code, 'JFZDPT-JS-20260714-10960',
     '1604813862', 'JFZDPT-JS-20260714-10960|1604813862|2026-06-01', 3,
     JSON_OBJECT(
        '报账点名称', @bp_name,
        '报账点编码', @bp_code,
        '缴费单编码', 'JFZDPT-JS-20260714-10960',
        '缴费期始', '2026-06-01',
        '缴费期终', '2026-06-30',
        '电表编码', '1604813862',
        '电表户号', '3205103991060',
        '电表倍率', '1.000000',
        '实际分摊比例', '40%',
        '上次分摊比例', '40%',
        '电表上期读数', '14001.10',
        '本期读数', '14507.35',
        '电表耗电量', '506.25',
        '分摊后度数', '204.23',
        '电费不含税金额', '180.73',
        '电费税金', '23.50',
        '电损不含税金额', '0.00',
        '电损税金', '0.00',
        '平上期读数', '4100.00',
        '平本期读数', '4288.20',
        '平归零次数', '0',
        '电量2', '188.20',
        '峰上期读数', '5000.00',
        '峰本期读数', '5188.40',
        '峰归零次数', '0',
        '电量3', '188.40',
        '谷上期读数', '4901.10',
        '谷本期读数', '5030.75',
        '谷归零次数', '0',
        '电量4', '129.65',
        '尖上期读数', '0.00',
        '尖本期读数', '0.00',
        '尖归零次数', '0'
     ), TRUE),
    (@meter_batch_id, 'METER_READING', @period, @city_code, @bp_code, 'JFZDPT-JS-20260714-10961',
     '240618810278', 'JFZDPT-JS-20260714-10961|240618810278|2026-06-01', 4,
     JSON_OBJECT(
        '报账点名称', @bp_name,
        '报账点编码', @bp_code,
        '缴费单编码', 'JFZDPT-JS-20260714-10961',
        '缴费期始', '2026-06-01',
        '缴费期终', '2026-06-30',
        '电表编码', '240618810278',
        '电表户号', 'WX2406000796',
        '电表倍率', '1.000000',
        '实际分摊比例', '100%',
        '上次分摊比例', '100%',
        '电表上期读数', '26000.00',
        '本期读数', '26346.91',
        '电表耗电量', '346.91',
        '分摊后度数', '346.91',
        '电费不含税金额', '329.61',
        '电费税金', '42.85',
        '电损不含税金额', '0.00',
        '电损税金', '0.00',
        '平上期读数', '9100.00',
        '平本期读数', '9202.00',
        '平归零次数', '0',
        '电量2', '102.00',
        '峰上期读数', '8300.00',
        '峰本期读数', '8494.91',
        '峰归零次数', '0',
        '电量3', '194.91',
        '谷上期读数', '8600.00',
        '谷本期读数', '8650.00',
        '谷归零次数', '0',
        '电量4', '50.00',
        '尖上期读数', '0.00',
        '尖本期读数', '0.00',
        '尖归零次数', '0'
     ), TRUE)
ON DUPLICATE KEY UPDATE
    payment_code = VALUES(payment_code),
    meter_code = VALUES(meter_code),
    values_json = VALUES(values_json),
    is_active = TRUE;

INSERT INTO imported_record
    (batch_id, dataset_type, data_period, city_code, billing_point_code, payment_code,
     meter_code, business_key, source_row, values_json, is_active)
VALUES
    (@benchmark_batch_id, 'BENCHMARK', @period, @city_code, @bp_code, NULL, NULL, CONCAT(@bp_code, '|2026-06|NORMAL'), 2,
     JSON_OBJECT(
        '报账点编码', @bp_code,
        '报账点名称', @bp_name,
        '报账点状态', '启用',
        '所属地市', '常州市',
        '所属区县', '钟楼区',
        '年份', '2026',
        '月份', '06',
        '月总标杆', '1265.40',
        '1', '42.18',
        '2', '42.18',
        '3', '42.18',
        '4', '42.18',
        '5', '42.18',
        '6', '42.18',
        '7', '42.18',
        '8', '42.18',
        '9', '42.18',
        '10', '42.18',
        '11', '42.18',
        '12', '42.18',
        '13', '42.18',
        '14', '42.18',
        '15', '42.18',
        '16', '42.18',
        '17', '42.18',
        '18', '42.18',
        '19', '42.18',
        '20', '42.18',
        '21', '42.18',
        '22', '42.18',
        '23', '42.18',
        '24', '42.18',
        '25', '42.18',
        '26', '42.18',
        '27', '42.18',
        '28', '42.18',
        '29', '42.18',
        '30', '42.18',
        '31', ''
     ), TRUE),
    (@benchmark_batch_id, 'BENCHMARK', @period, @city_code, 'ZDBZD-JS-2018-100096', NULL, NULL, 'ZDBZD-JS-2018-100096|2026-06', 3,
     JSON_OBJECT(
        '报账点编码', 'ZDBZD-JS-2018-100096',
        '报账点名称', '陈堡历史参考A',
        '报账点状态', '启用',
        '所属地市', '常州市',
        '所属区县', '钟楼区',
        '年份', '2026',
        '月份', '06',
        '月总标杆', '1835.40',
        '1', '61.18',
        '2', '61.18',
        '3', '61.18',
        '4', '61.18',
        '5', '61.18',
        '6', '61.18',
        '7', '61.18',
        '8', '61.18',
        '9', '61.18',
        '10', '61.18',
        '11', '61.18',
        '12', '61.18',
        '13', '61.18',
        '14', '61.18',
        '15', '61.18',
        '16', '61.18',
        '17', '61.18',
        '18', '61.18',
        '19', '61.18',
        '20', '61.18',
        '21', '61.18',
        '22', '61.18',
        '23', '61.18',
        '24', '61.18',
        '25', '61.18',
        '26', '61.18',
        '27', '61.18',
        '28', '61.18',
        '29', '61.18',
        '30', '61.18',
        '31', ''
     ), TRUE),
    (@benchmark_batch_id, 'BENCHMARK', @period, @city_code, 'ZDBZD-JS-2018-100986', NULL, NULL, 'ZDBZD-JS-2018-100986|2026-06', 4,
     JSON_OBJECT(
        '报账点编码', 'ZDBZD-JS-2018-100986',
        '报账点名称', '陈堡历史参考B',
        '报账点状态', '启用',
        '所属地市', '常州市',
        '所属区县', '钟楼区',
        '年份', '2026',
        '月份', '06',
        '月总标杆', '8593.20',
        '1', '286.44',
        '2', '286.44',
        '3', '286.44',
        '4', '286.44',
        '5', '286.44',
        '6', '286.44',
        '7', '286.44',
        '8', '286.44',
        '9', '286.44',
        '10', '286.44',
        '11', '286.44',
        '12', '286.44',
        '13', '286.44',
        '14', '286.44',
        '15', '286.44',
        '16', '286.44',
        '17', '286.44',
        '18', '286.44',
        '19', '286.44',
        '20', '286.44',
        '21', '286.44',
        '22', '286.44',
        '23', '286.44',
        '24', '286.44',
        '25', '286.44',
        '26', '286.44',
        '27', '286.44',
        '28', '286.44',
        '29', '286.44',
        '30', '286.44',
        '31', ''
     ), TRUE)
ON DUPLICATE KEY UPDATE values_json = VALUES(values_json), is_active = TRUE;

INSERT INTO audit_result
    (public_id, billing_point_code, city_code, data_period, payment_eligible,
     actual_energy, actual_amount, yoy_reference_energy, mom_reference_energy,
     rated_benchmark_energy, yoy_ratio, mom_ratio, rated_ratio, max_ratio,
     audit_status, over_limit_type, detail_json)
VALUES
    ('40000000-0000-0000-0000-000000000101', @bp_code, @city_code, @period, TRUE,
     1331.91, 895.97, 1100.00, 1210.00, 1265.40, 0.15800000, 0.07100000, 0.05260000, 0.15800000,
     'OVER_LIMIT', 'MULTIPLE',
     JSON_OBJECT(
        'finalStatus', 'OVER_LIMIT',
        'finalReason', '同比、环比与额定标杆存在超标，需生成稽核报告。',
        'ruleVersion', 'audit-v2026.1',
        'calculatedAt', '2026-07-02T10:36:00+08:00',
        'eligibilityReason', '缴费明细审核通过，电表读数完整。',
        'comparisons', JSON_ARRAY(
            JSON_OBJECT('key', 'YEAR_ON_YEAR', 'label', '历史同期电量同比标杆', 'status', 'OVER_LIMIT', 'baseline', '36.67', 'actual', '44.40', 'difference', '38.34', 'ratio', '15.80%', 'reason', '参考上一年度同账期日均用电量', 'formula', '当前日均 > 同比参考阈值'),
            JSON_OBJECT('key', 'MONTH_ON_MONTH', 'label', '历史环比电量标杆', 'status', 'OVER_LIMIT', 'baseline', '40.33', 'actual', '44.40', 'difference', '41.45', 'ratio', '7.10%', 'reason', '参考上一自然月日均用电量', 'formula', '当前日均 > 环比参考阈值'),
            JSON_OBJECT('key', 'RATED_BENCHMARK', 'label', '额定功率标杆', 'status', 'OVER_LIMIT', 'baseline', '1265.40', 'actual', '1331.91', 'difference', '1265.40', 'ratio', '5.26%', 'reason', '参考当月额定功率标杆值合计', 'formula', '实际总耗电量 > 额定标杆总量')
        )
     ))
ON DUPLICATE KEY UPDATE
    payment_eligible = TRUE,
    actual_energy = VALUES(actual_energy),
    actual_amount = VALUES(actual_amount),
    yoy_reference_energy = VALUES(yoy_reference_energy),
    mom_reference_energy = VALUES(mom_reference_energy),
    rated_benchmark_energy = VALUES(rated_benchmark_energy),
    yoy_ratio = VALUES(yoy_ratio),
    mom_ratio = VALUES(mom_ratio),
    rated_ratio = VALUES(rated_ratio),
    max_ratio = VALUES(max_ratio),
    audit_status = VALUES(audit_status),
    over_limit_type = VALUES(over_limit_type),
    detail_json = VALUES(detail_json),
    calculated_at = NOW(3);

COMMIT;
