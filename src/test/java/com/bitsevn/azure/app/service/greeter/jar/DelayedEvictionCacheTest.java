package com.bitsevn.azure.app.service.greeter.jar;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class DelayedEvictionCacheTest {
    private static final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("HHmmss").withZone(ZoneId.of("GMT"));

    public static void main(String[] args) {
        DelayedEvictionCacheTest test = new DelayedEvictionCacheTest();
        test.run();
    }

    private void run() {
        final LocalTime gmtNow = LocalTime.now(ZoneId.of("GMT"));

        final DelayedNotifyingCache<Item> cache = new DelayedNotifyingCacheImpl<>(30, 3, (t) -> {});
        new Thread(() -> produceItems(50,"11074", Long.valueOf(dtf.format(gmtNow.minusSeconds(55)) + "0000"), dtf.format(gmtNow.minusSeconds(55)),15, TimeUnit.SECONDS, cache),
                "t-item-prod-1").start();
        new Thread(() -> produceItems(50,"11075",Long.valueOf(dtf.format(gmtNow.minusSeconds(30)) + "0000"), dtf.format(gmtNow.minusSeconds(30)),5, TimeUnit.SECONDS, cache),
                "t-item-prod-2").start();
        new Thread(() -> produceItems(50,"11076",Long.valueOf(dtf.format(gmtNow.minusMinutes(2)) + "0000"), dtf.format(gmtNow.minusMinutes(2)),60, TimeUnit.SECONDS, cache),
                "t-item-prod-3").start();
        CountDownLatch latch = new CountDownLatch(1);
        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void produceItems(
            int maxItems,
            String indexId,
            long startMessageId,
            String startGmtTime,
            long produceFreq,
            TimeUnit produceTimeUnit,
            DelayedNotifyingCache<Item> cache) {
        LocalTime lastTime = null;
        while (maxItems >= 0) {
            maxItems--;
            LocalTime time = null;
            if(lastTime != null) {
                switch (produceTimeUnit) {
                    case MINUTES: time = lastTime.plusMinutes(produceFreq); break;
                    case SECONDS: time = lastTime.plusSeconds(produceFreq); break;
                }
            } else {
                time = LocalTime.from(dtf.parse(startGmtTime)).plusHours(1);
            }
            lastTime = time;
            Item item = new Item(indexId, String.valueOf(startMessageId++), dtf.format(time));
            cache.put(indexId, new CacheItem<>(item, time));
            System.out.println(String.format("[%s] produced item: %s", Thread.currentThread().getName(), item));
            try {
                produceTimeUnit.sleep(produceFreq);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    interface DelayedNotifyingCache<T> {
        void onEvict(T evictedItem);
        void put(String key, T value);
        void put(String key, CacheItem<T> value);
        List<CacheItem<T>> remove(String key);
        CacheItem<T> remove(String key, T value);
        List<T> evict();
    }

    static class DelayedNotifyingCacheImpl<T> extends TimerTask implements DelayedNotifyingCache<T> {
        private static DateTimeFormatter dtf = DateTimeFormatter.ofPattern("HHmmss").withZone(ZoneId.of("GMT"));
        private static DateTimeFormatter dtfLog = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss:SSS");

        private Map<String, PriorityQueue<CacheItem<T>>> cache = new ConcurrentHashMap<>(5000);
        private Consumer<T> evictionCallback;
        private long evictionDelayInMin;
        private long evictionCheckFreqInSec;
        private Timer timer;

        public DelayedNotifyingCacheImpl(long evictionCheckFreqInSec, long evictionDelayInMin, Consumer<T> evictionCallback) {
            this.evictionCheckFreqInSec = evictionCheckFreqInSec;
            this.evictionDelayInMin = evictionDelayInMin;
            this.evictionCallback = evictionCallback;
            this.timer = new Timer();
            long millis = this.evictionCheckFreqInSec * 1000;
            this.timer.scheduleAtFixedRate(this, 0L, millis);
        }

        @Override
        public void onEvict(T evictedItem) {
            if(this.evictionCallback != null) {
                this.evictionCallback.accept(evictedItem);
            }
        }

        @Override
        public void put(String key, T value) {
            this.cache.compute(key, (k, v) -> {
                if(v == null) v = new PriorityQueue<>(3000, Comparator.comparing(CacheItem::getGmtTime));
                // TODO
                return v;
            });
        }

        @Override
        public void put(String key, CacheItem<T> value) {
            this.cache.compute(key, (k, v) -> {
                if(v == null) v = new PriorityQueue<>(3000, Comparator.comparing(CacheItem::getGmtTime));
                v.add(value);
                return v;
            });
        }

        @Override
        public List<CacheItem<T>> remove(String key) {
            PriorityQueue<CacheItem<T>> items = this.cache.remove(key);
            if(items != null) {
                return items.stream().collect(Collectors.toList());
            }
            return null;
        }

        @Override
        public CacheItem<T> remove(String key, T value) {
            if(this.cache.containsKey(key)) {
                PriorityQueue<CacheItem<T>> items = this.cache.get(key);
                Iterator<CacheItem<T>> it = items.iterator();
                while (it.hasNext()) {
                    CacheItem<T> item = it.next();
                    if(item.getData().equals(value)) {
                        it.remove();
                        return item;
                    }
                }
            }
            return null;
        }

        @Override
        public List<T> evict() {
            final List<T> evicted = new ArrayList<>();
            final LocalTime now = LocalTime.now(ZoneId.of("GMT"));
            this.cache.forEach((k, v) -> {
                if(!v.isEmpty()) {
                    if(Duration.between(v.peek().getGmtTime(), now).toMinutes() >= evictionDelayInMin) {
                        evicted.add(v.poll().getData());
                    }
                }

            });
            return evicted;
        }

        @Override
        public void run() {
            LocalDateTime now = LocalDateTime.now(ZoneId.of("GMT"));
            List<T> evicted = evict();
            System.out.println(String.format("[%s] evicted %s items: %s", dtfLog.format(now), evicted.size(), evicted));
        }
    }

    static class CacheItem<T> {
        private T data;
        private LocalTime gmtTime;

        public CacheItem(T data, LocalTime gmtTime) {
            this.data = data;
            this.gmtTime = gmtTime;
        }

        public T getData() {
            return data;
        }

        public void setData(T data) {
            this.data = data;
        }

        public LocalTime getGmtTime() {
            return gmtTime;
        }

        public void setGmtTime(LocalTime gmtTime) {
            this.gmtTime = gmtTime;
        }
    }

    static class Item {
        private String indexId;
        private String messageId;
        private String gmtTime;

        public Item(String indexId, String messageId, String gmtTime) {
            this.indexId = indexId;
            this.messageId = messageId;
            this.gmtTime = gmtTime;
        }

        public String getIndexId() {
            return indexId;
        }

        public void setIndexId(String indexId) {
            this.indexId = indexId;
        }

        public String getMessageId() {
            return messageId;
        }

        public void setMessageId(String messageId) {
            this.messageId = messageId;
        }

        public String getGmtTime() {
            return gmtTime;
        }

        public void setGmtTime(String gmtTime) {
            this.gmtTime = gmtTime;
        }

        @Override
        public String toString() {
            return String.format("{indexId=%s, messageId=%s, gmtTime=%s}", indexId, messageId, gmtTime);
        }
    }
}
