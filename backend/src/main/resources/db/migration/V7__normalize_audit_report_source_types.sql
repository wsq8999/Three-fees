UPDATE audit_report SET source_type = 'GENERATED' WHERE source_type = 'SYSTEM';
UPDATE audit_report SET source_type = 'IMPORTED' WHERE source_type = 'HISTORICAL';
