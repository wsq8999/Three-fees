DELETE FROM report_draft
 WHERE id IN (
       SELECT duplicate_ids.id
         FROM (
             SELECT d.id,
                    ROW_NUMBER() OVER (
                        PARTITION BY s.billing_point_code, s.data_period, s.city_code
                        ORDER BY d.updated_at DESC, d.id DESC
                    ) AS row_no
               FROM report_draft d
               JOIN billing_point_snapshot s ON s.id = d.billing_point_snapshot_id
         ) duplicate_ids
        WHERE duplicate_ids.row_no > 1
   );

DELETE FROM historical_report_import
 WHERE id IN (
       SELECT duplicate_ids.id
         FROM (
             SELECT h.id,
                    ROW_NUMBER() OVER (
                        PARTITION BY s.billing_point_code, s.data_period, s.city_code
                        ORDER BY h.updated_at DESC, h.id DESC
                    ) AS row_no
               FROM historical_report_import h
               JOIN billing_point_snapshot s ON s.id = h.billing_point_snapshot_id
         ) duplicate_ids
        WHERE duplicate_ids.row_no > 1
   );

DELETE FROM report_correction
 WHERE report_id IN (
       SELECT duplicate_ids.id
         FROM (
             SELECT r.id,
                    ROW_NUMBER() OVER (
                        PARTITION BY s.billing_point_code, s.data_period, s.city_code
                        ORDER BY r.updated_at DESC, r.id DESC
                    ) AS row_no
               FROM audit_report r
               JOIN billing_point_snapshot s ON s.id = r.billing_point_snapshot_id
         ) duplicate_ids
        WHERE duplicate_ids.row_no > 1
   );

DELETE FROM audit_report
 WHERE id IN (
       SELECT duplicate_ids.id
         FROM (
             SELECT r.id,
                    ROW_NUMBER() OVER (
                        PARTITION BY s.billing_point_code, s.data_period, s.city_code
                        ORDER BY r.updated_at DESC, r.id DESC
                    ) AS row_no
               FROM audit_report r
               JOIN billing_point_snapshot s ON s.id = r.billing_point_snapshot_id
         ) duplicate_ids
        WHERE duplicate_ids.row_no > 1
   );

UPDATE report_draft
   SET billing_point_snapshot_id = (
       SELECT mapped.keep_id
         FROM (
             SELECT id,
                    FIRST_VALUE(id) OVER (
                        PARTITION BY billing_point_code, data_period, city_code
                        ORDER BY updated_at DESC, id DESC
                    ) AS keep_id
               FROM billing_point_snapshot
         ) mapped
        WHERE mapped.id = report_draft.billing_point_snapshot_id
   )
 WHERE billing_point_snapshot_id IN (
       SELECT duplicate_ids.id
         FROM (
             SELECT id,
                    FIRST_VALUE(id) OVER (
                        PARTITION BY billing_point_code, data_period, city_code
                        ORDER BY updated_at DESC, id DESC
                    ) AS keep_id
               FROM billing_point_snapshot
         ) duplicate_ids
        WHERE duplicate_ids.id <> duplicate_ids.keep_id
   );

UPDATE audit_report
   SET billing_point_snapshot_id = (
       SELECT mapped.keep_id
         FROM (
             SELECT id,
                    FIRST_VALUE(id) OVER (
                        PARTITION BY billing_point_code, data_period, city_code
                        ORDER BY updated_at DESC, id DESC
                    ) AS keep_id
               FROM billing_point_snapshot
         ) mapped
        WHERE mapped.id = audit_report.billing_point_snapshot_id
   )
 WHERE billing_point_snapshot_id IN (
       SELECT duplicate_ids.id
         FROM (
             SELECT id,
                    FIRST_VALUE(id) OVER (
                        PARTITION BY billing_point_code, data_period, city_code
                        ORDER BY updated_at DESC, id DESC
                    ) AS keep_id
               FROM billing_point_snapshot
         ) duplicate_ids
        WHERE duplicate_ids.id <> duplicate_ids.keep_id
   );

UPDATE historical_report_import
   SET billing_point_snapshot_id = (
       SELECT mapped.keep_id
         FROM (
             SELECT id,
                    FIRST_VALUE(id) OVER (
                        PARTITION BY billing_point_code, data_period, city_code
                        ORDER BY updated_at DESC, id DESC
                    ) AS keep_id
               FROM billing_point_snapshot
         ) mapped
        WHERE mapped.id = historical_report_import.billing_point_snapshot_id
   )
 WHERE billing_point_snapshot_id IN (
       SELECT duplicate_ids.id
         FROM (
             SELECT id,
                    FIRST_VALUE(id) OVER (
                        PARTITION BY billing_point_code, data_period, city_code
                        ORDER BY updated_at DESC, id DESC
                    ) AS keep_id
               FROM billing_point_snapshot
         ) duplicate_ids
        WHERE duplicate_ids.id <> duplicate_ids.keep_id
   );

DELETE FROM billing_point_snapshot
 WHERE id IN (
       SELECT duplicate_ids.id
         FROM (
             SELECT id,
                    ROW_NUMBER() OVER (
                        PARTITION BY billing_point_code, data_period, city_code
                        ORDER BY updated_at DESC, id DESC
                    ) AS row_no
               FROM billing_point_snapshot
         ) duplicate_ids
        WHERE duplicate_ids.row_no > 1
   );

DELETE FROM audit_result
 WHERE id IN (
       SELECT duplicate_ids.id
         FROM (
             SELECT id,
                    ROW_NUMBER() OVER (
                        PARTITION BY billing_point_code, data_period, city_code
                        ORDER BY updated_at DESC, id DESC
                    ) AS row_no
               FROM audit_result
         ) duplicate_ids
        WHERE duplicate_ids.row_no > 1
   );

ALTER TABLE billing_point_snapshot DROP INDEX uk_bp_snapshot_business;
ALTER TABLE billing_point_snapshot
    ADD UNIQUE KEY uk_bp_snapshot_business (billing_point_code, data_period, city_code);

ALTER TABLE audit_result DROP INDEX uk_audit_business;
ALTER TABLE audit_result
    ADD UNIQUE KEY uk_audit_business (billing_point_code, data_period, city_code);

ALTER TABLE payment_detail DROP INDEX uk_payment_business;
ALTER TABLE payment_detail
    ADD UNIQUE KEY uk_payment_business (
        city_code,
        data_period,
        billing_point_code,
        payment_bill_code,
        period_start,
        period_end,
        payment_start
    );

ALTER TABLE meter_reading DROP INDEX uk_meter_business;
ALTER TABLE meter_reading
    ADD UNIQUE KEY uk_meter_business (
        city_code,
        data_period,
        billing_point_code,
        payment_bill_code,
        meter_code,
        payment_start
    );

ALTER TABLE benchmark_value DROP INDEX uk_benchmark_business;
ALTER TABLE benchmark_value
    ADD UNIQUE KEY uk_benchmark_business (
        city_code,
        data_period,
        billing_point_code,
        benchmark_year,
        benchmark_month
    );
