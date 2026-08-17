ALTER TABLE import_job
    ADD COLUMN scope_json LONGTEXT NULL AFTER city_code;

UPDATE import_job
   SET scope_json = '[]'
 WHERE scope_json IS NULL;

ALTER TABLE import_job
    MODIFY COLUMN scope_json LONGTEXT NOT NULL;
