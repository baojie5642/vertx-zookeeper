/*
 *  Copyright (c) 2011-2016 The original author or authors
 *  ------------------------------------------------------
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *       The Eclipse Public License is available at
 *       http://www.eclipse.org/legal/epl-v10.html
 *
 *       The Apache License v2.0 is available at
 *       http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */
package io.vertx.spi.cluster.zookeeper.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.TimeoutStream;
import io.vertx.core.Vertx;
import io.vertx.core.VertxException;
import io.vertx.core.shareddata.AsyncMap;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.zookeeper.data.Stat;

import java.time.Instant;
import java.util.Optional;

/**
 * Created by Stream.Liu
 */
public class ZKAsyncMap<K, V> extends ZKMap<K, V> implements AsyncMap<K, V> {

  private final PathChildrenCache curatorCache;

  public ZKAsyncMap(Vertx vertx, CuratorFramework curator, String mapName) {
    super(curator, vertx, ZK_PATH_ASYNC_MAP, mapName);
    curatorCache = new PathChildrenCache(curator, mapPath, true);
    try {
      curatorCache.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE);
    } catch (Exception e) {
      throw new VertxException(e);
    }
  }

  @Override
  public void get(K k, Handler<AsyncResult<V>> asyncResultHandler) {
    assertKeyIsNotNull(k)
      .compose(aVoid -> checkExists(k))
      .compose(checkResult -> {
        Future<V> future = Future.future();
        if (checkResult) {
          ChildData childData = curatorCache.getCurrentData(keyPath(k));
          if (childData != null && childData.getData() != null) {
            try {
              V value = asObject(childData.getData());
              future.complete(value);
            } catch (Exception e) {
              future.fail(e);
            }
          } else {
            future.complete();
          }
        } else {
          //ignore
          future.complete();
        }
        return future;
      })
      .setHandler(asyncResultHandler);
  }

  @Override
  public void put(K k, V v, Handler<AsyncResult<Void>> completionHandler) {
    put(k, v, Optional.empty(), completionHandler);
  }

  @Override
  public void put(K k, V v, long timeout, Handler<AsyncResult<Void>> completionHandler) {
    completionHandler.handle(Future.failedFuture(new UnsupportedOperationException()));
  }

  private void put(K k, V v, Optional<TimeoutStream> timeoutStream, Handler<AsyncResult<Void>> completionHandler) {
    assertKeyAndValueAreNotNull(k, v)
      .compose(aVoid -> checkExists(k))
      .compose(checkResult -> checkResult ? setData(k, v) : create(k, v))
      .setHandler(asyncResult -> {
        timeoutStream.ifPresent(TimeoutStream::cancel);
        if (asyncResult.failed()) {
          completionHandler.handle(Future.failedFuture(asyncResult.cause()));
        } else {
          completionHandler.handle(Future.succeededFuture(asyncResult.result()));
        }
      });
  }

  @Override
  public void putIfAbsent(K k, V v, Handler<AsyncResult<V>> completionHandler) {
    putIfAbsent(k, v, Optional.empty(), completionHandler);
  }

  @Override
  public void putIfAbsent(K k, V v, long timeout, Handler<AsyncResult<V>> completionHandler) {
    completionHandler.handle(Future.failedFuture(new UnsupportedOperationException()));
  }

  private void putIfAbsent(K k, V v, Optional<TimeoutStream> timeoutStream, Handler<AsyncResult<V>> completionHandler) {
    assertKeyAndValueAreNotNull(k, v)
      .compose(aVoid -> {
        Future<V> innerFuture = Future.future();
        vertx.executeBlocking(future -> {
          long startTime = Instant.now().toEpochMilli();
          int retries = 0;

          for (; ; ) {
            try {
              Stat stat = new Stat();
              String path = keyPath(k);
              V currentValue = getData(stat, path);
              if (compareAndSet(startTime, retries++, stat, path, currentValue, v)) {
                future.complete(currentValue);
                return;
              }
            } catch (Exception e) {
              future.fail(e);
              return;
            }
          }
        }, false, innerFuture.completer());
        return innerFuture;
      })
      .setHandler(asyncResult -> {
        timeoutStream.ifPresent(TimeoutStream::cancel);
        if (asyncResult.failed()) {
          completionHandler.handle(Future.failedFuture(asyncResult.cause()));
        } else {
          completionHandler.handle(Future.succeededFuture(asyncResult.result()));
        }
      });
  }

  @Override
  public void remove(K k, Handler<AsyncResult<V>> asyncResultHandler) {
    assertKeyIsNotNull(k).compose(aVoid -> {
      Future<V> future = Future.future();
      get(k, future.completer());
      return future;
    }).compose(value -> {
      Future<V> future = Future.future();
      if (value != null) {
        return delete(k, value);
      } else {
        future.complete();
      }
      return future;
    }).setHandler(asyncResultHandler);
  }

  @Override
  public void removeIfPresent(K k, V v, Handler<AsyncResult<Boolean>> resultHandler) {
    assertKeyAndValueAreNotNull(k, v)
      .compose(aVoid -> {
        Future<V> future = Future.future();
        get(k, future.completer());
        return future;
      })
      .compose(value -> {
        Future<Boolean> future = Future.future();
        if (value.equals(v)) {
          delete(k, v).setHandler(deleteResult -> {
            if (deleteResult.succeeded()) future.complete(true);
            else future.fail(deleteResult.cause());
          });
        } else {
          future.complete(false);
        }
        return future;
      }).setHandler(resultHandler);
  }

  @Override
  public void replace(K k, V v, Handler<AsyncResult<V>> asyncResultHandler) {
    assertKeyAndValueAreNotNull(k, v)
      .compose(aVoid -> {
        Future<V> innerFuture = Future.future();
        vertx.executeBlocking(future -> {
          long startTime = Instant.now().toEpochMilli();
          int retries = 0;

          for (; ; ) {
            try {
              Stat stat = new Stat();
              String path = keyPath(k);
              V currentValue = getData(stat, path);
              //do not replace value if previous value is null
              if (currentValue == null) {
                future.complete(null);
                return;
              }
              if (compareAndSet(startTime, retries++, stat, path, currentValue, v)) {
                future.complete(currentValue);
                return;
              }
            } catch (Exception e) {
              future.fail(e);
              return;
            }
          }
        }, false, innerFuture.completer());
        return innerFuture;
      })
      .setHandler(asyncResultHandler);
  }

  @Override
  public void replaceIfPresent(K k, V oldValue, V newValue, Handler<AsyncResult<Boolean>> resultHandler) {
    assertKeyIsNotNull(k)
      .compose(aVoid -> assertValueIsNotNull(oldValue))
      .compose(aVoid -> assertValueIsNotNull(newValue))
      .compose(aVoid -> {
        Future<Boolean> innerFuture = Future.future();
        vertx.executeBlocking(future -> {
          long startTime = Instant.now().toEpochMilli();
          int retries = 0;

          for (; ; ) {
            try {
              Stat stat = new Stat();
              String path = keyPath(k);
              V currentValue = getData(stat, path);
              if (!currentValue.equals(oldValue)) {
                future.complete(false);
                return;
              }
              if (compareAndSet(startTime, retries++, stat, path, oldValue, newValue)) {
                future.complete(true);
                return;
              }
            } catch (Exception e) {
              future.fail(e);
              return;
            }
          }
        }, false, innerFuture.completer());
        return innerFuture;
      })
      .setHandler(resultHandler);
  }

  @Override
  public void clear(Handler<AsyncResult<Void>> resultHandler) {
    //just remove parent node
    delete(mapPath, null).setHandler(result -> {
      if (result.succeeded()) {
        resultHandler.handle(Future.succeededFuture());
      } else {
        resultHandler.handle(Future.failedFuture(result.cause()));
      }
    });
  }

  @Override
  public void size(Handler<AsyncResult<Integer>> resultHandler) {
    try {
      curator.getChildren().inBackground((client, event) ->
        vertx.runOnContext(aVoid -> resultHandler.handle(Future.succeededFuture(event.getChildren().size()))))
        .forPath(mapPath);
    } catch (Exception e) {
      resultHandler.handle(Future.failedFuture(e));
    }
  }

}