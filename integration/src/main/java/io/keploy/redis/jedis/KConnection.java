package io.keploy.redis.jedis;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.keploy.grpc.stubs.Service;
import io.keploy.regression.KeployInstance;
import io.keploy.regression.Mock;
import io.keploy.regression.Mode;
import io.keploy.regression.context.Context;
import io.keploy.regression.context.Kcontext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Connection;
import redis.clients.jedis.Protocol;
import redis.clients.util.SafeEncoder;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocketFactory;
import java.lang.reflect.Type;
import java.net.Socket;
import java.util.*;

/**
 * KConnection is a class which extends Connection class and wraps it. KConnection records data in record mode and sends
 * data in test mode.
 */
public class KConnection extends Connection {

    static final Logger logger = LoggerFactory.getLogger(KConnection.class);
    private final Mode.ModeType keployMode = Context.getCtx().getMode().getModeFromContext();
    private Map<String, String> meta = new HashMap<String, String>() {
        {
            put("name", "redis");
            put("type", "NoSqlDB");
        }
    };
    private static final RedisCustomSerializer redisCustomSerializer = new RedisCustomSerializer();
    private static final Gson gson = new Gson();
    private static final String CROSS = new String(Character.toChars(0x274C));
    private static final byte[][] EMPTY_ARGS = new byte[0][];

    public KConnection() {
        super();
        // fill data in Mock object into meta if application is in test mode.
        if (keployMode == Mode.ModeType.MODE_TEST) {
            fillMock();
        }
    }

    public KConnection(String host) {
        super(host);
        // fill data in Mock object into meta if application is in test mode.
        if (keployMode == Mode.ModeType.MODE_TEST) {
            fillMock();
        }
    }

    public KConnection(String host, int port) {
        super(host, port);
        // fill data in Mock object into meta if application is in test mode.
        if (keployMode == Mode.ModeType.MODE_TEST) {
            fillMock();
        }
    }

    public KConnection(String host, int port, boolean ssl) {
        super(host, port, ssl);
        // fill data in Mock object into meta if application is in test mode.
        if (keployMode == Mode.ModeType.MODE_TEST) {
            fillMock();
        }
    }

    public KConnection(String host, int port, boolean ssl, SSLSocketFactory sslSocketFactory, SSLParameters sslParameters, HostnameVerifier hostnameVerifier) {
        super(host, port, ssl, sslSocketFactory, sslParameters, hostnameVerifier);
        // fill data in Mock object into meta if application is in test mode.
        if (keployMode == Mode.ModeType.MODE_TEST) {
            fillMock();
        }
    }

    @Override
    public Socket getSocket() {
        return super.getSocket();
    }

    @Override
    public int getConnectionTimeout() {
        return super.getConnectionTimeout();
    }

    @Override
    public int getSoTimeout() {
        return super.getSoTimeout();
    }

    @Override
    public void setConnectionTimeout(int connectionTimeout) {
        super.setConnectionTimeout(connectionTimeout);
    }

    @Override
    public void setSoTimeout(int soTimeout) {
        super.setSoTimeout(soTimeout);
    }

    @Override
    public void setTimeoutInfinite() {
        super.setTimeoutInfinite();
    }

    @Override
    public void rollbackTimeout() {
        super.rollbackTimeout();
    }

    @Override
    protected Connection sendCommand(Protocol.Command cmd, String... args) {
        switch (keployMode) {
            case MODE_RECORD:
                /*
                  if the request has reached this function it means that request did not send byte data instead request
                  sent objects. So Redis uses its default serializer to serialize the data.
                 */
                meta.put("serializer", SerializationType.REDIS_SERIALIZATION.toString());
                // capturing request data
                meta.put("command", cmd.toString());
                int argCount = 1;
                for (String arg : args) {
                    meta.put("arg".concat(Integer.toString(argCount)), arg);
                    argCount++;
                }
                return super.sendCommand(cmd, args);
            case MODE_TEST:
                /*
                  Implementing super class logic and calling function of this class. So the flow doesn't divert
                  completely to Connection class.
                 */
                byte[][] bargs = new byte[args.length][];
                for (int i = 0; i < args.length; ++i) {
                    bargs[i] = SafeEncoder.encode(args[i]);
                }
                return this.sendCommand(cmd, bargs);
            default:
                return super.sendCommand(cmd, args);
        }
    }

