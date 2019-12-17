CREATE TABLE "APP"."SCHEDULED_QUERIES" (
  "SCHEDULED_QUERY_ID" bigint primary key not null,
  "SCHEDULE_NAME" varchar(256) not null,
  "ENABLED" CHAR(1) NOT NULL DEFAULT 'N',
  "CLUSTER_NAMESPACE" varchar(256) not null,
  "USER" varchar(128) not null,
  "SCHEDULE" varchar(256) not null,
  "QUERY" varchar(4000) not null,
  "NEXT_EXECUTION" integer not null
);

CREATE INDEX NEXTEXECUTIONINDEX ON APP.SCHEDULED_QUERIES (ENABLED,CLUSTER_NAMESPACE,NEXT_EXECUTION);
CREATE UNIQUE INDEX UNIQUE_SCHEDULED_QUERIES_NAME ON APP.SCHEDULED_QUERIES (SCHEDULE_NAME,CLUSTER_NAMESPACE);

CREATE TABLE APP.SCHEDULED_EXECUTIONS (
        SCHEDULED_EXECUTION_ID bigint primary key not null,
        EXECUTOR_QUERY_ID VARCHAR(256),
        SCHEDULED_QUERY_ID bigint not null,
        START_TIME integer not null,
        END_TIME INTEGER,
        LAST_UPDATE_TIME INTEGER,
        ERROR_MESSAGE VARCHAR(2000),
        STATE VARCHAR(256),
        CONSTRAINT SCHEDULED_EXECUTIONS_SCHQ_FK FOREIGN KEY (SCHEDULED_QUERY_ID) REFERENCES APP.SCHEDULED_QUERIES(SCHEDULED_QUERY_ID) ON DELETE CASCADE
);

CREATE INDEX LASTUPDATETIMEINDEX ON APP.SCHEDULED_EXECUTIONS (LAST_UPDATE_TIME);
CREATE INDEX SCHEDULED_EXECUTIONS_SCHQID ON APP.SCHEDULED_EXECUTIONS (SCHEDULED_QUERY_ID);
CREATE UNIQUE INDEX SCHEDULED_EXECUTIONS_UNIQUE_ID ON APP.SCHEDULED_EXECUTIONS (SCHEDULED_EXECUTION_ID);

-- These lines need to be last.  Insert any changes above.
UPDATE "APP".VERSION SET SCHEMA_VERSION='3.1.3000', VERSION_COMMENT='Hive release version 3.1.3000' where VER_ID=1;