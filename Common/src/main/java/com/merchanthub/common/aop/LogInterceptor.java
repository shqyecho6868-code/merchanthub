package com.merchanthub.common.aop;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

/**
 * Request/response logging AOP
 * <p>
 * Depends on AOP and the Servlet API, both optional in Common,
 * so it is only active in Servlet services that declare spring-boot-starter-aop.
 **/
@ConditionalOnClass(name = {"org.aspectj.lang.ProceedingJoinPoint", "jakarta.servlet.http.HttpServletRequest"})
@Aspect
@Component
@Slf4j
public class LogInterceptor {

    /**
     * Intercept the call
     */
    @Around("execution(* com.merchanthub..controller.*.*(..))")
    public Object doInterceptor(ProceedingJoinPoint point) throws Throwable {
        // Start timing
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        // Get the request path
        RequestAttributes requestAttributes = RequestContextHolder.currentRequestAttributes();
        HttpServletRequest httpServletRequest = ((ServletRequestAttributes) requestAttributes).getRequest();
        // Generate a unique request id
        String requestId = UUID.randomUUID().toString();
        String url = httpServletRequest.getRequestURI();
        // Get the request parameters
        Object[] args = point.getArgs();
        String reqParam = "[" + StringUtils.join(args, ", ") + "]";
        // Log the request
        log.info("request start，id: {}, path: {}, ip: {}, params: {}", requestId, url, httpServletRequest.getRemoteHost(), reqParam);
        // Invoke the original method
        Object result = point.proceed();
        // Log the response
        stopWatch.stop();
        long totalTimeMillis = stopWatch.getTotalTimeMillis();
        log.info("request end, id: {}, cost: {}ms", requestId, totalTimeMillis);
        return result;
    }
}