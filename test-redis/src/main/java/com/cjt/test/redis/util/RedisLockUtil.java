package com.cjt.test.redis.util;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @Author: chenjintao
 * @Date: 2021/3/23 22:02
 */
public class RedisLockUtil {
    public final static int DEFAULT_EXPIRE_MILLIS = 60 * 1000;        //设置默认的过期时间为60s
    public final static float DEFAULT_EXPIRE_CHECK_FACTOR = 0.75f;    //定义过期时间提前检测的时间点比例，默认表示在设定的过期时间达到75%时增加过期时间
    public final static float DEFAULT_EXPIRE_LOAD_FACTOR = 0.75f;     //定义过期时间增量的比例，默认表示每次增加设定的过期时间的75%
    private final static ThreadLocal<Map<String, String>> THREAD_LOCAL = ThreadLocal.withInitial(HashMap::new);
    private final static ExecutorService executorService = Executors.newFixedThreadPool(20, (r) -> {      //创建守护线程池
        Thread thread = new Thread(r);
        thread.setDaemon(true);
        return thread;
    });

    public static boolean lock(String key) {
        return lock(key, DEFAULT_EXPIRE_MILLIS);
    }


    public static boolean lock(String key, long expireMillis) {
        long beginMillis = System.currentTimeMillis();
        Jedis jedis = JedisUtil.getJedis();
//        if (jedis.exists(key)) return false;            //如果存在就直接返回false

        SetParams setParams = new SetParams();
        setParams.nx();                 //使用setNx
        setParams.px(expireMillis);     //设置key过期时间

        //设置一个UUID，存入redis，并用于校验是否过期被重入
        String uuid = UUID.randomUUID().toString().replaceAll("-", "");
        String setResult = jedis.set(key, uuid);         //成功ok，失败null
        if (setResult == null) return false;

        Map<String, String> localMap = THREAD_LOCAL.get();
        localMap.put("id", uuid);
        localMap.put("lockFlag", "true");                //设置一个锁标识表示是否获取到锁了

        /*
        启动一个守护线程，用于自动为将要将要过期的key增加时间，通过定义的锁标识在当前线程释放锁时关闭该线程
         */
        executorService.execute(() -> {
            long e = expireMillis;
            while (Boolean.parseBoolean(localMap.get("lockFlag"))) {
                long checkMillis = (long) (e * DEFAULT_EXPIRE_CHECK_FACTOR);
                if (System.currentTimeMillis() - beginMillis >= checkMillis) {
                    long v1 = (long) (e * DEFAULT_EXPIRE_LOAD_FACTOR);
                    e = checkMillis + v1;
                    jedis.pexpire(key, v1);
                }
            }
        });
        return true;
    }

    public static boolean unlock(String key) {
        Jedis jedis = JedisUtil.getJedis();
        Map<String, String> localMap = THREAD_LOCAL.get();
        String id = localMap.get("id");
        String v = jedis.get(key);
        if (!v.equals(id)) return true;         //如果拿到的id不是当前线程赋予的，则为了防止删除另一个线程的锁，直接返回true
        boolean result = jedis.del(key) > 0;
        localMap.put("lockFlag", "false");      //设置线程局部变量锁标识
        return result;
    }
}
