package io.keploy.ksql;

import org.apache.logging.log4j.LogManager;

import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.util.Objects;

import static io.keploy.ksql.KDriver.mode;
import static io.keploy.ksql.KDriver.testMode;

public class KParameterMetaData implements ParameterMetaData {
    ParameterMetaData wrappedParameterMetaData;
    private static final org.apache.logging.log4j.Logger logger = LogManager.getLogger(ParameterMetaData.class);
    public KParameterMetaData(ParameterMetaData parameterMetaData) {
        logger.debug("Inside KParameterMetaData !!");
        wrappedParameterMetaData = parameterMetaData;
    }

    @Override
    public int getParameterCount() throws SQLException {
        if (mode == testMode) {
            return 0;
        }
        return wrappedParameterMetaData.getParameterCount();
    }

    @Override
    public int isNullable(int param) throws SQLException {
        if (mode == testMode) {
            return 0;
        }
        return wrappedParameterMetaData.isNullable(param);
    }

    @Override
    public boolean isSigned(int param) throws SQLException {
        if (mode == testMode) {
            return true;
        }
        return wrappedParameterMetaData.isSigned(param);
    }

    @Override
    public int getPrecision(int param) throws SQLException {
        if (mode == testMode) {
            return 0;
        }
        return wrappedParameterMetaData.getPrecision(param);
    }

    @Override
    public int getScale(int param) throws SQLException {
        if (mode == testMode) {
            return 0;
        }
        return wrappedParameterMetaData.getScale(param);
    }

    @Override
    public int getParameterType(int param) throws SQLException {
        if (mode == testMode) {
            return 0;
        }
        return wrappedParameterMetaData.getParameterType(param);
    }

    @Override
    public String getParameterTypeName(int param) throws SQLException {
        if (mode == testMode) {
            return "";
        }
        return wrappedParameterMetaData.getParameterTypeName(param);
    }

    @Override
    public String getParameterClassName(int param) throws SQLException {
        if (mode == testMode) {
            return "";
        }
        return wrappedParameterMetaData.getParameterClassName(param);
    }

    @Override
    public int getParameterMode(int param) throws SQLException {
        if (mode == testMode) {
            return 0;
        }
        return wrappedParameterMetaData.getParameterMode(param);
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (Objects.equals(System.getenv("KEPLOY_MODE"), "test")) {
            return null;
        }
        return wrappedParameterMetaData.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        if (Objects.equals(System.getenv("KEPLOY_MODE"), "test")) {
            return true;
        }
        return wrappedParameterMetaData.isWrapperFor(iface);
    }
}
