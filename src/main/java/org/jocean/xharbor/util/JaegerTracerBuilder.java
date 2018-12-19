package org.jocean.xharbor.util;

import org.springframework.beans.factory.annotation.Value;

import io.opentracing.Tracer;

public class JaegerTracerBuilder {

    @Value("${servicename}")
    String _serviceName;

    @Value("${endpoint}")
    String _endpoint;

    @Value("${username}")
    String _username;

    @Value("${password}")
    String _password;

    public Tracer build() {
        final io.jaegertracing.Configuration config = new io.jaegertracing.Configuration(_serviceName);
        final io.jaegertracing.Configuration.SenderConfiguration sender = new io.jaegertracing.Configuration.SenderConfiguration();
        /**
           *  从链路追踪控制台获取网关（Endpoint）、用户名、密码（userkey）
           *  第一次运行时，请设置当前用户的网关、用户名、密码（userkey）
        */
        sender.withEndpoint(_endpoint);
        // 设置用户名
        sender.withAuthUsername(_username);
        // 设置密码（userkey）
        sender.withAuthPassword(_password);

        config.withSampler(new io.jaegertracing.Configuration.SamplerConfiguration().withType("const").withParam(1));
        config.withReporter(new io.jaegertracing.Configuration.ReporterConfiguration().withSender(sender).withMaxQueueSize(10000));
        return config.getTracer();
    }
}
