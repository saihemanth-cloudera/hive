-- HIVE-23107
ALTER TABLE COMPACTION_QUEUE ADD CQ_NEXT_TXN_ID bigint;
-- HIVE-24291
ALTER TABLE COMPACTION_QUEUE ADD CQ_TXN_ID bigint;
-- HIVE-24275
ALTER TABLE COMPACTION_QUEUE ADD CQ_COMMIT_TIME bigint;

-- These lines need to be last.  Insert any changes above.
UPDATE "APP".CDH_VERSION SET SCHEMA_VERSION='3.1.3000.7.2.8.0', VERSION_COMMENT='Hive release version 3.1.3000 for CDH 7.2.8.0' where VER_ID=1;