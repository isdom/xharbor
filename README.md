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
  
  // TODO
  8、Routing 计算是否可以异步化？可行，改进Router的接口定义，允许较为耗时的 routing 动作 异步化进行，在RelayFlow中增加"ROUTING"状态, 
    标识 异步化计算路由的阶段。
  
  9、~~在有限次转发失败后，暂时屏蔽该达到转发失败次数上限的特定转发路由~~， 目前功能是 单次转发失败（连接失败）后，即标识为down机。
  
  10、增加 URI 的 黑、白名单，均在 netty 接收线程池中进行，凡是匹配 黑名单正则表达式的，全部拒绝，或者 只有匹配白名单正则表达式的才予以处理。
      对于被拒绝的 URI 记录其总个数。

  11、根据转发的结果，实时对转发目的地URI进行打分；选择路由时，由实时的分数情况加权确定路由。对于CONNECT_FAILURE的情况，URI down的判断标志
      应该全局共用(done)。
    
  12、对转发失败的HttpRequest，增加一定次数的重新转发，包括重新转发完整的HttpContent(s)。 并记录重新转发次数。(done, 记录为 RELAY_RETRY result)
  
  13、如转发成功，但返回得到的HttpResponse为异常状态码(4XX, 5XX)，则对转发路由进行权重降低？或者标识该目的地的特定接口(API)无效。
      (done, 增加 Result(HTTP_CLIENT_ERROR/HTTP_SERVER_ERROR)，并根据这两个Result结果，标识特定接口无效，并尝试再次转发,
      并增加了开关控制是否启用该 检查特性)
  
  14、对已经标志为down机的服务，设置定时器动作进行定期重置down机标志为false，当有匹配业务需要转发时，则可再次重新尝试该路由目的地。
      让曾经down机服务在恢复后，有机会再次成为有效的转发目的地 (done)。
      
  15、增加 BeanShell via JMX 方式对 xharbor 的运行时刻 灵活控制。 
  
  16、对 NO_ROUTING 的HttpRequest不再注册一组独立的 MBean，而是统一到一个记录NoRouting的MBean中，String形式 或 Map 形式呈现，无效URI/请求次数 信息对
     减少无效MBean的无效信息呈现 (done)
     
  17、尽可能将应用的配置项迁移到ZooKeeper中，可以采用实例ID在ZK上区分全局配置项 和 实例特定配置项，每个配置项一个条目，方便独立修改，参考
     RulesZKUpdater的实现方式
     
  18、从 netty 接收到 http request 即开始计时(StopWatch)，逼近 ROUTING 耗时统计的起始时间点
  
  19、从 xharbor 通过 REST API的方式下载各类统计信息的 Excel(CSV)，统计内容可以包括：接口调用时长、次数、noRouting URI，流量大小 等
  
  20、~~将RulesZKUpdater & AUPZKUpdater 的实现抽象出公共 ZK's Updater with CopyOnWrite。~~ (done)
  
  21、对 健康检查 信令 在 RelayFlow 中做单独处理，包括 匹配的 method/path，返回状态码，消息体 等。(done)
  
  22、统计显示 NOROUTING 的对端 IP 信息 (peer.ip.addr)
  
  23、~~manifest/MANIFEST.MF 中的 ClassPath 指向外部路径，实现 在公共位置 保存 logback.xml、*.properties 的特性，AppServer 升级时，不用拷贝和迁移
      配置文件（参考 confluence、jira 的做法）~~ (done, 2015-02-04 在 MANIFEST.MF中, 根据目前现网部署模式将 Class-Path 改进成了 ../../etc/)  (done)
      
  24、基于 JMX 的命令行，可用 ObjectName 的部分匹配模式，查询出 字段超过一定数值的 MBean。
  
  25、将各级转发信令的统计信息MBean 合并在一个或有限个 MBean中，用类似 Map(OpenMBean TabularData) 的方式呈现出来。并进行 Excel / CSV 格式的信息输出。
  
  26、~~简化转发日志输出，到达单个转发输出单条日志。~~ (done, 2015-02-05)
  
  27、用 "hostname/username" 对运行的JVM实例进行区分。用于在ZK的配置树中进行实例区分。
  
  28、ZK配置树的属性结构可以使 公共配置 + 实例特定配置 组合。 eg：
       -----+-------------
            |
            +------------(公共配置)
            |
            +------------ host1/user1 +-----------
                                      |
                                      +----------(实例特定配置)
                                      
  29、relayAgent的创建也和HttpGateServer一样，由ZK进行配置，并在运行时刻可动态创建及销毁。并与 HttpGatewayServer 可动态匹配，通过命名？
  
  30、relayAgent 及 HttpGatewayServer 均可通过MBean进行监控，观察运行参数配置及运行情况。
  
  31、~~细化不能转发的信令的Http request's WARN日志详细信息，包括 HTTP 头等。~~ (done)
  
  32、~~在转发时，可取消部分path路径，对 HttpRequest做出一定程度的修改。~~ (done, 2015-02)
  
  33、~~在转发位置，设置认证环节，可在转发开始时，即对访问用户的合法性进行判断。~~(done, 2015-02-11) 包括 白名单，黑名单等。
  
  34、确认 FullHttpRequest 的HttpContent部分是否会被重复发送两次？
  
  35、将 RelayAgent 更名为 BusinessAgent ，并将 public RelayTask createRelayTask(final ChannelHandlerContext channelCtx, final HttpRequest httpRequest)

     中的 ChannelHandlerContext 进行接口包装，目的是 使得 业务处理流程 可以适应 Http 传输 和 TCP 多路复用的传输方式。
     
  36、将 RelayFlow中的传入参数
            final RelayMemo.Builder memoBuilder,
            final ServiceMemo       serviceMemo, 
            final GuideBuilder      guideBuilder,
            final boolean           checkResponseStatus,
            final boolean           showInfoLog,
            final HttpRequestTransformer.Builder transformerBuilder
      均考虑采用target中带入的方式传入 RelayFlow，这样 可以做到在 ZK Node 的Data中进行配置。
      先考虑将 checkResponseStatus & showInfoLog ZK 配置化

  37、jmxmp & jmxhtml 的端口配置 采用 ZK 方式启动，去掉 配置文件中的 jmxmp.port & jmxhtml.port 端口配置项。
     
  38、采用构造 Spring Context for ZK 的方式 构造可用功能单元（Unit）。 复用原有j2se中模式。(done)
  
  39、实现树状方式构造功能单元，子功能单元能继承父功能的Bean信息，eg：
       relay unit +--> acceptor unit: 6572
                  |
                  +--> acceptor unit: 8888
     (done)
     
  40、将AUP(ApplyUrlencode4Post)以Spring方式加载到系统，去掉 AUPOperator，可以考虑按照 
       relay unit +--> acceptor unit: 6572
                  |
                  +--> acceptor unit: 8888
                  |
                  +--> AUPunit 
     的模式加载。 (done)
     
  41、实现RouteRules的采用独立的Spring Ctx方式加载，替换RouteRulesOperator
  
  42、实现 ZK Spring 属性文件的引用模式，可通过特殊标记引用已经存在的Unit定义
  
  43、结合42 简化方式实现需求 28
  
  44、梳理 RelayFlow 单个Java文件: 将 HttpRequest / request's HttpContent 以及相关方法 组织在一起，etc (done)
  
  45、使用 Mock or Hamcrest to build Unit TestCase. (done)
  
  46、在BizStep中实现延迟初始化特性，当调用到接口时，才对其完整实例进行初始化，减少Flow初始化所占用的时间及内存消耗，
  	在某些场景下，一个状态转换图中的某些状态在单次流程中不会被跳转到，延迟初始化特性可有效减少整个流程的总时间耗用和内存耗用。
  	
  47、fix bug: httpRequestTransform 在retry时，会被执行多次，retry几次 就会 执行几次
  