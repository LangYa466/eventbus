package me.bush.eventbus.bus;

import me.bush.eventbus.annotation.EventListener;
import me.bush.eventbus.event.Event;
import me.bush.eventbus.handler.Handler;
import me.bush.eventbus.handler.handlers.LambdaHandler;
import me.bush.eventbus.handler.handlers.ReflectHandler;
import me.bush.eventbus.util.Util;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * @author bush, LangYa466
 * @since fall 2021
 */
public class EventBus {
    private final Set<Object> subscribers = Collections.synchronizedSet(new HashSet<>());
    private final Map<Class<?>, List<Handler>> handlerMap = new ConcurrentHashMap<>();
    private Class<? extends Handler> handlerType;
    private final Consumer<String> errorLogger;
    private final Consumer<String> infoLogger;

    public EventBus() {
        this(LambdaHandler.class, System.out::println, System.out::println);
    }

    public EventBus(Class<? extends Handler> handlerType) {
        this(handlerType, System.out::println, System.out::println);
    }

    public EventBus(Consumer<String> messageLogger) {
        this(LambdaHandler.class, messageLogger, messageLogger);
    }

    public EventBus(Class<? extends Handler> handlerType, Consumer<String> errorLogger, Consumer<String> infoLogger) {
        this.handlerType = handlerType;
        this.errorLogger = errorLogger;
        this.infoLogger = infoLogger;
    }

    public void subscribe(Object subscriber) {
        if (subscriber == null || subscribers.contains(subscriber)) return;
        subscribers.add(subscriber);
        addHandlers(subscriber);
    }

    public boolean post(Event event) {
        if (event == null) return false;
        List<Handler> handlers = handlerMap.get(event.getClass());
        if (handlers == null) return false;
        handlers.forEach(handler -> {
            if (!event.isCancelled() || handler.shouldRecieveCancelled()) {
                handler.invoke(event);
            }
        });
        return event.isCancelled();
    }

    public void unsubscribe(Object subscriber) {
        if (subscriber == null || !subscribers.contains(subscriber)) return;
        subscribers.remove(subscriber);
        handlerMap.values().forEach(handlers -> handlers.removeIf(handler -> handler.isSubscriber(subscriber)));
        handlerMap.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    public void getInfo() {
        String format = "%-25s%-25s";
        infoLogger.accept("============ EVENTBUS INFO ============");
        infoLogger.accept(String.format(format, "Handler type", handlerType.getSimpleName()));
        infoLogger.accept(String.format(format, "Subscriber count", subscribers.size()));
        int total = handlerMap.values().stream().mapToInt(Collection::size).sum();
        infoLogger.accept(String.format(format, "Listener count", total));
        handlerMap.forEach((eventType, handlers) -> {
            String eventName = Util.formatClassName(eventType);
            infoLogger.accept(String.format(format, eventName, handlers.size()));
        });
    }

    public void setHandlerType(Class<? extends Handler> handlerType) {
        if (this.handlerType == handlerType) return;
        this.handlerType = handlerType;
        handlerMap.clear();
        subscribers.forEach(this::addHandlers);
    }

    private void addHandlers(Object subscriber) {
        boolean isClass = subscriber instanceof Class;
        Arrays.stream((isClass ? (Class<?>) subscriber : subscriber.getClass()).getMethods())
                .filter(method -> method.isAnnotationPresent(EventListener.class))
                .filter(method -> isClass == Modifier.isStatic(method.getModifiers()))
                .forEach(method -> {
                    if (method.getReturnType() != void.class || method.getParameterCount() != 1 || !Event.class.isAssignableFrom(method.getParameterTypes()[0])) {
                        errorLogger.accept(method + " has incorrect return type or parameters.");
                        return;
                    }
                    List<Handler> handlers = handlerMap.computeIfAbsent(method.getParameterTypes()[0], v -> new CopyOnWriteArrayList<>());
                    handlers.add(createHandler(method, subscriber));
                    handlers.sort(Comparator.comparing(Handler::getPriority));
                });
    }

    private Handler createHandler(Method method, Object object) {
        try {
            return handlerType.getDeclaredConstructor(Method.class, Object.class, Consumer.class)
                    .newInstance(method, object, errorLogger);
        } catch (Exception exception) {
            Util.logReflectionExceptions(exception, Util.formatClassName(handlerType), errorLogger);
            errorLogger.accept("Defaulting to ReflectHandler for listener method " + Util.formatMethodName(method) + ".");
            return new ReflectHandler(method, object, errorLogger);
        }
    }
}
