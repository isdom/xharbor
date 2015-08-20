#!/bin/sh

FINDNAME=$0
while [ -h $FINDNAME ] ; do FINDNAME=`ls -ld $FINDNAME | awk '{print $NF}'` ; done
SERVER_HOME=`echo $FINDNAME | sed -e 's@/[^/]*$@@'`
unset FINDNAME

if [ "$SERVER_HOME" = '.' ]; then
   SERVER_HOME=$(echo `pwd` | sed 's/\/bin//')
else
   SERVER_HOME=$(echo $SERVER_HOME | sed 's/\/bin//')
fi

if [ ! -d $SERVER_HOME/pids ]; then
    mkdir $SERVER_HOME/pids
fi

HEAP_MEMORY=1024m
PERM_MEMORY=128m
DIRECT_MEMORY=512m
# JMX_PORT=8002
SERVER_NAME=xharbor

PIDFILE=$SERVER_HOME/pids/$SERVER_NAME.pid

case $1 in
start)
    echo  "Starting $SERVER_NAME ... "

    # -Djava.nio.channels.spi.SelectorProvider=sun.nio.ch.EPollSelectorProvider remove this flag for jdk 1.7 & mac
    JAVA_OPTS="-server -XX:+HeapDumpOnOutOfMemoryError"
    # JAVA_OPTS="${JAVA_OPTS} -Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false"
    
    shift
    ARGS=($*)
    for ((i=0; i<${#ARGS[@]}; i++)); do
        case "${ARGS[$i]}" in
        -D*)    JAVA_OPTS="${JAVA_OPTS} ${ARGS[$i]}" ;;
        -Heap*) HEAP_MEMORY="${ARGS[$i+1]}" ;;
        -Perm*) PERM_MEMORY="${ARGS[$i+1]}" ;;
        -Direct*) DIRECT_MEMORY="${ARGS[$i+1]}" ;;
        -JmxPort*)  JMX_PORT="${ARGS[$i+1]}" ;;
        esac
    done
    # JAVA_OPTS="${JAVA_OPTS} -Dcom.sun.management.jmxremote.port=${JMX_PORT}"
    JAVA_OPTS="${JAVA_OPTS} -Xms${HEAP_MEMORY} -Xmx${HEAP_MEMORY} -XX:PermSize=${PERM_MEMORY} -XX:MaxPermSize=${PERM_MEMORY}  "
    JAVA_OPTS="${JAVA_OPTS} -XX:MaxDirectMemorySize=${DIRECT_MEMORY}"
    JAVA_OPTS="${JAVA_OPTS} -XX:+AlwaysPreTouch"
    JAVA_OPTS="${JAVA_OPTS} -Drx.ring-buffer.size=1024000"
    JAVA_OPTS="${JAVA_OPTS} -Dio.netty.allocator.type=pooled"
    JAVA_OPTS="${JAVA_OPTS} -Dio.netty.leakDetectionLevel=PARANOID"
    JAVA_OPTS="${JAVA_OPTS} -Duser.dir=${SERVER_HOME} -Dapp.name=$SERVER_NAME"
    echo "start jvm args ${JAVA_OPTS}"
    nohup java $JAVA_OPTS -jar ${SERVER_HOME}/bin/$SERVER_NAME.jar >/dev/null &
    echo $! > $PIDFILE
    echo STARTED
    ;;

stop)
    echo "Stopping $SERVER_NAME ... "
    if [ ! -f $PIDFILE ]
    then
        echo "error: could not find file $PIDFILE"
        exit 1
    else
        kill -9 $(cat $PIDFILE)
        rm $PIDFILE
        echo STOPPED
    fi
    ;;

restart)
    ./appctrl.sh stop
    sleep 1
    ./appctrl.sh start
    ;;

*)
    ;;
   
esac

exit 0
