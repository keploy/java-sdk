package io.keploy.advice.ksql;

import org.objectweb.asm.*;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;

public class LineLoggerTransformer implements ClassFileTransformer {

    private final String targetPackage;

    public LineLoggerTransformer(String targetPackage) {
        this.targetPackage = targetPackage;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
        if (className.startsWith(targetPackage)) {
            try (InputStream inputStream = loader.getResourceAsStream(className + ".class")) {
                ClassReader classReader = new ClassReader(inputStream);
                ClassWriter classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_FRAMES);
                classReader.accept(new LineLoggerClassVisitor(classWriter), 0);
                return classWriter.toByteArray();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }
}
