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
  
  9、~~在有限次转发失败后，暂时屏蔽该达到转发失败次数上限的特定转发路由~~， 目前功能是 单次转发失败（连接失败）后，即标识为down机。
  
  10、增加 URI 的 黑、白名单，均在 netty 接收线程池中进行，凡是匹配 黑名单正则表达式的，全部拒绝，或者 只有匹配白名单正则表达式的才予以处理。
      对于被拒绝的 URI 记录其总个数。

  11、根据转发的结果，实时对转发目的地URI进行打分；选择路由时，由实时的分数情况加权确定路由。对于CONNECT_FAILURE的情况，URI down的判断标志
      应该全局共用(done)。
    
  12、对转发失败的HttpRequest，增加一定次数的重新转发，包括重新转发完整的HttpContent(s)。TODO 并记录重新转发次数。
  
  13、TODO 如转发成功，但返回得到的HttpResponse为异常状态码(4XX, 5XX)，则对转发路由进行权重降低？或者标志位该目的地的该接口无效。
  
  14、对已经标志为down机的服务，设置定时器动作进行定期重置down机标志为false，当有匹配业务需要转发时，则可再次重新尝试该路由目的地。
      让曾经down机服务在恢复后，有机会再次成为有效的转发目的地 (done)。
      
  15、增加 BeanShell via JMX 方式对 xharbor 的运行时刻 灵活控制。 
  
  16、对 NO_ROUTING 的HttpRequest不再注册一组独立的 MBean，而是统一到一个记录NoRouting的MBean中，String形式 或 Map 形式呈现，无效URI/请求次数 信息对
     减少无效MBean的无效信息呈现
     
  