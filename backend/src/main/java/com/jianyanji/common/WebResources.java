package com.jianyanji.common;

import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.config.annotation.*;

/** 同源提供构建后的 Vue 页面和原始品牌素材，生产无需 Vite 或 Node 服务。 */
@Configuration
public class WebResources implements WebMvcConfigurer {
    /** 前端 dist 目录，可由 WEB_DIST 显式指定绝对路径。 */
    private final String webDir;
    /** 小程序公共素材目录，可由 ASSETS_DIR 显式指定绝对路径。 */
    private final String assetsDir;

    public WebResources(
            @Value("${jianyanji.web-dir}") String webDir,
            @Value("${jianyanji.assets-dir}") String assetsDir) {
        this.webDir = webDir;
        this.assetsDir = assetsDir;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations(
                        location(Paths.get(webDir, "assets").toString()), location(assetsDir));
        registry.addResourceHandler("/**").addResourceLocations(location(webDir));
    }

    private String location(String path) {
        return Paths.get(path).toAbsolutePath().normalize().toUri().toString() + "/";
    }
    /** 品牌后台及浏览器购买端入口，路径保持原约定。 */
    @Controller
    public static class Pages {
        @GetMapping({"/", "/admin"})
        public String index() {
            return "forward:/index.html";
        }
    }
}
