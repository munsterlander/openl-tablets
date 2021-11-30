package org.openl.types.java;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.commons.collections4.map.AbstractReferenceMap;
import org.apache.commons.collections4.map.ReferenceMap;
import org.openl.classloader.OpenLClassLoader;

public final class JavaOpenClassCache {

    private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    /**
     * Stores a strong references to common java classes that's why they will not be garbage collected
     */
    private volatile Map<Class<?>, JavaOpenClass> javaClassCache;
    /**
     * Cache for all classes (including javaClassCache and generated by OpenL classes) Uses soft references to prevent
     * memory leak. Classes added to javaClassCache will not be garbage collected. TODO use better cache implementation
     * instead
     */
    private final Map<Class<?>, JavaOpenClass> cache = new ReferenceMap<>(AbstractReferenceMap.ReferenceStrength.SOFT,
        AbstractReferenceMap.ReferenceStrength.SOFT);

    public static JavaOpenClassCache getInstance() {
        return JavaOpenClassCacheHolder.INSTANCE;
    }

    private static Map<Class<?>, JavaOpenClass> initializeJavaClassCache() {
        Map<Class<?>, JavaOpenClass> javaClassCache = new HashMap<>();
        javaClassCache.put(int.class, JavaOpenClass.INT);
        javaClassCache.put(Integer.class, new JavaOpenClass(Integer.class, true));
        javaClassCache.put(long.class, JavaOpenClass.LONG);
        javaClassCache.put(Long.class, new JavaOpenClass(Long.class, true));
        javaClassCache.put(double.class, JavaOpenClass.DOUBLE);
        javaClassCache.put(Double.class, new JavaOpenClass(Double.class, true));
        javaClassCache.put(float.class, JavaOpenClass.FLOAT);
        javaClassCache.put(Float.class, new JavaOpenClass(Float.class, true));
        javaClassCache.put(short.class, JavaOpenClass.SHORT);
        javaClassCache.put(Short.class, new JavaOpenClass(Short.class, true));
        javaClassCache.put(char.class, JavaOpenClass.CHAR);
        javaClassCache.put(Character.class, new JavaOpenClass(Character.class, true));
        javaClassCache.put(byte.class, JavaOpenClass.BYTE);
        javaClassCache.put(Byte.class, new JavaOpenClass(Byte.class, true));
        javaClassCache.put(boolean.class, JavaOpenClass.BOOLEAN);
        javaClassCache.put(Boolean.class, new JavaOpenClass(Boolean.class, true));
        javaClassCache.put(void.class, JavaOpenClass.VOID);
        javaClassCache.put(Void.class, JavaOpenClass.CLS_VOID);
        javaClassCache.put(String.class, JavaOpenClass.STRING);
        javaClassCache.put(Object.class, JavaOpenClass.OBJECT);
        javaClassCache.put(Class.class, JavaOpenClass.CLASS);
        javaClassCache.put(Date.class, new JavaOpenClass(Date.class, true));
        javaClassCache.put(BigInteger.class, new JavaOpenClass(BigInteger.class, true));
        javaClassCache.put(BigDecimal.class, new JavaOpenClass(BigDecimal.class, true));
        return javaClassCache;
    }

    private Map<Class<?>, JavaOpenClass> getJavaClassCache() {
        if (javaClassCache == null) {
            synchronized (this) {
                if (javaClassCache == null) {
                    javaClassCache = initializeJavaClassCache();
                }
            }
        }
        return javaClassCache;
    }

    JavaOpenClass get(Class<?> c) {
        JavaOpenClass openClass = getJavaClassCache().get(c);
        if (openClass != null) {
            return openClass;
        }
        Lock lock = readWriteLock.readLock();
        try {
            lock.lock();
            return cache.get(c);
        } finally {
            lock.unlock();
        }
    }

    public void resetClassloader(ClassLoader cl) {
        final Lock lock = readWriteLock.writeLock();

        try {
            lock.lock();

            List<Class<?>> toRemove = new ArrayList<>();
            for (Class<?> c : cache.keySet()) {
                ClassLoader classLoader = c.getClassLoader();
                if (classLoader == cl) {
                    toRemove.add(c);
                }
                if (cl instanceof OpenLClassLoader) {
                    if (((OpenLClassLoader) cl).containsClassLoader(classLoader)) {
                        toRemove.add(c);
                    }
                }
            }

            for (Class<?> c : toRemove) {
                if (getJavaClassCache().containsKey(c)) {
                    continue;
                }
                cache.remove(c);
            }
        } finally {
            lock.unlock();
        }
    }

    JavaOpenClass put(Class<?> c, JavaOpenClass openClass) {
        JavaOpenClass javaOpenClass = getJavaClassCache().get(c);
        if (javaOpenClass != null) {
            return javaOpenClass;
        }
        Lock lock = readWriteLock.writeLock();
        try {
            lock.lock();
            JavaOpenClass existed = cache.get(c);
            if (existed != null) {
                return existed;
            }
            cache.put(c, openClass);
            return openClass;
        } finally {
            lock.unlock();
        }
    }

    private static class JavaOpenClassCacheHolder {
        private static final JavaOpenClassCache INSTANCE = new JavaOpenClassCache();
    }

}
