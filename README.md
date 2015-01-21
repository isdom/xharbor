xharbor
============

    http gateway (switch)

TODO:

  1、对转发业务增加 Netty 层面可获取到的TCP Inbound/Outbound 的传输字节数、流量(单位事件内的字节数)等监控信息，
     实现思路为: 通过较低的Handler获取到相关信息后，通过 Channel or Ctx's Attribute传递到 Flow 处，并在 BizMemo 中统一呈现

  2、~~增加JMX MBean 监控项，包括BytesPool、HttpStack 等~~

  3、考虑增加HTTP自定义头域，例如"X-Client-Type: Test"确定转发地址

  4、根据是否存在有效的转发规则，启动或停止 HttpGatewayServer 实例

  5、~~对于没有匹配的转发地址的客户端，需要增加JMX MBean指标进行监控这样的请求个数~~ 
  
  6、~~将计算路由的逻辑从HttpGatewayServer中迁移到RelayFlow中，路由计算所耗用的是异步事件线程池中的线程计算资源，
    可降低占用接收Client信息的 Netty 所管理线程池的计算开销~~
    
  ~~5，6 需求可合并起来实现。~~ 2015-01-18已实现

  7、~~增加 JMX ConnectServer using jmxmp protocol~~
  
  8、Routing 计算是否可以异步化？
  
  9、在有限次转发失败后，暂时屏蔽该达到转发失败次数上限的特定转发路由
  
  10、增加 URI 的 黑、白名单，均在 netty 接收线程池中进行，凡是匹配 黑名单正则表达式的，全部拒绝，或者 只有匹配白名单正则表达式的才予以处理。
      对于被拒绝的 URI 记录其总个数。

  11、根据转发的结果，实时对转发目的地URI进行打分；选择路由时，由实时的分数情况加权确定路由。
  
  12、对转发失败的HttpRequest，增加一定次数的重新转发，包括重新转发完整的HttpContent(s)。
  