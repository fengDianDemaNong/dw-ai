package com.dwai.lineage.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * 文件操作工具类
 *
 * @author wang
 * @createTime 2025/3/27 11:40
 */
public final class FileUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileUtils.class);

    // 私有构造方法防止实例化
    private FileUtils() {
        throw new AssertionError("Cannot instantiate utility class");
    }

    /**
     * 从类路径读取文件内容为字符串（UTF-8编码）
     *
     * @param filePath 类路径相对路径，如 "data/example.json"
     * @return 文件内容字符串
     * @throws IOException 当文件不存在或读取失败时抛出
     */
    public static String readClasspathFile(String filePath) throws IOException {
        try (InputStream inputStream = getClasspathResource(filePath);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            return reader.lines().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            LOGGER.error("读取类路径文件失败: {}", filePath, e);
            throw new IOException("文件读取失败: " + filePath, e);
        }
    }

    /**
     * 安全读取类路径文件（遇到异常时返回空字符串）
     *
     * @param filePath 类路径相对路径
     * @return 文件内容或空字符串
     */
    public static String readClasspathFileSafely(String filePath) {
        try {
            return readClasspathFile(filePath);
        } catch (IOException e) {
            LOGGER.warn("安全读取文件失败，返回空内容", e);
            return "";
        }
    }

    /**
     * 获取类路径资源输入流
     */
    private static InputStream getClasspathResource(String filePath) throws IOException {
        InputStream inputStream = FileUtils.class.getClassLoader().getResourceAsStream(filePath);
        if (inputStream == null) {
            throw new FileNotFoundException("类路径文件不存在: " + filePath);
        }
        return inputStream;
    }
}
