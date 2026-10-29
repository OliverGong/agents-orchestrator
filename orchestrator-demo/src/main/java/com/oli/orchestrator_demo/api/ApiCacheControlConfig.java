/**
 * @ClassName ApiCacheControlConfig
 * @Description Sends `Cache-Control: no-cache` on every /api/** response.
 *              The observability endpoints are point-in-time snapshots of the
 *              actor timelines and Micrometer registry: a run that just
 *              finished must show up on the next tab switch, but browsers
 *              heuristically cache unmarked GET responses and would serve a
 *              stale run list / replay. `no-cache` keeps caching (ETag
 *              revalidation) while forcing freshness checks.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ApiCacheControlConfig implements WebMvcConfigurer {

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new HeaderInterceptor()).addPathPatterns("/api/**");
  }

  private static final class HeaderInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
      response.setHeader("Cache-Control", "no-cache");
      return true;
    }
  }
}
