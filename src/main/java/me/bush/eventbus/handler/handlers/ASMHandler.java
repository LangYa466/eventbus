package me.bush.eventbus.handler.handlers;

import me.bush.eventbus.event.Event;
import me.bush.eventbus.handler.DynamicHandler;
import me.bush.eventbus.handler.Handler;
import me.bush.eventbus.util.Util;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * @author bush, forge, LangYa466
 * @since 11/23/2021
 */
public class ASMHandler extends Handler {

    private static final ASMLoader loader = new ASMLoader();
    private static final String handlername = Type.getInternalName(DynamicHandler.class);
    private static final String methodname = Type.getMethodDescriptor(DynamicHandler.class.getDeclaredMethods()[0]);
    private static final ConcurrentHashMap<Method, DynamicHandler> listenercache = new ConcurrentHashMap<>();

    private final DynamicHandler dynamicHandler;

    public ASMHandler(Method listener, Object subscriber, Consumer<String> logger) throws Exception {
        super(listener, subscriber, logger);
        this.dynamicHandler = listenercache.computeIfAbsent(listener, key -> createDynamicHandler(listener, subscriber));
    }

    @Override
    public void invoke(Event event) {
        this.dynamicHandler.invoke(event);
    }

    private DynamicHandler createDynamicHandler(Method listener, Object subscriber) {
        try {
            Class<?> wrapperClass = createWrapper(listener);
            return (DynamicHandler) (Modifier.isStatic(listener.getModifiers())
                    ? wrapperClass.newInstance()
                    : wrapperClass.getConstructor(Object.class).newInstance(subscriber));
        } catch (Exception e) {
            throw new RuntimeException("Failed to create dynamic handler", e);
        }
    }

    private Class<?> createWrapper(Method method) {
        ClassWriter cw = new ClassWriter(0);
        boolean isStatic = Modifier.isStatic(method.getModifiers());
        String name = getUniqueName(method);
        String desc = name.replace('.', '/');
        String instType = Type.getInternalName(method.getDeclaringClass());
        String eventType = Type.getInternalName(method.getParameterTypes()[0]);

        cw.visit(50, 33, desc, null, "java/lang/Object", new String[]{handlername});
        cw.visitSource(".dynamic", null);
        if (!isStatic) cw.visitField(1, "instance", "Ljava/lang/Object;", null, null).visitEnd();

        createConstructor(cw, method, isStatic, desc);
        createInvokeMethod(cw, method, isStatic, instType, eventType, desc);

        cw.visitEnd();
        return loader.define(name, cw.toByteArray());
    }

    private void createConstructor(ClassWriter cw, Method method, boolean isStatic, String desc) {
        MethodVisitor mv = cw.visitMethod(1, "<init>", isStatic ? "()V" : "(Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(25, 0);
        mv.visitMethodInsn(183, "java/lang/Object", "<init>", "()V", false);
        if (!isStatic) {
            mv.visitVarInsn(25, 0);
            mv.visitVarInsn(25, 1);
            mv.visitFieldInsn(181, desc, "instance", "Ljava/lang/Object;");
        }
        mv.visitInsn(177);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    private void createInvokeMethod(ClassWriter cw, Method method, boolean isStatic, String instType, String eventType, String desc) {
        MethodVisitor mv = cw.visitMethod(1, "invoke", methodname, null, null);
        mv.visitCode();
        mv.visitVarInsn(25, 0);
        if (!isStatic) {
            mv.visitFieldInsn(180, desc, "instance", "Ljava/lang/Object;");
            mv.visitTypeInsn(192, instType);
        }
        mv.visitVarInsn(25, 1);
        mv.visitTypeInsn(192, eventType);
        mv.visitMethodInsn(isStatic ? 184 : 182, instType, method.getName(), Type.getMethodDescriptor(method), false);
        mv.visitInsn(177);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    private String getUniqueName(Method method) {
        return String.format("%s_%d_%s", "ASMListener", listenercache.size(), Util.formatMethodName(method));
    }

    private static class ASMLoader extends ClassLoader {
        public ASMLoader() {
            super(ASMLoader.class.getClassLoader());
        }

        public Class<?> define(String name, byte[] data) {
            return this.defineClass(name, data, 0, data.length);
        }
    }
}
