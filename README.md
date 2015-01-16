xharbor
============

    http gateway (switch)

TODO:

  1、对转发业务增加 Netty 层面可获取到的TCP Inbound/Outbound 的传输字节数、流量(单位事件内的字节数)等监控信息，
     实现思路为: 通过较低的Handler获取到相关信息后，通过 Channel or Ctx's Attribute传递到 Flow 处，并在 BizMemo 中统一呈现
     
  2、增加JMX MBean 监控项，包括BytesPool、HttpStack 等
  
  3、考虑增加HTTP自定义头域，例如"X-Client-Type: Test"确定转发地址
  
  4、根据是否存在有效的转发规则，启动或停止 HttpGatewayServer 实例
  
  5、对于没有匹配的转发地址的客户端，需要增加JMX MBean指标进行监控这样的请求个数
  
  6、将计算路由的逻辑从HttpGatewayServer中迁移到RelayFlow中，路由计算所耗用的是异步事件线程池中的线程计算资源，
    可降低占用接收Client信息的 Netty 所管理线程池的计算开销
    
  7、增加 JMX ConnectServer using jmxmp protocol
     