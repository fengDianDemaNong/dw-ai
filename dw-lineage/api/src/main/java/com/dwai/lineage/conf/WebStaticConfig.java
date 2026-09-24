package com.dwai.lineage.conf;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 由后端直接托管前端静态资源，安装包因此只需启动一个进程，不必额外部署 nginx。
 *
 * <p>目录由 {@code web.static-dir} 指定，默认指向安装目录下的 {@code web}。
 * 该目录不存在时（例如开发环境只跑后端）不会影响启动。
 */
@Configuration
public class WebStaticConfig implements WebMvcConfigurer {

    @Value("${web.static-dir:./web}")
    private String staticDir;

    /**
     * 根路径显式转发到 index.html。
     *
     * <p>不能依赖下面的资源回落：ResourceHttpRequestHandler 对空路径会在进入
     * ResourceResolver <b>之前</b> 直接返回 null，自定义 resolver 根本不会被调用，
     * 表现为访问 "/" 报 500 而访问 "/any/path" 正常。
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = staticDir.endsWith("/") ? staticDir : staticDir + "/";

        // Vite 产出的文件名带内容哈希（index-b25701e6.js），内容变了文件名一定变，
        // 因此可以放心地让浏览器永久缓存，省掉每次刷新的一轮 304 往返。
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("file:" + location + "assets/")
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).immutable());

        // index.html 恰恰相反：它的文件名固定，内容却每次发布都变（引用的哈希文件名变了）。
        //
        // 之前这里什么缓存头都不发，只有 Last-Modified。缺少 Cache-Control / Expires 时，
        // 浏览器会按「启发式缓存」自行决定新鲜期（通常取资源已存在时长的 10%），
        // 于是重新部署后普通刷新仍会命中旧的 index.html —— 它引用的还是上一版的哈希文件名，
        // 表现为「后端明明更新了、页面却还是旧的，新加的入口凭空不见」。
        //
        // no-cache 不是不缓存，而是「可以存，但每次都必须回源校验」，
        // 配合 Last-Modified 走 304，代价只有一个 1KB 请求。
        registry.addResourceHandler("/**")
                .addResourceLocations("file:" + location)
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String path, Resource location) throws IOException {
                        Resource requested = resolveFile(path, location);
                        if (requested != null) {
                            return requested;
                        }
                        // 看起来像静态资源（带扩展名）却没找到，就如实返回 404。
                        // 若一律回落到 index.html，资源加载失败会伪装成 200 + HTML，
                        // 浏览器把 HTML 当 JS 解析后只会得到一个空白页面，极难排查。
                        if (looksLikeAsset(path)) {
                            return null;
                        }
                        // 前端是单页应用，其余未命中路径回落到 index.html，
                        // 否则刷新非根路由会 404。/api 由 Controller 优先匹配，不受影响。
                        Resource index = location.createRelative("index.html");
                        return index.exists() && index.isReadable() ? index : null;
                    }

                    /**
                     * 命中真实文件时才返回。
                     *
                     * <p>不能只判断 exists() && isReadable()：根路径 "/" 会被解析成 "."，
                     * 目录同样满足这两个条件，返回目录会让 ResourceHttpRequestHandler 抛异常。
                     */
                    /** 最后一段带扩展名的，视为静态资源而非前端路由。 */
                    private boolean looksLikeAsset(String path) {
                        if (path == null || path.isBlank()) {
                            return false;
                        }
                        int slash = path.lastIndexOf('/');
                        String last = slash < 0 ? path : path.substring(slash + 1);
                        return last.indexOf('.') > 0;
                    }

                    private Resource resolveFile(String path, Resource location) {
                        if (path == null || path.isBlank() || ".".equals(path) || path.endsWith("/")) {
                            return null;
                        }
                        try {
                            Resource candidate = location.createRelative(path);
                            if (candidate.exists() && candidate.isReadable() && candidate.getFile().isFile()) {
                                return candidate;
                            }
                        } catch (IOException ignored) {
                            // 无法定位为文件时按未命中处理，回落到 index.html
                        }
                        return null;
                    }
                });
    }
}