    @Override
    protected Connection sendCommand(Protocol.Command cmd) {
        /*
          Implementing super class logic and calling function of this class. So the flow doesn't divert
          completely to Connection class.
         */
        return this.sendCommand(cmd, EMPTY_ARGS);
    }

    @Override
    protected Connection sendCommand(Protocol.Command cmd, byte[]... args) {
        switch (keployMode) {
            case MODE_RECORD:
                /*
                  Checking if serializer is already set if not that means request sent bytes data i.e. before reaching
                  redis client serialization is done. As REDIS_CUSTOM_SERIALIZATION is the most used serializer using this
                  serializer.
                 */
                if (!meta.containsKey("serializer") || !Objects.equals(meta.get("serializer"), SerializationType.REDIS_SERIALIZATION.toString())) {
                    meta.put("serializer", SerializationType.REDIS_CUSTOM_SERIALIZATION.toString());
                    // capturing data
                    meta.put("command", cmd.toString());
                    int argCount = 1;
                    for (byte[] arg : args) {
                        Object deserializedObject = redisCustomSerializer.deserialize(arg);
                        meta.put("arg".concat(Integer.toString(argCount)), gson.toJson(deserializedObject));
                        argCount++;
                    }
                }
                return super.sendCommand(cmd, args);
            case MODE_TEST:
                /*
                  Returning this class instead of Connection
                 */
                return this;
            default:
                return super.sendCommand(cmd, args);
        }
    }

    @Override
    public String getHost() {
        return super.getHost();
    }

    @Override
    public void setHost(String host) {
        super.setHost(host);
    }

    @Override
    public int getPort() {
        return super.getPort();
    }

    @Override
    public void setPort(int port) {
        super.setPort(port);
    }

    @Override
    public void connect() {
        switch (keployMode) {
            case MODE_TEST:
                // does nothing
                break;
            default:
                super.connect();
        }
    }

    @Override
    public void close() {
        this.disconnect();
    }

    @Override
    public void disconnect() {
        switch (keployMode) {
            case MODE_TEST:
                break;
            // does nothing
            default:
                super.disconnect();
        }
    }

    @Override
    public boolean isConnected() {
        return super.isConnected();
    }

    @Override
    public String getStatusCodeReply() {
        switch (keployMode) {
            case MODE_RECORD:
                // capturing data
                String statusCodeReply = super.getStatusCodeReply();
                meta.put("response", statusCodeReply);
                sendToServer();
                return statusCodeReply;
            case MODE_TEST:
                // returning recorded data
                return meta.get("response");
            default:
                return super.getStatusCodeReply();
        }
    }

    @Override
    public String getBulkReply() {
        switch (keployMode) {
            case MODE_RECORD:
                // capturing data
                String bulkReply = super.getBulkReply();
                meta.put("response", bulkReply);
                sendToServer();
                return bulkReply;
            case MODE_TEST:
                // returning recorded data
                return meta.get("response");
            default:
                return super.getBulkReply();
        }
    }

    @Override
    public byte[] getBinaryBulkReply() {
        switch (keployMode) {
            case MODE_RECORD:
                /*
                  Checking if serializer is already set if not that means request sent bytes data i.e. before reaching
                  redis client serialization is done. As REDIS_CUSTOM_SERIALIZATION is the most used serializer using this
                  serializer.
                 */
                if (Objects.equals(meta.get("serializer"), SerializationType.REDIS_SERIALIZATION.toString())) {
                    return super.getBinaryBulkReply();
                } else {
                    // capturing data
                    byte[] binaryBulkReply = super.getBinaryBulkReply();
                    Object deserializedObject = redisCustomSerializer.deserialize(binaryBulkReply);
                    meta.put("response", gson.toJson(deserializedObject));
                    sendToServer();
                    return binaryBulkReply;
                }
            case MODE_TEST:
                // returning recorded data based on serializer
                if (!Objects.equals(meta.get("serializer"), SerializationType.REDIS_SERIALIZATION.toString())) {
                    return redisCustomSerializer.serialize(gson.fromJson(meta.get("response"), Object.class));
                }
                return super.getBinaryBulkReply();
            default:
                return super.getBinaryBulkReply();
        }
    }

