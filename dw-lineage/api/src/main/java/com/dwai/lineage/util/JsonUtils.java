package com.dwai.lineage.util;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

import java.io.IOException;
import java.util.*;

public class JsonUtils {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public JsonUtils() {
    }

    public static ObjectMapper getInstance() {
        return objectMapper;
    }

    public static String toJSONString(Object obj) throws IOException {
        return objectMapper.writeValueAsString(obj);
    }

    public static String toJSONStringIgnoreNull(Object obj) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(Include.NON_NULL);
        return mapper.writeValueAsString(obj);
    }

    public static <T> T toJavaObject(String jsonString, Class<T> clazz) throws IOException {
        objectMapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
        return objectMapper.readValue(jsonString, clazz);
    }

    public static <T> Map<String, Object> toJavaMap(String jsonString) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(Include.NON_NULL);
        return (Map) mapper.readValue(jsonString, Map.class);
    }

    public static <T> Map<String, T> toJavaMap(String jsonString, Class<T> clazz) throws IOException {
        Map<String, Map<String, T>> map = (Map) objectMapper.readValue(jsonString, new TypeReference<Map<String, Map<String, T>>>() {
        });
        Map<String, T> result = new HashMap();
        Iterator var4 = map.entrySet().iterator();

        while (var4.hasNext()) {
            Map.Entry<String, Map<String, T>> entry = (Map.Entry) var4.next();
            result.put((String) entry.getKey(), map2pojo((Map) entry.getValue(), clazz));
        }

        return result;
    }

    public static Map<String, Object> toJavaMapDeeply(String json) throws IOException {
        return json2MapRecursion(json, objectMapper);
    }

    private static List<Object> json2ListRecursion(String json, ObjectMapper mapper) throws IOException {
        if (json == null) {
            return null;
        } else {
            List<Object> list = (List) mapper.readValue(json, List.class);
            Iterator var3 = list.iterator();

            while (var3.hasNext()) {
                Object obj = var3.next();
                if (obj != null && obj instanceof String) {
                    String str = (String) obj;
                    if (str.startsWith("[")) {
                        json2ListRecursion(str, mapper);
                    } else if (obj.toString().startsWith("{")) {
                        json2MapRecursion(str, mapper);
                    }
                }
            }

            return list;
        }
    }

    private static Map<String, Object> json2MapRecursion(String json, ObjectMapper mapper) throws IOException {
        if (json == null) {
            return null;
        } else {
            Map<String, Object> map = (Map) mapper.readValue(json, Map.class);
            Iterator var3 = map.entrySet().iterator();

            while (var3.hasNext()) {
                Map.Entry<String, Object> entry = (Map.Entry) var3.next();
                Object obj = entry.getValue();
                if (obj != null && obj instanceof String) {
                    String str = (String) obj;
                    if (str.startsWith("[")) {
                        List<?> list = json2ListRecursion(str, mapper);
                        map.put((String) entry.getKey(), list);
                    } else if (str.startsWith("{")) {
                        Map<String, Object> mapRecursion = json2MapRecursion(str, mapper);
                        map.put((String) entry.getKey(), mapRecursion);
                    }
                }
            }

            return map;
        }
    }

    public static <T> List<T> toJavaListObject(String jsonArrayStr, Class<T> clazz) throws IOException {
        JavaType javaType = toJavaType(ArrayList.class, clazz);
        List<T> list = (List) objectMapper.readValue(jsonArrayStr, javaType);
        return list;
    }

    public static JavaType toJavaType(Class<?> collectionClass, Class<?>... elementClasses) {
        return objectMapper.getTypeFactory().constructParametricType(collectionClass, elementClasses);
    }

    public static <T> T map2pojo(Map map, Class<T> clazz) {
        return objectMapper.convertValue(map, clazz);
    }

    static {
        objectMapper.registerModule(new Jdk8Module());
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        objectMapper.configure(Feature.ALLOW_SINGLE_QUOTES, true);
        objectMapper.configure(Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);
    }
}
