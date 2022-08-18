package com.example.ksql;

import java.sql.ParameterMetaData;
import java.sql.SQLException;

public class KParameterMetaData implements java.sql.ParameterMetaData {
    ParameterMetaData wrappedParameterMetaData;

    public KParameterMetaData(ParameterMetaData parameterMetaData) {
        wrappedParameterMetaData = parameterMetaData;
    }

    @Override
    public int getParameterCount() throws SQLException {
        return wrappedParameterMetaData.getParameterCount();
    }

    @Override
    public int isNullable(int param) throws SQLException {
        return wrappedParameterMetaData.isNullable(param);
    }

    @Override
    public boolean isSigned(int param) throws SQLException {
        return wrappedParameterMetaData.isSigned(param);
    }

    @Override
    public int getPrecision(int param) throws SQLException {
        return wrappedParameterMetaData.getPrecision(param);
    }

    @Override
    public int getScale(int param) throws SQLException {
        return wrappedParameterMetaData.getScale(param);
    }

    @Override
    public int getParameterType(int param) throws SQLException {
        return wrappedParameterMetaData.getParameterType(param);
    }

    @Override
    public String getParameterTypeName(int param) throws SQLException {
        return wrappedParameterMetaData.getParameterTypeName(param);
    }

    @Override
    public String getParameterClassName(int param) throws SQLException {
        return wrappedParameterMetaData.getParameterClassName(param);
    }

    @Override
    public int getParameterMode(int param) throws SQLException {
        return wrappedParameterMetaData.getParameterMode(param);
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return wrappedParameterMetaData.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return wrappedParameterMetaData.isWrapperFor(iface);
    }
}
