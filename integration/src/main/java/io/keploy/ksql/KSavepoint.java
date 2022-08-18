package com.example.ksql;

import java.sql.SQLException;
import java.sql.Savepoint;

public class KSavepoint implements java.sql.Savepoint {

    Savepoint wrappedSavepoint;

    public KSavepoint(Savepoint setSavepoint) {
        wrappedSavepoint = setSavepoint;
    }

    @Override
    public int getSavepointId() throws SQLException {
        return wrappedSavepoint.getSavepointId();
    }

    @Override
    public String getSavepointName() throws SQLException {
        return wrappedSavepoint.getSavepointName();
    }
}