    @Override
    public Long getIntegerReply() {
        switch (keployMode) {
            case MODE_RECORD:
                // recording data
                Long integerReply = super.getIntegerReply();
                meta.put("response", integerReply.toString());
                sendToServer();
                return integerReply;
            case MODE_TEST:
                // sending recorded data
                return Long.parseLong(meta.get("response"));
            default:
                return super.getIntegerReply();
        }
    }

    @Override
    public List<String> getMultiBulkReply() {
        switch (keployMode) {
            case MODE_RECORD:
                // recording data
                List<String> multiBulkReply = super.getMultiBulkReply();
                meta.put("response", multiBulkReply.toString());
                sendToServer();
                return multiBulkReply;
            case MODE_TEST:
                // sending recorded data
                return new ArrayList<String>(Arrays.asList(meta.get("response").split(",")));
            default:
                return super.getMultiBulkReply();
        }
    }

    @Override
    public List<byte[]> getBinaryMultiBulkReply() {
        switch (keployMode) {
            case MODE_RECORD:
                /*
                  Checking if serializer is already set if not that means request sent bytes data i.e. before reaching
                  redis client serialization is done. As REDIS_CUSTOM_SERIALIZATION is the most used serializer ,using this
                  serializer.
                 */
                if (Objects.equals(meta.get("serializer"), SerializationType.REDIS_SERIALIZATION.toString())) {
                    return super.getBinaryMultiBulkReply();
                } else {
                    // recording data
                    List<byte[]> binaryMultiBulkReply = super.getBinaryMultiBulkReply();
                    List<Object> response = new ArrayList<>();
                    for (byte[] i : binaryMultiBulkReply) {
                        Object deserializedObject = redisCustomSerializer.deserialize(i);
                        response.add(deserializedObject);
                    }
                    meta.put("response", gson.toJson(response));
                    sendToServer();
                    return binaryMultiBulkReply;
                }
            case MODE_TEST:
                // sending recorded data
                List<byte[]> response = new ArrayList<>();
                Type listOfObject = new TypeToken<List<Object>>() {
                }.getType();
                List<Object> lObj = gson.fromJson(meta.get("response"), listOfObject);
                for (Object i : lObj) {
                    response.add(redisCustomSerializer.serialize(i));
                }
                return response;
            default:
                return super.getBinaryMultiBulkReply();
        }
    }

    @Override
    public void resetPipelinedCount() {
        super.resetPipelinedCount();
    }

    @Override
    public List<Object> getRawObjectMultiBulkReply() {
        switch (keployMode) {
            case MODE_RECORD:
                // recording data
                List<Object> rawObjectMultiBulkReply = super.getRawObjectMultiBulkReply();
                meta.put("response", gson.toJson(rawObjectMultiBulkReply));
                sendToServer();
                return rawObjectMultiBulkReply;
            case MODE_TEST:
                // sending recorded data
                Type listOfObject = new TypeToken<List<Object>>() {
                }.getType();
                return gson.fromJson(meta.get("response"), listOfObject);
            default:
                return super.getRawObjectMultiBulkReply();
        }
    }

    @Override
    public List<Object> getObjectMultiBulkReply() {
        switch (keployMode) {
            case MODE_RECORD:
                // recording data
                List<Object> objectMultiBulkReply = super.getObjectMultiBulkReply();
                meta.put("response", gson.toJson(objectMultiBulkReply));
                sendToServer();
                return objectMultiBulkReply;
            case MODE_TEST:
                // sending recorded data
                Type listOfObject = new TypeToken<List<Object>>() {
                }.getType();
                return gson.fromJson(meta.get("response"), listOfObject);
            default:
                return super.getObjectMultiBulkReply();
        }
    }

