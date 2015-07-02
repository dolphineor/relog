package com.ss.es;

import com.ss.main.Constants;
import com.ss.monitor.MonitorService;
import com.ss.parser.KeywordExtractor;
import com.ss.parser.SearchEngineParser;
import com.ss.redis.JRedisPools;
import com.ss.utils.UrlUtils;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.collect.Lists;
import redis.clients.jedis.Jedis;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Created by yousheng on 15/3/16.
 */
public class EsForward implements Constants {

    private static final int ONE_DAY_SECONDS = 86_400;

    private final int HANDLER_WORKERS = Runtime.getRuntime().availableProcessors() * 2;

    private final BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>();

    private final ExecutorService preHandlerExecutor = Executors.newFixedThreadPool(HANDLER_WORKERS, new DataPreHandleThreadFactory());

    private final ExecutorService requestHandlerExecutor = Executors.newFixedThreadPool(HANDLER_WORKERS, new EsRequestThreadFactory());


    public EsForward(TransportClient client) {
        BlockingQueue<IndexRequest> requestQueue = new LinkedBlockingQueue<>();
        preHandle(client, requestQueue);
        handleRequest(client, requestQueue);
    }

    public void add(Map<String, Object> obj) {
        queue.add(obj);
    }

    private void preHandle(TransportClient client, BlockingQueue<IndexRequest> requestQueue) {
        for (int i = 0; i < HANDLER_WORKERS; i++)
            preHandlerExecutor.execute(new PreHandleWorker(client, requestQueue));
    }

    private void handleRequest(TransportClient client, BlockingQueue<IndexRequest> requestQueue) {
        for (int i = 0; i < HANDLER_WORKERS; i++)
            requestHandlerExecutor.execute(new RequestHandleWorker(client, requestQueue));
    }

    private void submitRequest(BulkRequestBuilder bulkRequestBuilder) {
        BulkResponse responses = bulkRequestBuilder.get();
        if (responses.hasFailures()) {
            System.out.println("Failure: " + responses.buildFailureMessage());
            MonitorService.getService().es_data_error();
        }
    }

    private void addRequest(TransportClient client, BlockingQueue<IndexRequest> requestQueue, Map<String, Object> source) {
        IndexRequestBuilder builder = client.prepareIndex();
        builder.setIndex(source.remove(INDEX).toString());
        builder.setType(source.remove(TYPE).toString());
        builder.setSource(source);
        requestQueue.add(builder.request());
    }


