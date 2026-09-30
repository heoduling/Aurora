package gg.auroramc.hotswap;

import sun.misc.Unsafe;
import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.*;

/** Legacy bStats 3.0.2 lost its shutdown handle. Read only its known scheduled executor graph. */
final class LegacyMetrics {
    private static final Unsafe UNSAFE;
    static {
        try {
            var f = Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    static Object read(Object object, String name) throws ReflectiveOperationException {
        for (var type = object.getClass(); type != null; type = type.getSuperclass()) {
            try { return read(object, type.getDeclaredField(name)); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static Object read(Object object, Field field) throws ReflectiveOperationException {
        if (field.trySetAccessible()) return field.get(object);
        return UNSAFE.getObject(object, UNSAFE.objectFieldOffset(field));
    }
    static void clearValue(Reference<?> entry) throws ReflectiveOperationException {
        var value = entry.getClass().getDeclaredField("value");
        UNSAFE.putObjectVolatile(entry, UNSAFE.objectFieldOffset(value), null);
    }
    static Set<ThreadPoolExecutor> find(Set<ClassLoader> owners) throws ReflectiveOperationException {
        Set<ThreadPoolExecutor> result = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var thread : Thread.getAllStackTraces().keySet()) {
            if (!thread.getName().equals("bStats-Metrics")) continue;
            Object runnable;
            try { runnable = read(read(thread, "holder"), "task"); }
            catch (NoSuchFieldException e) { runnable = read(thread, "target"); }
            if (runnable == null) continue;
            if (!runnable.getClass().getName().equals("java.util.concurrent.ThreadPoolExecutor$Worker")) {
                throw new IllegalStateException("Unknown bStats thread layout");
            }
            var executor = (ThreadPoolExecutor) read(runnable, "this$0");
            for (var queued : executor.getQueue()) {
                if (ownedMetrics(queued, owners, 0, Collections.newSetFromMap(new IdentityHashMap<>()))) {
                    result.add(executor); break;
                }
            }
        }
        return result;
    }
    private static boolean ownedMetrics(Object value, Set<ClassLoader> owners, int depth, Set<Object> visited) throws ReflectiveOperationException {
        if (value == null || depth > 6 || !visited.add(value)) return false;
        var type = value.getClass();
        if (type.getClassLoader() != null && owners.contains(type.getClassLoader()) && type.getName().contains(".bstats.MetricsBase")) return true;
        String name = type.getName();
        if (!(name.startsWith("java.util.concurrent.") || name.contains(".bstats."))) return false;
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (var field : c.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive()
                        && ownedMetrics(read(value, field), owners, depth + 1, visited)) return true;
            }
        }
        return false;
    }
    static void stop(Set<ThreadPoolExecutor> executors) throws InterruptedException {
        for (var executor : executors) executor.shutdownNow();
        for (var executor : executors) {
            if (!executor.awaitTermination(8, TimeUnit.SECONDS)) throw new IllegalStateException("Legacy bStats did not stop");
        }
    }
    private LegacyMetrics() { }
}
