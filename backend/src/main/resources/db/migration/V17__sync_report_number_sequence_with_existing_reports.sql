INSERT INTO report_number_sequence (business_month, next_value, version)
SELECT numbered.business_month, numbered.max_value + 1, 0
  FROM (
    SELECT SUBSTRING(report_number, 4, 6) AS business_month,
           MAX(SUBSTRING(report_number, 12, 6) + 0) AS max_value
      FROM audit_report
     WHERE report_number REGEXP '^BG-[0-9]{6}-[0-9]{6}$'
     GROUP BY SUBSTRING(report_number, 4, 6)
  ) numbered
ON DUPLICATE KEY UPDATE
    next_value = GREATEST(next_value, VALUES(next_value)),
    version = version + 1;
