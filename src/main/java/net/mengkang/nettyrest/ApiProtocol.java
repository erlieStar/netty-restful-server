package net.mengkang.nettyrest;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.multipart.MixedAttribute;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * convert the netty HttpRequest object to a api protocol object
 */
public class ApiProtocol {

    private static final Logger logger = LoggerFactory.getLogger(ApiProtocol.class);

    private int                       build      = 101;
    private String                    version    = "1.0";
    private String                    channel    = "mengkang.net";
    private String                    geo        = null;
    private String                    clientIP   = null;
    private String                    serverIP   = null;
    private String                    api        = null;
    private String                    endpoint   = null;
    private String                    auth       = null;
    private int                       offset     = 0;
    private int                       limit      = 10;
    private HttpMethod                method     = HttpMethod.GET;
    private Map<String, List<String>> parameters = new HashMap<String, List<String>>(); // get 和 post 的键值对都存储在这里
    private String                    postBody   = null; // post 请求时的非键值对内容

    public int getBuild() {
        return build;
    }

    public String getVersion() {
        return version;
    }

    public String getChannel() {
        return channel;
    }

    public String getGeo() {
        return geo;
    }

    public String getClientIP() {
        return clientIP;
    }

    public String getServerIP() {
        return serverIP;
    }

    public String getApi() {
        return api;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getAuth() {
        return auth;
    }

    public int getOffset() {
        return offset;
    }

    public int getLimit() {
        return limit;
    }

    public HttpMethod getMethod() {
        return method;
    }

    public Map<String, List<String>> getParameters() {
        return parameters;
    }

    // 访问的url是http://localhost:8080/user/1/album/10?build=103
    // /user/:uid/album/:aid
    // 打印出来的log为
    // key uid, params [1]
    // key aid, params [10]
    private void addParameter(String key, String param) {
        List<String> params = new ArrayList<>();
        params.add(param);
        this.parameters.put(key, params);
        logger.info("key {}, params {}",key, params);
    }

    public String getPostBody() {
        return postBody;
    }

    /**
     * api protocol object initializer
     *
     * @param ctx
     * @param msg
     * @return
     */
    public ApiProtocol(ChannelHandlerContext ctx, Object msg) {
        HttpRequest req = (HttpRequest) msg;

        String uri = req.uri();
        if (uri.length() <= 0) {
            return;
        }

        logger.info(uri);
        this.method = req.method();

        // 获取路径匹配符中的参数
        parseEndpoint(uri);
        // 设置客户端ip和服务端ip
        setIp(ctx, req);
        // 对url进行decode（解码）
        queryStringHandler(uri);
        // 如果是post请求获取post请求的值
        requestParametersHandler(req);
        requestBodyHandler(msg);

        // 将parameters中有的参数设置到ApiProtocol这个对象中
        // 并从parameters中移除
        if (this.parameters.size() > 0) {
            setFields();
        }
    }

    /**
     * parse endpoint
     * match api name and api regex and add resource params into {@link #parameters}
     *
     * @param uri
     */
    private void parseEndpoint(String uri) {

        // 这个是获取url地址
        // 如：http://localhost:8080/user/1/album/10?build=103&test=100
        // endpoint为http://localhost:8080/user/1/album/10
        // 感觉if语句没有必要
        String endpoint = uri.split("\\?")[0];
        if (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length());
        }
        this.endpoint = endpoint;

        Set<Map.Entry<String, Api>> set = ApiRoute.apiMap.entrySet();

        for (Map.Entry<String, Api> entry : set) {
            Api api = entry.getValue();
            // 简单工厂创建正则表达式，^和$是匹配字符串的开头和结尾
            Pattern pattern = Pattern.compile("^" + api.getRegex() + "$");
            Matcher matcher = pattern.matcher(endpoint);
            // url是否匹配到正则表达式
            if (matcher.find()) {
                this.api = api.getName();
                // 返回匹配到的组数
                if (matcher.groupCount() > 0) {
                    for (int i = 0; i < matcher.groupCount(); i++) {
                        // matcher.group返回第i+1组匹配到到的字符串
                        addParameter(api.getParameterNames().get(i), matcher.group(i + 1));
                    }
                }
                break;
            }
        }

    }

