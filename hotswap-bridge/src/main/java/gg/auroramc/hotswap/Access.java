package gg.auroramc.hotswap;

import java.lang.reflect.*;

/** Reflection is confined to the two pinned legacy plugins and the tested scheduler layout. */
final class Access {
    static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { var f = c.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
    static Object get(Object object, String name) throws ReflectiveOperationException {
        return field(object instanceof Class<?> c ? c : object.getClass(), name).get(object instanceof Class<?> ? null : object);
    }
    static void set(Object object, String name, Object value) throws ReflectiveOperationException {
        field(object instanceof Class<?> c ? c : object.getClass(), name).set(object instanceof Class<?> ? null : object, value);
    }
    static Object call(Object object, String name, Object... args) throws ReflectiveOperationException {
        var type = object instanceof Class<?> c ? c : object.getClass();
        for (var method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            boolean matches = true;
            for (int i = 0; i < args.length; i++) {
                if (args[i] != null && !wrap(method.getParameterTypes()[i]).isInstance(args[i])) { matches = false; break; }
            }
            if (!matches) continue;
            method.setAccessible(true);
            try { return method.invoke(object instanceof Class<?> ? null : object, args); }
            catch (InvocationTargetException failure) { throw new ReflectiveOperationException(name, failure.getCause()); }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
    private static Class<?> wrap(Class<?> c) {
        if (c == boolean.class) return Boolean.class;
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        return c;
    }
    private Access() { }
}
