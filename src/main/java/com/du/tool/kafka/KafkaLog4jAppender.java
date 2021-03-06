package com.du.tool.kafka;

import lombok.Data;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.helpers.LogLog;
import org.apache.log4j.spi.LoggingEvent;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

@Data
public class KafkaLog4jAppender extends AppenderSkeleton {
    private String brokerList;
    private String topic;
    private String compressionType;
    private String securityProtocol;
    private String sslTruststoreLocation;
    private String sslTruststorePassword;
    private String sslKeystoreType;
    private String sslKeystoreLocation;
    private String sslKeystorePassword;
    private String saslKerberosServiceName;
    private String clientJaasConfPath;
    private String kerb5ConfPath;
    private Integer maxBlockMs;
    private int retries = Integer.MAX_VALUE;
    private int requiredNumAcks = 1;
    private int deliveryTimeoutMs = 120000;
    private boolean ignoreExceptions = true;
    private boolean syncSend;
    private String appName;
    private Producer<byte[], byte[]> producer;

    public int getMaxBlockMs()
    {
        return this.maxBlockMs.intValue();
    }

    public void setMaxBlockMs(int maxBlockMs)
    {
        this.maxBlockMs = Integer.valueOf(maxBlockMs);
    }

    public void activateOptions() {
        Properties props = new Properties();
        if (this.brokerList != null) {
            props.put("bootstrap.servers", this.brokerList);
        }
        if (props.isEmpty()) {
            throw new ConfigException("The bootstrap servers property should be specified");
        }
        if (this.topic == null) {
            throw new ConfigException("Topic must be specified by the Kafka log4j appender");
        }
        if (this.compressionType != null) {
            props.put("compression.type", this.compressionType);
        }
        props.put("acks", Integer.toString(this.requiredNumAcks));
        props.put("retries", Integer.valueOf(this.retries));
        props.put("delivery.timeout.ms", Integer.valueOf(this.deliveryTimeoutMs));
        if (this.securityProtocol != null) {
            props.put("security.protocol", this.securityProtocol);
        }
        if ((this.securityProtocol != null) && (this.securityProtocol.contains("SSL")) && (this.sslTruststoreLocation != null) && (this.sslTruststorePassword != null)) {
            props.put("ssl.truststore.location", this.sslTruststoreLocation);
            props.put("ssl.truststore.password", this.sslTruststorePassword);
            if ((this.sslKeystoreType != null) && (this.sslKeystoreLocation != null) && (this.sslKeystorePassword != null))
            {
                props.put("ssl.keystore.type", this.sslKeystoreType);
                props.put("ssl.keystore.location", this.sslKeystoreLocation);
                props.put("ssl.keystore.password", this.sslKeystorePassword);
            }
        }
        if ((this.securityProtocol != null) && (this.securityProtocol.contains("SASL")) && (this.saslKerberosServiceName != null) && (this.clientJaasConfPath != null)) {
            props.put("sasl.kerberos.service.name", this.saslKerberosServiceName);
            System.setProperty("java.security.auth.login.config", this.clientJaasConfPath);
            if (this.kerb5ConfPath != null) {
                System.setProperty("java.security.krb5.conf", this.kerb5ConfPath);
            }
        }
        if (this.maxBlockMs != null) {
            props.put("max.block.ms", this.maxBlockMs);
        }
        props.put("key.serializer", ByteArraySerializer.class.getName());
        props.put("value.serializer", ByteArraySerializer.class.getName());
        this.producer = getKafkaProducer(props);
        LogLog.debug("Kafka producer connected to " + this.brokerList);
        LogLog.debug("Logging for topic: " + this.topic);
    }

    protected Producer<byte[], byte[]> getKafkaProducer(Properties props)
    {
        return new KafkaProducer(props);
    }

    protected void append(LoggingEvent event) {
        String message = subAppend(event);
        LogLog.debug("[" + new Date(event.getTimeStamp()) + "]" + message);
        Future<RecordMetadata> response = this.producer.send(
                new ProducerRecord(this.topic, message.getBytes(StandardCharsets.UTF_8)));
        if (this.syncSend) {
            try {
                response.get();
            } catch (InterruptedException|ExecutionException ex) {
                if (!this.ignoreExceptions) {
                    throw new RuntimeException(ex);
                }
                LogLog.debug("Exception while getting response", ex);
            }
        }
    }

