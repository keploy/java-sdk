package io.keploy.ksql;

import org.apache.logging.log4j.LogManager;

import java.sql.SQLException;
import java.sql.Savepoint;

import static io.keploy.ksql.KResultSet.msg1;
import static io.keploy.ksql.KResultSet.msg2;

public class KSavepoint implements Savepoint {

    Savepoint wrappedSavepoint;
    private static final org.apache.logging.log4j.Logger logger = LogManager.getLogger(KSavepoint.class);
    public KSavepoint(Savepoint setSavepoint) {
        wrappedSavepoint = setSavepoint;
    }

    @Override
    public int getSavepointId() throws SQLException {
        logger.warn("{} int getSavepointId() throws SQLException {}", msg1, msg2);
        return wrappedSavepoint.getSavepointId();
    }

    @Override
    public String getSavepointName() throws SQLException {
        logger.warn("{} String getSavepointName() throws SQLException {}", msg1, msg2);
        return wrappedSavepoint.getSavepointName();
    }
}
