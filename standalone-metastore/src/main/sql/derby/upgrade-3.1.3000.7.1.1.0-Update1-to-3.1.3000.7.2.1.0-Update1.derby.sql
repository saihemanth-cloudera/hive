--HIVE-23516
CREATE TABLE "APP"."REPLICATION_METRICS" (
  "RM_SCHEDULED_EXECUTION_ID" bigint NOT NULL,
  "RM_POLICY" varchar(256) NOT NULL,
  "RM_DUMP_EXECUTION_ID" bigint NOT NULL,
  "RM_METADATA" varchar(4000),
  "RM_PROGRESS" varchar(4000),
  "RM_START_TIME" integer not null,
  PRIMARY KEY("RM_SCHEDULED_EXECUTION_ID")
);

CREATE INDEX "POLICY_IDX" ON "APP"."REPLICATION_METRICS" ("RM_POLICY");
CREATE INDEX "DUMP_IDX" ON "APP"."REPLICATION_METRICS" ("RM_DUMP_EXECUTION_ID");

-- These lines need to be last.  Insert any changes above.
UPDATE "APP".CDH_VERSION SET SCHEMA_VERSION='3.1.3000.7.2.1.0-Update1', VERSION_COMMENT='Hive release version 3.1.3000 for CDH 7.2.1.0-Update1' where VER_ID=1;