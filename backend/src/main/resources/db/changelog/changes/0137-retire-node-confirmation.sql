--liquibase formatted sql

--changeset rvport:0137-retire-node-confirmation
DROP TABLE migration_input_refs;
DROP TABLE migration_input_sets;
