package io.keploy.utils;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.security.AnyTypePermission;
import io.keploy.grpc.stubs.Service;
import io.keploy.regression.Mock;
import io.keploy.regression.context.Context;
import io.keploy.regression.context.Kcontext;
import lombok.NoArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@NoArgsConstructor
public class ProcessD {

    private static final Logger logger = LogManager.getLogger(ProcessD.class);
    //    public static ArrayList<Byte> binResult;
    public static byte[] binResult;
    public static XStream xstream = new XStream();
    private static AtomicInteger c = new AtomicInteger();

    @SafeVarargs
    public static <T> depsobj ProcessDep(Map<String, String> meta, T... outputs) throws InvalidProtocolBufferException {

        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            logger.error("dependency mocking failed: failed to get Keploy context");
            return new depsobj<String>(false, null);
        }
        List<Service.Dependency> deps = kctx.getDeps();
        switch (kctx.getMode()) {
            case MODE_TEST:
                if (kctx.getMock().size() == 0) {
                    if (deps == null || deps.size() == 0) {
                        logger.error("dependency mocking failed: incorrect number of dependencies in keploy context with test id: " + kctx.getTestId());
                        return new depsobj<>(false, null);
                    }
                    if (deps.get(0).getDataList().size() != outputs.length) {
                        logger.error("dependency mocking failed: incorrect number of dependencies in keploy context with test id: " + kctx.getTestId());
                        return new depsobj<>(false, null);
                    }
                    List<Object> res = new ArrayList<>();

                    for (T output : outputs) {
                        List<Service.DataBytes> bin = deps.get(0).getDataList();
                        Service.DataBytes c = bin.get(0);
                        binResult = c.getBin().toByteArray();
//                    T obj = decode(binResult, output);
                        String objectClass = output.getClass().getName();
                        Object obj = null;
                        switch (objectClass) {
                            case "io.keploy.ksql.KPreparedStatement":
                                obj = decodePreparedStatement(binResult);
                                break;
                            case "org.postgresql.jdbc.PgResultSet":
                            case "io.keploy.ksql.KResultSet":
                                obj = decodeResultSet(binResult);
                                break;
                            case "io.keploy.ksql.KConnection":
                                obj = decodeConnection(binResult);
                                break;
                            case "java.lang.Integer":
                                obj = decodeInt(binResult);
                                break;
                            case "java.lang.Boolean":
                                obj = decodeBoolean(binResult);
                                break;
                            default:
                        }

                        if (obj == null) {
                            logger.error("dependency mocking failed: failed to decode object for testID : {}", kctx.getTestId());
                            return new depsobj<>(false, null);
                        }
                        res.add(obj);
                    }
                    kctx.getDeps().remove(0);
                    return new depsobj<>(true, res);
                }
                List<Service.Mock> mocks = kctx.getMock();

                if (mocks == null || mocks.size() == 0) {
                    logger.error("mocking failed: incorrect number of mocks in keploy context with test id: " + kctx.getTestId());
                    return new depsobj<>(false, null);
                }
                if (mocks.get(0).getSpec().getObjectsCount() != outputs.length) {
                    logger.error("mocking failed: incorrect number of mocks in keploy context with test id: " + kctx.getTestId());
                    return new depsobj<>(false, null);
                }

                List<Object> res = new ArrayList<>();

                for (T output : outputs) {
                    List<Service.Mock.Object> bin = mocks.get(0).getSpec().getObjectsList();
                    Service.Mock.Object c = bin.get(0);
                    binResult = c.toByteArray();
//                    System.out.println(binResult.length+" BINRESULT 1 !!" + Arrays.toString(binResult));
//                    T obj = decode(binResult, output);
                    String objectClass = output.getClass().getName();
                    Object obj = null;
                    switch (objectClass) {
                        case "io.keploy.ksql.KPreparedStatement":
                            obj = decodePreparedStatement(binResult);
                            break;
                        case "org.postgresql.jdbc.PgResultSet":
                        case "io.keploy.ksql.KResultSet":
                        case "com.mysql.cj.jdbc.MySqlResultSet":
                        case "oracle.jdbc.driver.ForwardOnlyResultSet":
                            byte[] bit = new byte[binResult.length - 4];
                            System.arraycopy(binResult, 4, bit, 0, bit.length);
                            obj = decodeResultSet(bit);
                            break;
                        case "io.keploy.ksql.KConnection":
                            obj = decodeConnection(binResult);
                            break;
                        case "java.lang.Integer":
                            obj = decodeInt(binResult);
                            break;
                        case "java.lang.Boolean":
                            obj = decodeBoolean(binResult);
                            break;
                        default:
                    }

                    if (obj == null) {
                        logger.error("dependency mocking failed: failed to decode object for testID : {}", kctx.getTestId());
                        return new depsobj<>(false, null);
                    }
                    res.add(obj);
                }

                kctx.getMock().remove(0);
                return new depsobj<>(true, res);

            case MODE_RECORD:
                Service.Dependency.Builder Dependencies = Service.Dependency.newBuilder();
                List<Service.DataBytes> dblist = new ArrayList<>(); //this is 2d array
//                Map<String, String> meta = new HashMap<>();
                for (T output : outputs) {
//                    binResult = encoded(output);
                    String objectClass = output.getClass().getName();

                    switch (objectClass) {
                        case "org.postgresql.jdbc.PgPreparedStatement":
                        case "io.keploy.ksql.KPreparedStatement":
                            binResult = encodedPreparedStatement((PreparedStatement) output);
                            break;
                        case "org.postgresql.jdbc.PgResultSet":
                        case "io.keploy.ksql.KResultSet":
                        case "oracle.jdbc.driver.ForwardOnlyResultSet":
                            binResult = encodedResultSet((ResultSet) output);
                            break;
                        case "org.postgresql.jdbc.PgConnection":
                        case "io.keploy.ksql.KConnection":
                            binResult = encodedConnection((Connection) output);
                            break;
                        case "java.lang.Integer":
                            binResult = encodedInt((Integer) output);
                            break;
                        case "java.lang.Boolean":
                            binResult = encodedBoolean((Boolean) output);
                            break;
                        default:

                    }
                    meta = getMeta(output);
                    if (binResult == null) {
                        logger.error("dependency capture failed: failed to encode object test id : {}", kctx.getTestId());
                        return new depsobj<>(false, null);
                    }
                    Service.DataBytes dbytes = Service.DataBytes.newBuilder().setBin(ByteString.copyFrom(binResult)).build();
                    dblist.add(dbytes);
                }

                Service.Dependency genericDeps = Dependencies.addAllData(dblist).setName(meta.get("name")).setType(meta.get("type")).putAllMeta(meta).build();

                kctx.getDeps().add(genericDeps);

                List<Service.Mock.Object> lobj = new ArrayList<>();

                Service.Mock.Object.newBuilder().setType("").build();

                for (Service.DataBytes s : dblist) {
//                    System.out.println(Arrays.toString(s.getBin().toByteArray()));
                    Service.Mock.Object obj = Service.Mock.Object.newBuilder().setData(s.getBin()).build();
                    lobj.add(obj);
                }

                Service.Mock.SpecSchema specSchema = Service.Mock.SpecSchema.newBuilder().putAllMetadata(meta).addAllObjects(lobj).build();

                Service.Mock mock = Service.Mock.newBuilder()
                        .setVersion(Mock.Version.V1_BETA1.value)
                        .setName("")
                        .setKind(Mock.Kind.GENERIC_EXPORT.value)
                        .setSpec(specSchema)
                        .build();

                kctx.getMock().add(mock);

        }
        return new depsobj<>(false, null);
    }

    public static <T> byte[] encoded(T output) {

        XStream xstream = new XStream();
        xstream.alias("Generic", output.getClass());
        xstream.addPermission(AnyTypePermission.ANY);
        xstream.ignoreUnknownElements();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        OutputStreamWriter writer = new OutputStreamWriter(outputStream);
        xstream.toXML(output, writer);
        return outputStream.toByteArray();
    }

    public static <T> T decode(byte[] bin) {

        ByteArrayInputStream input = new ByteArrayInputStream(bin);
        XStream xstream = new XStream();
        xstream.addPermission(AnyTypePermission.ANY);

        T object = null;
        try {
            object = (T) xstream.fromXML(input);
            input.close();

        } catch (Exception e) {
            System.out.println("Exception while decoding ..... " + e);
        }
        return object;
    }

    public static <T> Map<String, String> getMeta(T obj) {
        Map<String, String> meta = new HashMap<>();
        meta.put("name", "SQL");
        meta.put("type", "SQL_DB");
        meta.put("object", obj.getClass().getName());
        return meta;
    }

    public static PreparedStatement decodePreparedStatement(byte[] bin) {

        ByteArrayInputStream input = new ByteArrayInputStream(bin);
        XStream xstream = new XStream();
        xstream.addPermission(AnyTypePermission.ANY);
        PreparedStatement object = null;
        try {
            object = (PreparedStatement) xstream.fromXML(input);
            input.close();

        } catch (Exception e) {
            System.out.println("Exception while decoding ..... " + e);
        }
        return object;
    }

    public static byte[] encodedPreparedStatement(PreparedStatement output) {
        XStream xstream = new XStream();
        xstream.alias("PreparedStatement", PreparedStatement.class);
        xstream.addPermission(AnyTypePermission.ANY);
        xstream.ignoreUnknownElements();
        String temp = xstream.toXML(output);
//        System.out.println(temp);
        int x = c.incrementAndGet();
        try (PrintWriter out = new PrintWriter("pp" + x + ".txt")) {
            out.println(temp);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        OutputStreamWriter writer = new OutputStreamWriter(outputStream);
        xstream.toXML(output, writer);
        return outputStream.toByteArray();
    }

    public static Connection decodeConnection(byte[] bin) {

        ByteArrayInputStream input = new ByteArrayInputStream(bin);
        XStream xstream = new XStream();
        xstream.addPermission(AnyTypePermission.ANY);
        Connection object = null;
        try {
            object = (Connection) xstream.fromXML(input);
            input.close();

        } catch (Exception e) {
            System.out.println("Exception while decoding ..... " + e);
        }

        return object;
    }

    public static byte[] encodedConnection(Connection output) {
        XStream xstream = new XStream();
        xstream.alias("Connection", Connection.class);
        xstream.addPermission(AnyTypePermission.ANY);
        xstream.ignoreUnknownElements();
        String temp = xstream.toXML(output);
        int x = c.incrementAndGet();
        try (PrintWriter out = new PrintWriter("cc" + x + ".txt")) {
            out.println(temp);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        OutputStreamWriter writer = new OutputStreamWriter(outputStream);
        xstream.toXML(output, writer);
        return outputStream.toByteArray();
    }

    public static byte[] encodedResultSet(ResultSet output) {
        XStream xstream = new XStream();
        xstream.alias("ResultSet", ResultSet.class);
        xstream.addPermission(AnyTypePermission.ANY);
        xstream.ignoreUnknownElements();
        String temp = xstream.toXML(output);
        int x = c.incrementAndGet();

//        try (PrintWriter out = new PrintWriter("rs" + x + ".txt")) {
//            out.println(temp);
//        } catch (FileNotFoundException e) {
//            throw new RuntimeException(e);
//        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        OutputStreamWriter writer = new OutputStreamWriter(outputStream);
        xstream.toXML(output, writer);
//        System.out.println(outputStream.toByteArray().length);

        return outputStream.toByteArray();
    }

    public static ResultSet decodeResultSet(byte[] bin) {
//        System.out.println(bin.length+" BIN 2 !!" + Arrays.toString(bin));

        ByteArrayInputStream input = new ByteArrayInputStream(bin);
        XStream xstream = new XStream();
        xstream.addPermission(AnyTypePermission.ANY);
        ResultSet object = null;
        try {
            object = (ResultSet) xstream.fromXML(input);
            input.close();

        } catch (Exception e) {
            System.out.println("Exception while decoding ..... " + e);
        }
        return object;
    }

    public static byte[] encodedInt(int output) {
        xstream.alias("int", Integer.class);
        xstream.addPermission(AnyTypePermission.ANY);
        xstream.ignoreUnknownElements();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        OutputStreamWriter writer = new OutputStreamWriter(outputStream);
        xstream.toXML(output, writer);
        return outputStream.toByteArray();
    }

    public static int decodeInt(byte[] bin) {

        ByteArrayInputStream input = new ByteArrayInputStream(bin);
        XStream xstream = new XStream();
        xstream.addPermission(AnyTypePermission.ANY);
        int object = 0;
        try {
            object = (int) xstream.fromXML(input);
            input.close();

        } catch (Exception e) {
            System.out.println("Exception while decoding ..... " + e);
        }
        return object;
    }

    public static byte[] encodedBoolean(boolean output) {
        xstream.alias("boolean", Boolean.class);
        xstream.addPermission(AnyTypePermission.ANY);
        xstream.ignoreUnknownElements();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        OutputStreamWriter writer = new OutputStreamWriter(outputStream);
        xstream.toXML(output, writer);
        return outputStream.toByteArray();
    }

    public static boolean decodeBoolean(byte[] bin) {

        ByteArrayInputStream input = new ByteArrayInputStream(bin);
        XStream xstream = new XStream();
        xstream.addPermission(AnyTypePermission.ANY);
        boolean object = false;
        try {
            object = (boolean) xstream.fromXML(input);
            input.close();

        } catch (Exception e) {
            System.out.println("Exception while decoding ..... " + e);
        }
        return object;
    }

    public static String readFile(String path, Charset encoding)
            throws IOException {
        byte[] encoded = Files.readAllBytes(Paths.get(path));
        return new String(encoded, encoding);
    }
}