    class DataPreHandleThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable r) {
            PreHandlerThread thread = new PreHandlerThread(r);
            thread.setName("thread-relog-preHandler-" + counter.incrementAndGet());
            return thread;
        }
    }

    class EsRequestThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable r) {
            RequestHandlerThread thread = new RequestHandlerThread(r);
            thread.setName("thread-relog-requestHandler-" + counter.incrementAndGet());
            return thread;
        }
    }

    /**
     * 数据预处理线程
     */
    class PreHandlerThread extends Thread {
        public PreHandlerThread(Runnable target) {
            super(target);
        }
    }

    /**
     * es请求处理线程
     */
    class RequestHandlerThread extends Thread {
        public RequestHandlerThread(Runnable target) {
            super(target);
        }
    }

    class PreHandleWorker implements Runnable {
        private final TransportClient client;
        private final BlockingQueue<IndexRequest> requestQueue;

        PreHandleWorker(TransportClient client, BlockingQueue<IndexRequest> requestQueue) {
            this.client = client;
            this.requestQueue = requestQueue;
        }

        @Override
        public void run() {
            while (true) {
                Map<String, Object> mapSource = null;
                try {
                    mapSource = queue.take();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                if (mapSource == null || !mapSource.containsKey(T) || !mapSource.containsKey(TT))
                    continue;

                long clientTime = Long.parseLong(mapSource.remove(DT).toString());
                mapSource.put(CLIENT_TIME, clientTime);

                Jedis jedis = null;
                try {
                    jedis = JRedisPools.getConnection();
                    String trackId = mapSource.getOrDefault(T, EMPTY_STRING).toString();
                    String esType = jedis.get(TYPE_ID_PREFIX + trackId);
                    if (esType == null)
                        continue;

                    // 网站代码安装的正确性检测
                    String siteUrl = jedis.get(SITE_URL_PREFIX + trackId);
                    if (Strings.isEmpty(siteUrl) || !UrlUtils.match(siteUrl, mapSource.get(CURR_ADDRESS).toString()))
                        continue;

                    /**
                     * 检测当天对同一网站访问的重复性
                     * key: trackId-192.168.1.8-2015-07-01
                     */
                    String ipDupliKey = trackId + PLACEHOLDER +
                            mapSource.get(REMOTE).toString() + PLACEHOLDER +
                            LocalDate.now().toString();
                    Long statusCode = jedis.sadd(ipDupliKey, mapSource.get(REMOTE).toString());
                    mapSource.put(IP_DUPLICATE, statusCode);
                    // 设置过期时间
                    jedis.expire(ipDupliKey, ONE_DAY_SECONDS);

                    // TEST CODE
                    if (TEST_TRACK_ID.equals(trackId)) {
                        MonitorService.getService().data_ready();
                    }

                    // 区分普通访问, 事件跟踪, xy坐标, 推广URL统计信息
                    String eventInfo = mapSource.getOrDefault(ET, EMPTY_STRING).toString();
                    String xyCoordinateInfo = mapSource.getOrDefault(XY, EMPTY_STRING).toString();
                    String promotionUrlInfo = mapSource.getOrDefault(UT, EMPTY_STRING).toString();
                    if (!eventInfo.isEmpty()) {
                        mapSource.put(TYPE, esType + ES_TYPE_EVENT_SUFFIX);
                        addRequest(client, requestQueue, EventProcessor.handle(mapSource));
                        continue;
                    } else if (!xyCoordinateInfo.isEmpty()) {
                        mapSource.put(TYPE, esType + ES_TYPE_XY_SUFFIX);
                        addRequest(client, requestQueue, CoordinateProcessor.handle(mapSource));
                        continue;
                    } else if (!promotionUrlInfo.isEmpty()) {
                        if (!mapSource.get(CURR_ADDRESS).toString().contains(SEM_KEYWORD_IDENTIFIER))
                            continue;
                        mapSource.put(TYPE, esType + ES_TYPE_PROMOTION_URL_SUFFIX);
                        addRequest(client, requestQueue, PromotionUrlProcessor.handle(mapSource));
                        continue;
                    }
                    mapSource.put(TYPE, esType);

                    // 检测是否是一次的新的访问(1->新的访问, 0->同一次访问)
                    int identifier = Integer.valueOf(mapSource.getOrDefault(NEW_VISIT, 0).toString());
                    if (identifier == 1) {
                        mapSource.put(ENTRANCE, 1);
                        mapSource.remove(NEW_VISIT);
                        String _location = mapSource.get(CURR_ADDRESS).toString();

                        boolean hasPromotion = false;
                        if (_location.contains(SEM_KEYWORD_IDENTIFIER)) {
                            // keyword extract
                            Map<String, Object> keywordInfoMap = KeywordExtractor.parse(_location);
                            if (!keywordInfoMap.isEmpty())
                                mapSource.putAll(keywordInfoMap);

                            hasPromotion = true;
                        }

                        URL url = new URL(_location);
                        _location = url.getProtocol() + DOUBLE_SLASH + url.getHost().split("/")[0];
                        mapSource.put(CURR_ADDRESS, _location);
                        mapSource.put(DESTINATION_URL, hasPromotion ? _location : PLACEHOLDER);
                    } else {
                        mapSource.put(ENTRANCE, 0);
                    }

                    // 来源类型解析
                    String refer = mapSource.get(RF).toString();
                    String tt = mapSource.get(TT).toString();
                    String rf_type;
                    if (PLACEHOLDER.equals(refer)) {  // 直接访问
                        mapSource.put(SE, PLACEHOLDER);
                        mapSource.put(KW, PLACEHOLDER);
                        rf_type = jedis.get(tt);
                        if (rf_type == null) {
                            mapSource.put(RF_TYPE, VAL_RF_TYPE_DIRECT);
                            jedis.setex(tt, ONE_DAY_SECONDS, VAL_RF_TYPE_DIRECT);
                        } else
                            mapSource.put(RF_TYPE, rf_type);

                        mapSource.put(DOMAIN, PLACEHOLDER);
                    } else {
                        List<String> skList = Lists.newArrayList();
                        boolean found = SearchEngineParser.getSK(java.net.URLDecoder.decode(refer, StandardCharsets.UTF_8.name()), skList);
                        // extract domain from rf
                        URL url = new URL(refer);
                        mapSource.put(DOMAIN, url.getProtocol() + DOUBLE_SLASH + url.getHost());
                        if (found) {
                            mapSource.put(SE, skList.remove(0));
                            mapSource.put(KW, skList.remove(0));
                            rf_type = jedis.get(tt);
                            if (rf_type == null) {
                                mapSource.put(RF_TYPE, VAL_RF_TYPE_SE);
                                jedis.setex(tt, ONE_DAY_SECONDS, VAL_RF_TYPE_SE);
                            } else
                                mapSource.put(RF_TYPE, rf_type);
                        } else {
                            rf_type = jedis.get(tt);
                            if (rf_type == null) {
                                mapSource.put(RF_TYPE, VAL_RF_TYPE_OUTLINK);
                                jedis.setex(tt, ONE_DAY_SECONDS, VAL_RF_TYPE_OUTLINK);
                            } else
                                mapSource.put(RF_TYPE, rf_type);
                        }
                    }

                    // 访问URL路径解析
                    String location = UrlUtils.removeProtocol(mapSource.get(CURR_ADDRESS).toString());
                    if (location.contains(QUESTION_MARK))
                        location = location.substring(0, location.indexOf(QUESTION_MARK));

                    Map<String, String> pathMap = new HashMap<>();

                    final AtomicInteger integer = new AtomicInteger(0);
                    Consumer<String> pathConsumer = (String c) -> pathMap.put(HTTP_PATH + (integer.getAndIncrement()), c);
                    Arrays.asList(location.split("/")).stream().filter((p) -> !p.isEmpty() || !p.startsWith(HTTP_PREFIX)).forEach(pathConsumer);
                    mapSource.put(PATHS, pathMap);

                    addRequest(client, requestQueue, mapSource);
                } catch (NullPointerException | UnsupportedEncodingException | MalformedURLException e) {
                    e.printStackTrace();
                    MonitorService.getService().data_error();
                } finally {
                    if (jedis != null) {
                        jedis.close();
                    }
                }

            }
        }

    }

    class RequestHandleWorker implements Runnable {
        private final TransportClient client;
        private final BlockingQueue<IndexRequest> requestQueue;

        public RequestHandleWorker(TransportClient client, BlockingQueue<IndexRequest> requestQueue) {
            this.client = client;
            this.requestQueue = requestQueue;
        }

        @Override
        public void run() {
            BulkRequestBuilder bulkRequestBuilder = client.prepareBulk();
            while (true) {
                IndexRequest request = null;
                try {
                    request = requestQueue.take();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                if (request == null)
                    continue;

                bulkRequestBuilder.add(request);

                if (requestQueue.isEmpty() && bulkRequestBuilder.numberOfActions() > 0) {
                    submitRequest(bulkRequestBuilder);
                    bulkRequestBuilder = client.prepareBulk();
                    continue;
                }

                if (bulkRequestBuilder.numberOfActions() == EsPools.getBulkRequestNumber()) {
                    submitRequest(bulkRequestBuilder);
                    bulkRequestBuilder = client.prepareBulk();
                }

            }
        }

    }

}
