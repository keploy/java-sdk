package io.keploy.ksql;

import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.util.Objects;

public class KParameterMetaData implements ParameterMetaData {
    ParameterMetaData wrappedParameterMetaData;

    public KParameterMetaData(ParameterMetaData parameterMetaData) {
        wrappedParameterMetaData = parameterMetaData;
    }

    @Override
    public int getParameterCount() throws SQLException {
        if (Objects.equals(System.getenv("KEPLOY_MODE"), "test")) {
            return 0;
        }
        return wrappedParameterMetaData.getParameterCount();
    }

    @Override
    public int isNullable(int param) throws SQLException {
        if (Objects.equals(System.getenv("KEPLOY_MODE"), "test")) {
            return 0;
        }
        return wrappedParameterMetaData.isNullable(param);
    }

    @Override
    public boolean isSigned(int param) throws SQLException {
        if (Objects.equals(System.getenv("KEPLOY_MODE"), "test")) {
            return true;
        }
        return wrappedParameterMetaData.isSigned(param);
    }

    @Override
    public int getPrecision(int param) throws SQLException {
        if (Objects.equals(System.getenv("KEPLOY_MODE"), "test")) {
            return 0;
        }
        return wrappedParameterMetaData.getPrecision(param);
    }

    @Override
    public int getScale(int param) throws SQLException {
        if (Objects.equals(System.getenv("KEPLOY_MODE"), "test")) {
            return 0;
        }
        return wrappedParameterMetaData.getScale(param);
    }

    @Override
    public int getParameterType(int param) throws SQLException {
        if (Objects.equals(System.getenv("KEPLOY_MODE"), "test")) {
            return 0;
        }
        return wrappedParameterMetaData.getParameterType(param);
    }

    @Override
    public String getParameterTypeName(int param) throws SQLException {
        if (Objects.equals(System.getenv("KEPLOY_MODE"), "test")) {
            return "";
        }
        return wrappedParameterMetaData.getParameterTypeName(param);
    }

    @Override
    public String getParameterClassName(int param) throws SQLException {
        if (Objects.equals(System.getenv("KEPLOY_MODE"), "test")) {
            return "";
        }
        return wrappedParameterMetaData.getParameterClassName(param);
    }

    @Override
    public int getParameterMode(int param) throws SQLException {
        if (Objects.equals(System.getenv("KEPLOY_MODE"), "test")) {
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
