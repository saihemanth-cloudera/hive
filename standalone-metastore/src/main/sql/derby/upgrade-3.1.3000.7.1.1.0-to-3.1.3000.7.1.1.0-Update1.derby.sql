-- HIVE-22995
ALTER TABLE "APP"."DBS" ADD COLUMN "DB_MANAGED_LOCATION_URI" VARCHAR(4000);

-- These lines need to be last.  Insert any changes above.
UPDATE "APP".CDH_VERSION SET SCHEMA_VERSION='3.1.3000.7.1.1.0-Update1', VERSION_COMMENT='Hive release version 3.1.3000 for CDH 7.1.1.0-Update1' where VER_ID=1;