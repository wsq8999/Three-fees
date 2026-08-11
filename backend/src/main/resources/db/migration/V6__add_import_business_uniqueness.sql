ALTER TABLE imported_record
    ADD CONSTRAINT uk_imported_record__batch_business_key UNIQUE (batch_id, business_key);
