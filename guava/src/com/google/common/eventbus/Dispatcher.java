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

import com.google.common.collect.Queues;

import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * ImmediateDispatcher分发器无额外的线程处理事件。
 * EventBus有一个线程专门处理事件，有一个线程私有的队列用于存放待处理事件。
 * AsyncEventBus有线程池用于处理事件。有一个共享的CopyOnWriteQueue用于存放所有线程要处理的事件。
 * Handler for dispatching events to subscribers, providing different event ordering guarantees that
 * make sense for different situations.
 *
 * <p><b>Note:</b> The dispatcher is orthogonal to the subscriber's {@code Executor}. The dispatcher
 * controls the order in which events are dispatched, while the executor controls how (i.e. on which
 * thread) the subscriber is actually called when an event is dispatched to it.
 *
 * @author Colin Decker
 */
abstract class Dispatcher {

  /**
   * Returns a dispatcher that queues events that are posted reentrantly on a thread that is already
   * dispatching an event, guaranteeing that all events posted on a single thread are dispatched to
   * all subscribers in the order they are posted.
   *
   * <p>When all subscribers are dispatched to using a <i>direct</i> executor (which dispatches on
   * the same thread that posts the event), this yields a breadth-first dispatch order on each
   * thread. That is, all subscribers to a single event A will be called before any subscribers to
   * any events B and C that are posted to the event bus by the subscribers to A.
   */
  static Dispatcher perThreadDispatchQueue() {
    return new PerThreadQueuedDispatcher();
  }

  /**
   * Returns a dispatcher that queues events that are posted in a single global queue. This
   * behavior matches the original behavior of AsyncEventBus exactly, but is otherwise not
   * especially useful. For async dispatch, an {@linkplain #immediate() immediate} dispatcher
   * should generally be preferable.
   */
  static Dispatcher legacyAsync() {
    return new LegacyAsyncDispatcher();
  }

  /**
   * Returns a dispatcher that dispatches events to subscribers immediately as they're posted
   * without using an intermediate queue to change the dispatch order. This is effectively a
   * depth-first dispatch order, vs. breadth-first when using a queue.
   */
  static Dispatcher immediate() {
    return ImmediateDispatcher.INSTANCE;
  }

  /**
   * Dispatches the given {@code event} to the given {@code subscribers}.
   */
  abstract void dispatch(Object event, Iterator<Subscriber> subscribers);

  /**
   * Implementation of a {@link #perThreadDispatchQueue()} dispatcher.
   */
  private static final class PerThreadQueuedDispatcher extends Dispatcher {

    // This dispatcher matches the original dispatch behavior of EventBus.

    /**
     * 线程私有的队列
     * Per-thread queue of events to dispatch.
     */
    private final ThreadLocal<Queue<Event>> queue =
        new ThreadLocal<Queue<Event>>() {
          @Override
          protected Queue<Event> initialValue() {
            return Queues.newArrayDeque();//双端队列
          }
        };

    /**
     * Per-thread dispatch state, used to avoid reentrant event dispatching.
     */
    private final ThreadLocal<Boolean> dispatching =
        new ThreadLocal<Boolean>() {
          @Override
          protected Boolean initialValue() {
            return false;
          }
        };

    @Override
    void dispatch(Object event, Iterator<Subscriber> subscribers) {
      checkNotNull(event);
      checkNotNull(subscribers);
      Queue<Event> queueForThread = queue.get();
      queueForThread.offer(new Event(event, subscribers));//入队，确保事件在队列中存放着
      //本次入队不一定能够进行消费，需要在没有消费者的时候才能消费队列
      //如果dispatching=true，即正处于处理阶段，则不消费队列。该队列只允许

      //通过dispatching保证同一时刻只有一个分发存在
      if (!dispatching.get()) {//防止同时有两个线程在对队列进行分发
        dispatching.set(true);
        try {
          Event nextEvent;
          while ((nextEvent = queueForThread.poll()) != null) {//取出队列的所有事件
            while (nextEvent.subscribers.hasNext()) {//向该事件的订阅者分发该事件
              nextEvent.subscribers.next().dispatchEvent(nextEvent.event);//调用线程池处理
            }
          }
        } finally {
          dispatching.remove();//删除线程内对应的值
          queue.remove();//队列内的事件分发完毕，可以清空该队列了
        }
      }
    }

    private static final class Event {
      private final Object event;
      private final Iterator<Subscriber> subscribers;

      private Event(Object event, Iterator<Subscriber> subscribers) {
        this.event = event;
        this.subscribers = subscribers;
      }
    }
  }

  /**
   * 异步事件总线中事件的分发器。由于事件在不同线程上提交，因此无法保证事件的顺序。
   * 对于存在队列的
   * Implementation of a {@link #legacyAsync()} dispatcher.
   */
  private static final class LegacyAsyncDispatcher extends Dispatcher {

    // This dispatcher matches the original dispatch behavior of AsyncEventBus.
    //
    // We can't really make any guarantees about the overall dispatch order for this dispatcher in
    // a multithreaded environment for a couple reasons:
    //
    // 1. Subscribers to events posted on different threads can be interleaved with each other
    //    freely. (A event on one thread, B event on another could yield any of
    //    [a1, a2, a3, b1, b2], [a1, b2, a2, a3, b2], [a1, b2, b3, a2, a3], etc.)
    // 2. It's possible for subscribers to actually be dispatched to in a different order than they
    //    were added to the queue. It's easily possible for one thread to take the head of the
    //    queue, immediately followed by another thread taking the next element in the queue. That
    //    second thread can then dispatch to the subscriber it took before the first thread does.
    //
    // All this makes me really wonder if there's any value in queueing here at all. A dispatcher
    // that simply loops through the subscribers and dispatches the event to each would actually
    // probably provide a stronger order guarantee, though that order would obviously be different
    // in some cases.

    /**
     * Global event queue.
     */
    private final ConcurrentLinkedQueue<EventWithSubscriber> queue =
        Queues.newConcurrentLinkedQueue();

    @Override
    void dispatch(Object event, Iterator<Subscriber> subscribers) {
      checkNotNull(event);
      //生产者消费者模式
      while (subscribers.hasNext()) {//将所有订阅关系加入到并发队列中
        queue.add(new EventWithSubscriber(event, subscribers.next()));
      }

      EventWithSubscriber e;
      while ((e = queue.poll()) != null) {//取出并发队列中的数据，并进行消费
        e.subscriber.dispatchEvent(e.event);//调用线程池进行处理
      }
    }

    private static final class EventWithSubscriber {
      private final Object event;
      private final Subscriber subscriber;

      private EventWithSubscriber(Object event, Subscriber subscriber) {
        this.event = event;
        this.subscriber = subscriber;
      }
    }
  }

  /**
   * 不存在队列用于缓存事件。
   * 事件一过来，马上调用对应的订阅者消费事件。
   * 只有在事件消费完后才返回，也就是postEvent是要等到事件处理完才能返回处理结果。
   * Implementation of {@link #immediate()}.
   */
  private static final class ImmediateDispatcher extends Dispatcher {
    private static final ImmediateDispatcher INSTANCE = new ImmediateDispatcher();

    @Override
    void dispatch(Object event, Iterator<Subscriber> subscribers) {
      checkNotNull(event);
      while (subscribers.hasNext()) {
        subscribers.next().dispatchEvent(event);
      }
    }
  }
}
