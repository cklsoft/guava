/*
 * Copyright (C) 2014 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.common.eventbus;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;

/**
 * A subscriber method on a specific object, plus the executor that should be used for
 * dispatching events to it.
 *
 * <p>Two subscribers are equivalent when they refer to the same method on the same object (not
 * class). This property is used to ensure that no subscriber method is registered more than once.
 * 通过将订阅者设置为static final保证不会被注册两次
 * @author Colin Decker
 */
class Subscriber {

  /**
   * 根据方法是否线程安全创建一个同步或异步的监听者。
   * Creates a {@code Subscriber} for {@code method} on {@code listener}.
   */
  static Subscriber create(EventBus bus, Object listener, Method method) {
    return isDeclaredThreadSafe(method)
        ? new Subscriber(bus, listener, method)
        : new SynchronizedSubscriber(bus, listener, method);
  }

  /** The event bus this subscriber belongs to. */
  private EventBus bus;

  /** Object sporting the subscriber method. */
  @VisibleForTesting
  final Object target;

  /** Subscriber method. */
  private final Method method;

  /** Executor to use for dispatching events to this subscriber. */
  private final Executor executor;

  private Subscriber(EventBus bus, Object target, Method method) {
    this.bus = bus;
    this.target = checkNotNull(target);
    this.method = method;
    method.setAccessible(true);//针对可能处理方法是私有的情况，为了在外部能够调用，需要设为true

    this.executor = bus.executor();
  }

  /**
   * Dispatches {@code event} to this subscriber using the proper executor.
   */
  final void dispatchEvent(final Object event) {
    executor.execute(new Runnable() {
      @Override
      public void run() {
        try {
          invokeSubscriberMethod(event);//调用订阅者中的处理事件方法
        } catch (InvocationTargetException e) {
          bus.handleSubscriberException(e.getCause(), context(event));
        }
      }
    });
  }

  /**
   * Invokes the subscriber method. This method can be overridden to make the invocation
   * synchronized.
   */
  @VisibleForTesting
  void invokeSubscriberMethod(Object event) throws InvocationTargetException {
    try {
      method.invoke(target, checkNotNull(event));//以反射的方式调用处理方法
    } catch (IllegalArgumentException e) {
      throw new Error("Method rejected target/argument: " + event, e);
    } catch (IllegalAccessException e) {
      throw new Error("Method became inaccessible: " + event, e);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof Error) {
        throw (Error) e.getCause();
      }
      throw e;
    }
  }

  /**
   * Gets the context for the given event.
   */
  private SubscriberExceptionContext context(Object event) {
    return new SubscriberExceptionContext(bus, event, target, method);
  }

  @Override
  public final int hashCode() {
    return (31 + method.hashCode()) * 31 + System.identityHashCode(target);
  }

  @Override
  public final boolean equals(@Nullable Object obj) {
    if (obj instanceof Subscriber) {
      Subscriber that = (Subscriber) obj;
      // Use == so that different equal instances will still receive events.
      // We only guard against the case that the same object is registered
      // multiple times
      return target == that.target && method.equals(that.method);
    }
    return false;
  }

  /**
   * Checks whether {@code method} is thread-safe, as indicated by the presence of the
   * {@link AllowConcurrentEvents} annotation.
   */
  private static boolean isDeclaredThreadSafe(Method method) {
    return method.getAnnotation(AllowConcurrentEvents.class) != null;
  }

  /**
   * 异步方法调用，保证了处理方法在一个时刻只被一个线程调用。不会出现多个线程同时调用处理方法的现象。
   * Subscriber that synchronizes invocations of a method to ensure that only one thread may enter
   * the method at a time.
   */
  @VisibleForTesting
  static final class SynchronizedSubscriber extends Subscriber {//标记为final使得不被继承,内部静态类对所有实例都可见

    private SynchronizedSubscriber(EventBus bus, Object target, Method method) {
      super(bus, target, method);
    }

    @Override
    void invokeSubscriberMethod(Object event) throws InvocationTargetException {
      synchronized (this) {//需要并发控制，调用父辈方法
        super.invokeSubscriberMethod(event);
      }
    }
  }
}