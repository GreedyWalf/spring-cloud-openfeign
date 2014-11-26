package org.springframework.cloud.netflix.zuul;

import com.netflix.zuul.http.ZuulServlet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.trace.TraceRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.netflix.zuul.filters.post.SendErrorFilter;
import org.springframework.cloud.netflix.zuul.filters.post.SendResponseFilter;
import org.springframework.cloud.netflix.zuul.filters.pre.DebugFilter;
import org.springframework.cloud.netflix.zuul.filters.pre.PreDecorationFilter;
import org.springframework.cloud.netflix.zuul.filters.pre.Servlet30WrapperFilter;
import org.springframework.cloud.netflix.zuul.filters.route.RibbonRoutingFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author Spencer Gibb
 */
@Configuration
@EnableConfigurationProperties()
@ConditionalOnClass(ZuulServlet.class)
@ConditionalOnExpression("${zuul.enabled:true}")
public class ZuulConfiguration {

    @Autowired(required=false)
    private TraceRepository traces;

    @Bean
    public ZuulProperties zuulProperties() {
        return new ZuulProperties();
    }

    @Bean
    public RouteLocator routes(){
        return new RouteLocator();
    }

    @Bean
    public ZuulController zuulController() {
        return new ZuulController();
    }

    @Bean
    public ZuulHandlerMapping zuulHandlerMapping() {
        return new ZuulHandlerMapping();
    }

    @Bean
    public FilterInitializer zuulFilterInitializer() {
        return new FilterInitializer();
    }

    // pre filters
    @Bean
    public DebugFilter debugFilter() {
        return new DebugFilter();
    }

    @Bean
    public PreDecorationFilter preDecorationFilter() {
        return new PreDecorationFilter();
    }

    @Bean
    public Servlet30WrapperFilter servlet30WrapperFilter() {
        return new Servlet30WrapperFilter();
    }

    // route filters
    @Bean
    public RibbonRoutingFilter ribbonRoutingFilter() {
        RibbonRoutingFilter filter = new RibbonRoutingFilter();
        if (traces!=null) {
            filter.setTraces(traces);
        }
        return filter;
    }

    // post filters
    @Bean
    public SendResponseFilter sendResponseFilter() {
        return new SendResponseFilter();
    }

    @Bean
    public SendErrorFilter sendErrorFilter() {
        return new SendErrorFilter();
    }
}