    /**
     * 获取与拼装消息
     * @param event
     * @return
     */
    private String subAppend(LoggingEvent event) {
        String eventStr = this.layout == null ? event.getRenderedMessage() : this.layout.format(event);
        EventLogEntry eventLogEntry = new EventLogEntry();
        try {
            InetAddress inetAddress = InetAddress.getLocalHost();
            eventLogEntry.setHostName(inetAddress.getHostName());
            eventLogEntry.setAddress(inetAddress.getHostAddress());
        } catch (Exception e) {
            LogLog.error("获取数据所在节点ip和主机名出错", e);
        }finally {
            try{
                String stacktraceToOneLineString = stacktraceToOneLineString(event.getThrowableInformation().getThrowable(),2000);
                eventLogEntry.setThrowableInfo(stacktraceToOneLineString);
            }catch (Exception e){
                eventLogEntry.setThrowableInfo("-");
            }finally {
                eventLogEntry.setEventId(UUID.randomUUID().toString());
                eventLogEntry.setEventTime(System.currentTimeMillis());
                eventLogEntry.setEventChannel(this.appName);
                eventLogEntry.setCategoryName(eventStr);
                eventLogEntry.setFqnOfCategoryClass(event.fqnOfCategoryClass);
                eventLogEntry.setLevel(event.getLevel().toString());
                eventLogEntry.setMessage(event.getMessage().toString());
                eventLogEntry.setThreadName(event.getThreadName());
                eventLogEntry.setTimeStamp(event.timeStamp);
            }
        }
        return eventLogEntry.toString();
    }

    public void close() {
        if (!this.closed) {
            this.closed = true;
            this.producer.close();
        }
    }

    public boolean requiresLayout() {
        return true;
    }

    private static final char TAB = '	';
    private static final char CR = '\r';
    private static final char LF = '\n';
    private static final String SPACE = " ";
    private static final String EMPTY = "";

    /**
     * 堆栈转为单行完整字符串
     *
     * @param throwable 异常对象
     * @param limit 限制最大长度
     * @return 堆栈转为的字符串
     */
    private static String stacktraceToOneLineString(Throwable throwable, int limit) {
        Map<Character, String> replaceCharToStrMap = new HashMap<>();
        replaceCharToStrMap.put(CR, SPACE);
        replaceCharToStrMap.put(LF, SPACE);
        replaceCharToStrMap.put(TAB, SPACE);
        return stacktraceToString(throwable, limit, replaceCharToStrMap);
    }


    /**
     * 堆栈转为完整字符串
     *
     * @param throwable 异常对象
     * @param limit 限制最大长度
     * @param replaceCharToStrMap 替换字符为指定字符串
     * @return 堆栈转为的字符串
     */
    private static String stacktraceToString(Throwable throwable, int limit, Map<Character, String> replaceCharToStrMap) {
        final OutputStream baos = new ByteArrayOutputStream();
        throwable.printStackTrace(new PrintStream(baos));
        String exceptionStr = baos.toString();
        int length = exceptionStr.length();
        if (limit > 0 && limit < length) {
            length = limit;
        }

        if (!replaceCharToStrMap.isEmpty()) {
            final StringBuilder sb = new StringBuilder();
            char c;
            String value;
            for (int i = 0; i < length; i++) {
                c = exceptionStr.charAt(i);
                value = replaceCharToStrMap.get(c);
                if (null != value) {
                    sb.append(value);
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        } else {
            return sub(exceptionStr,0, limit);
        }
    }

    /**
     * 改进JDK subString<br>
     * index从0开始计算，最后一个字符为-1<br>
     * 如果from和to位置一样，返回 "" <br>
     * 如果from或to为负数，则按照length从后向前数位置，如果绝对值大于字符串长度，则from归到0，to归到length<br>
     * 如果经过修正的index中from大于to，则互换from和to example: <br>
     * abcdefgh 2 3 =》 c <br>
     * abcdefgh 2 -3 =》 cde <br>
     *
     * @param str String
     * @param fromIndex 开始的index（包括）
     * @param toIndex 结束的index（不包括）
     * @return 字串
     */
    private static String sub(CharSequence str, int fromIndex, int toIndex) {
        if (isEmpty(str)) {
            return str(str);
        }
        int len = str.length();

        if (fromIndex < 0) {
            fromIndex = len + fromIndex;
            if (fromIndex < 0) {
                fromIndex = 0;
            }
        } else if (fromIndex > len) {
            fromIndex = len;
        }

        if (toIndex < 0) {
            toIndex = len + toIndex;
            if (toIndex < 0) {
                toIndex = len;
            }
        } else if (toIndex > len) {
            toIndex = len;
        }

        if (toIndex < fromIndex) {
            int tmp = fromIndex;
            fromIndex = toIndex;
            toIndex = tmp;
        }

        if (fromIndex == toIndex) {
            return EMPTY;
        }

        return str.toString().substring(fromIndex, toIndex);
    }

    /**
     * {@link CharSequence} 转为字符串，null安全
     *
     * @param cs {@link CharSequence}
     * @return 字符串
     */
    private static String str(CharSequence cs) {
        return null == cs ? null : cs.toString();
    }

    /**
     * 字符串是否为空，空的定义如下:<br>
     * 1、为null <br>
     * 2、为""<br>
     *
     * @param str 被检测的字符串
     * @return 是否为空
     */
    private static boolean isEmpty(CharSequence str) {
        return str == null || str.length() == 0;
    }
}