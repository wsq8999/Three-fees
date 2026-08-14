ALTER TABLE billing_point_master DROP PRIMARY KEY;

ALTER TABLE billing_point_master
    ADD PRIMARY KEY (city_code, billing_point_code);