    @Override
    public List<Long> getIntegerMultiBulkReply() {
        switch (keployMode) {
            case MODE_RECORD:
                // recording data
                List<Long> integerMultiBulkReply = super.getIntegerMultiBulkReply();
                meta.put("response", gson.toJson(integerMultiBulkReply));
                sendToServer();
                return integerMultiBulkReply;
            case MODE_TEST:
                // sending recorded data
                Type listOfLong = new TypeToken<List<Long>>() {
                }.getType();
                return gson.fromJson(meta.get("response"), listOfLong);
            default:
                return super.getIntegerMultiBulkReply();
        }
    }

    @Override
    public List<Object> getAll() {
        switch (keployMode) {
            case MODE_RECORD:
                // recording data
                List<Object> getAll = super.getAll();
                meta.put("response", gson.toJson(getAll));
                sendToServer();
                return getAll;
            case MODE_TEST:
                // sending recorded data
                Type listOfObject = new TypeToken<List<Object>>() {
                }.getType();
                return gson.fromJson(meta.get("response"), listOfObject);
            default:
                return super.getAll();
        }
    }

    @Override
    public List<Object> getAll(int except) {
        switch (keployMode) {
            case MODE_RECORD:
                // recording data
                List<Object> getAll = super.getAll(except);
                meta.put("response", gson.toJson(getAll));
                sendToServer();
                return getAll;
            case MODE_TEST:
                // sending recorded data
                Type listOfObject = new TypeToken<List<Object>>() {
                }.getType();
                return gson.fromJson(meta.get("response"), listOfObject);
            default:
                return super.getAll(except);
        }
    }

    @Override
    public Object getOne() {
        switch (keployMode) {
            case MODE_RECORD:
                // recording data
                Object getOne = super.getOne();
                meta.put("response", gson.toJson(getOne));
                sendToServer();
                return getOne;
            case MODE_TEST:
                // sending recorded data
                return gson.fromJson(meta.get("response"), Object.class);
            default:
                return super.getOne();
        }
    }

    @Override
    public boolean isBroken() {
        return super.isBroken();
    }

    @Override
    protected void flush() {
        super.flush();
    }

    @Override
    protected Object readProtocolWithCheckingBroken() {
        return super.readProtocolWithCheckingBroken();
    }

    // method to send data to server
    private void sendToServer() {
        Kcontext kctx = Context.getCtx();
        logger.debug("meta:{}", meta.toString());
        if (Objects.equals(meta.get("command"), Protocol.Command.PING.toString()) ||
                Objects.equals(meta.get("command"), Protocol.Command.QUIT.toString())) {
            return;
        }
        Service.Mock.SpecSchema specSchema = Service.Mock.SpecSchema.newBuilder()
                .putAllMetadata(meta)
                .build();
        Service.Mock redisMock = Service.Mock.newBuilder()
                .setVersion(Mock.Version.V1_BETA1.value)
                .setKind(Mock.Kind.GENERIC_EXPORT.value)
                .setSpec(specSchema)
                .build();
        kctx.getMock().add(redisMock);
    }

    // method to fill meta with the mock
    private void fillMock() {
        Kcontext kctx = Context.getCtx();
        if (kctx.getMock().size() > 0 && kctx.getMock().get(0).getKind().equals(Mock.Kind.GENERIC_EXPORT.value)) {
            List<Service.Mock> mocks = kctx.getMock();
            meta = mocks.get(0).getSpec().getMetadataMap();
            mocks.remove(0);
        } else {
            logger.error(CROSS + " mocks not present in " + KeployInstance.getInstance().getKeploy().getCfg().getApp().getMockPath() + " directory.");
            throw new RuntimeException("unable to read mocks from keploy context");
        }
    }

    public enum SerializationType {
        REDIS_SERIALIZATION,
        REDIS_CUSTOM_SERIALIZATION;

        SerializationType() {

        }
    }
}
