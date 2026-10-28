package com.fan.fanaiagent.platform;

import okhttp3.*;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;

/** DNS validation is installed on the actual client connection, not just a preflight lookup. */
@Component
public class PublicHttp {
    private final OkHttpClient client=new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .dns(host->{var ips=InetAddress.getAllByName(host);for(var ip:ips) if(!isPublic(ip)) throw new UnknownHostException("非公网目标");return List.of(ips);})
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(Duration.ofSeconds(8)).readTimeout(Duration.ofSeconds(15)).callTimeout(Duration.ofSeconds(20)).build();
    public record Result(byte[] body,String type,String url) {}
    public static boolean isPublic(InetAddress address) {
        if(address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b=address.getAddress();int x=b[0]&255,y=b[1]&255;
        if(b.length==16) return (x&0xe0)==0x20 && !(x==0x20 && y==0x02) &&
            !(x==0x20 && y==0x01 && ((b[2]==0 && b[3]==0) || ((b[2]&255)==0x0d && (b[3]&255)==0xb8)));
        return x!=0 && x!=10 && x!=127 && x<224 && !(x==100 && y>=64 && y<=127) &&
            !(x==169 && y==254) && !(x==172 && y>=16 && y<=31) && !(x==192 && (y==168 || y==0 || y==2)) &&
            !(x==198 && (y==18 || y==19 || y==51)) && !(x==203 && y==0);
    }
    public static HttpUrl validate(String url) {
        HttpUrl parsed=HttpUrl.parse(url);
        if(parsed==null || !Set.of("http","https").contains(parsed.scheme()) || !Set.of(80,443).contains(parsed.port()) ||
            !parsed.username().isEmpty() || !parsed.password().isEmpty() || parsed.host().endsWith(".local")) throw new IllegalArgumentException("仅支持公开HTTP地址");
        return parsed;
    }
    public Result get(String url,int maximum) throws IOException {
        HttpUrl current=validate(url);
        for(int redirects=0;redirects<=3;redirects++) {
            try(Response response=client.newCall(new Request.Builder().url(current).header("User-Agent","FanAI/2.0").build()).execute()) {
                if(response.code()>=300 && response.code()<400) {
                    String location=response.header("Location");HttpUrl next=location==null?null:current.resolve(location);
                    if(next==null) throw new IOException("无效重定向");current=validate(next.toString());continue;
                }
                if(!response.isSuccessful() || response.body()==null) throw new IOException("网络请求未成功");
                if(response.body().contentLength()>maximum) throw new IOException("资源超出大小限制");
                byte[] bytes=response.body().byteStream().readNBytes(maximum+1);
                if(bytes.length>maximum) throw new IOException("资源超出大小限制");
                return new Result(bytes,response.header("Content-Type","application/octet-stream"),current.toString());
            }
        }
        throw new IOException("重定向次数超限");
    }
}
