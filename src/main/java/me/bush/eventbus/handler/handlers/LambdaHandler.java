package me.bush.eventbus.handler.handlers;

import me.bush.eventbus.event.Event;
import me.bush.eventbus.handler.DynamicHandler;
import me.bush.eventbus.handler.Handler;

import java.lang.invoke.*;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * @author bush, LangYa466
 * @since fall 2021
 */
public class LambdaHandler extends Handler {

    private static final ConcurrentHashMap<Method, DynamicHandler> handlerCache = new ConcurrentHashMap<>();
    private final DynamicHandler dynamicHandler;

    public LambdaHandler(Method listener, Object subscriber, Consumer<String> logger) throws Throwable {
        super(listener, subscriber, logger);
        this.dynamicHandler = handlerCache.computeIfAbsent(listener, key -> createDynamicHandler(listener, subscriber));
    }

    @Override
    public void invoke(Event event) {
        this.dynamicHandler.invoke(event);
    }

    private DynamicHandler createDynamicHandler(Method listener, Object subscriber) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            boolean isStatic = Modifier.isStatic(listener.getModifiers());
            MethodType targetSignature = MethodType.methodType(DynamicHandler.class);

            CallSite callSite = LambdaMetafactory.metafactory(
                    lookup,
                    "invoke",
                    isStatic ? targetSignature : targetSignature.appendParameterTypes(subscriber.getClass()),
                    MethodType.methodType(void.class, Event.class),
                    lookup.unreflect(listener),
                    MethodType.methodType(void.class, listener.getParameterTypes()[0])
            );

            MethodHandle target = callSite.getTarget();
            return (DynamicHandler) (isStatic ? target.invoke() : target.invoke(subscriber));
        } catch (Throwable e) {
            throw new RuntimeException("Failed to create dynamic handler", e);
        }
    }
}
