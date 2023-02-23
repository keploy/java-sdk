package io.keploy.redis.jedis;

import lombok.NoArgsConstructor;

import java.io.*;

@NoArgsConstructor
public class RedisCustomSerializer<T> {

    public byte[] serialize(T obj) {
        try {
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            ObjectOutputStream objectStream = new ObjectOutputStream(byteStream);
            objectStream.writeObject(obj);
            objectStream.flush();
            return byteStream.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Cannot serialize", e);
        }
    }

    public T deserialize(byte[] bytes) {
        try {
            ByteArrayInputStream byteStream = new ByteArrayInputStream(bytes);
            ObjectInputStream objectStream = new ObjectInputStream(byteStream);
            Object obj = objectStream.readObject();
            return (T) obj;
        } catch (Exception e) {
            throw new RuntimeException("Cannot deserialize", e);
        }
    }
}
