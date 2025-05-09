package com.igot.cb.cache;


import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class DataCacheMgr {
    private Map<String, String> strCacheMap = new HashMap<String, String>();

    private Map<String, Object> objCacheMap = new HashMap<String, Object>();

    private Map<String, Map<String, Object>> contentCacheMap = new HashMap<String, Map<String, Object>>();

    public void putStringInCache(String key, String value) {
        strCacheMap.put(key, value);
    }

    public void putObjectInCache(String key, Object value) {
        objCacheMap.put(key, value);
    }

    public String getStringFromCache(String key) {
        if (strCacheMap.containsKey(key)) {
            return strCacheMap.get(key);
        }
        return "";
    }

    public Object getObjectFromCache(String key) {
        if (objCacheMap.containsKey(key)) {
            return objCacheMap.get(key);
        }
        return null;
    }

    public void putContentInCache(String key, Map<String, Object> value) {
        contentCacheMap.put(key, value);
    }

    public Map<String, Object> getContentFromCache(String key) {
        if (contentCacheMap.containsKey(key)) {
            return contentCacheMap.get(key);
        }
        return null;
    }
}

