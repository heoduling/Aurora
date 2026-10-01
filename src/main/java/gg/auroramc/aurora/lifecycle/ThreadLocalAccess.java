package gg.auroramc.aurora.lifecycle;

import sun.misc.Unsafe;
import java.lang.ref.Reference;
import java.lang.reflect.Field;

/** Private JVM fields are touched only for verified plugin-owned retention paths. */
final class ThreadLocalAccess {
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
    static void releaseBacktrace(Throwable diagnostic) throws ReflectiveOperationException {
        // StackTraceElement.computeFormat clears its temporary Class reference on Java 25.
        // Keep the printable frames while releasing the VM's native array of loaded Classes.
        diagnostic.setStackTrace(diagnostic.getStackTrace());
        var backtrace = Throwable.class.getDeclaredField("backtrace");
        UNSAFE.putObjectVolatile(diagnostic, UNSAFE.objectFieldOffset(backtrace), null);
    }
    private ThreadLocalAccess() { }
}
