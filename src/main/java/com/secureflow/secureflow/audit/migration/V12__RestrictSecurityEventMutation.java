package com.secureflow.secureflow.audit.migration;

import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.Statement;

@Component
public class V12__RestrictSecurityEventMutation extends BaseJavaMigration {

    @Override
    public MigrationVersion getVersion() {
        return MigrationVersion.fromVersion("12");
    }

    @Override
    public String getDescription() {
        return "restrict security event mutation";
    }

    @Override
    public void migrate(Context context) throws Exception {
        String database = context.getConnection().getMetaData().getDatabaseProductName();
        if (!"PostgreSQL".equals(database)) {
            return;
        }

        try (Statement statement = context.getConnection().createStatement();
             ResultSet roles = statement.executeQuery(
                     "select exists (select 1 from pg_roles where rolname = 'secureflow_app')"
             )) {
            if (!roles.next() || !roles.getBoolean(1)) {
                return;
            }
        }

        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute("revoke update, delete on table security_events from secureflow_app");
            statement.execute("grant select, insert on table security_events to secureflow_app");
        }
    }
}