    private void setIp(ChannelHandlerContext ctx, HttpRequest req) {
        // 文档参考地址：https://baike.baidu.com/item/X-Forwarded-For/3593639
        // X-Forwarded-For:简称XFF头，它代表客户端，也就是HTTP的请求端真实的IP，
        // 只有在通过了HTTP 代理或者负载均衡服务器时才会添加该项
        String clientIP = (String) req.headers().get("X-Forwarded-For");
        if (clientIP == null) {
            InetSocketAddress remoteSocket = (InetSocketAddress) ctx.channel().remoteAddress();
            clientIP = remoteSocket.getAddress().getHostAddress();
        }
        this.clientIP = clientIP;

        InetSocketAddress serverSocket = (InetSocketAddress) ctx.channel().localAddress();
        this.serverIP = serverSocket.getAddress().getHostAddress();
    }

    /**
     * set this class field's value by {@link #parameters}
     * <p>
     * the code improved log in my blog <a href="http://mengkang.net/614.html">http://mengkang.net/614.html</>
     */
    private void setFields() {
        Field[] fields = this.getClass().getDeclaredFields();

        for (int i = 0, length = fields.length; i < length; i++) {
            Field field = fields[i];
            String filedName = field.getName();

            // 遍历自己的属性名字
            if (filedName.equals("logger")
                    || filedName.equals("method")
                    || filedName.equals("parameters")
                    || filedName.equals("postBody")) {
                continue;
            }

            if (!this.parameters.containsKey(filedName)) {
                continue;
            }

            String fieldType = field.getType().getName();
            field.setAccessible(true);
            try {
                if (fieldType.endsWith("int")) {
                    field.set(this, Integer.parseInt(this.parameters.get(filedName).get(0)));
                } else if (fieldType.endsWith("float")) {
                    field.set(this, Float.parseFloat(this.parameters.get(filedName).get(0)));
                } else if (fieldType.endsWith("long")) {
                    field.set(this, Long.parseLong(this.parameters.get(filedName).get(0)));
                } else if (fieldType.endsWith("double")) {
                    field.set(this, Double.parseDouble(this.parameters.get(filedName).get(0)));
                } else {
                    field.set(this, this.parameters.get(filedName).get(0));
                }
            } catch (NumberFormatException | IllegalAccessException e) {
                logger.error(e.getMessage());
            }

            this.parameters.remove(filedName);
        }
    }

    /**
     * queryString encode, put all the query key value in {@link #parameters}
     * <p>
     * use netty's {@link QueryStringDecoder} replace my bad encode method <a href="http://mengkang.net/587.html">http://mengkang.net/587.html</a>
     *
     * @param uri
     */
    private void queryStringHandler(String uri) {

        QueryStringDecoder queryStringDecoder = new QueryStringDecoder(uri);
        if (queryStringDecoder.parameters().size() > 0) {
            // 访问的url是http://localhost:8080/user/1/album/10?build=103
            // queryStringDecoder.parameters() {build=[103]}
            // uid=[1],aid=[10],build=[103]
            logger.info("queryStringDecoder.parameters() {}", queryStringDecoder.parameters());
            this.parameters.putAll(queryStringDecoder.parameters());
        }
    }

    /**
     * request parameters put in {@link #parameters}
     *
     * @param req
     */
    private void requestParametersHandler(HttpRequest req) {
        if (req.method().equals(HttpMethod.POST)) {
            HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(req);
            try {
                List<InterfaceHttpData> postList = decoder.getBodyHttpDatas();
                for (InterfaceHttpData data : postList) {
                    List<String> values = new ArrayList<String>();
                    MixedAttribute value = (MixedAttribute) data;
                    value.setCharset(CharsetUtil.UTF_8);
                    values.add(value.getValue());
                    this.parameters.put(data.getName(), values);
                }
            } catch (Exception e) {
                logger.error(e.getMessage());
            }
        }
    }

    /**
     * request body put in {@link #postBody}
     *
     * @param msg
     */
    private void requestBodyHandler(Object msg) {
        if (msg instanceof HttpContent) {
            HttpContent httpContent = (HttpContent) msg;
            ByteBuf content = httpContent.content();
            StringBuilder buf = new StringBuilder();
            buf.append(content.toString(CharsetUtil.UTF_8));
            this.postBody = buf.toString();
        }
    }

}
