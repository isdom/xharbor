xharbor
============

    http gateway (switch)

TODO:

  1、对转发业务增加 Netty 层面可获取到的TCP Inbound/Outbound 的传输字节数、流量(单位事件内的字节数)等监控信息，
     实现思路为: 通过较低的Handler获取到相关信息后，通过 Channel or Ctx's Attribute传递到 Flow 处，并在 BizMemo 中统一呈现
     