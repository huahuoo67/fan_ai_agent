package com.fan.fanaiagent.platform;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.fan.fanaiagent.platform.PlatformTypes.*;

class BoundaryTest {
    @Test void allEightCapabilityCombinationsHaveExplicitTools() {
        for(int i=0;i<8;i++) {
            var c=new Capabilities((i&1)!=0,(i&2)!=0,(i&4)!=0);
            assertEquals(c.web(),PlatformModel.allowed("searchPublic",c));
            assertEquals(c.task(),PlatformModel.allowed("createPdf",c));
            assertEquals(c.task()&&c.web(),PlatformModel.allowed("downloadPublic",c));
            assertFalse(PlatformModel.allowed("terminal",c));assertFalse(PlatformModel.allowed("unknownMcp",c));
            assertEquals(c.love(),c.allows("LOVE"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"127.0.0.1","0.0.0.0","10.0.0.1","169.254.169.254","172.16.0.1","192.168.1.1","100.64.0.1","198.18.0.1","::1","fc00::1","fe80::1","2001:db8::1","2002:7f00:1::"})
    void privateAndSpecialNetworksAreBlocked(String ip) throws Exception {assertFalse(PublicHttp.isPublic(InetAddress.getByName(ip)));}
    @Test void normalPublicAddressesAllowed() throws Exception {assertTrue(PublicHttp.isPublic(InetAddress.getByName("8.8.8.8")));assertTrue(PublicHttp.isPublic(InetAddress.getByName("2606:4700:4700::1111")));}
    @ParameterizedTest @ValueSource(strings={"file:///etc/passwd","http://u:p@example.com","http://example.com:8123","gopher://example.com","http://host.local"})
    void invalidUrlsFailBeforeRequest(String url) {assertThrows(IllegalArgumentException.class,()->PublicHttp.validate(url));}
    @Test void onlyPublicTokensLeaveAutomatically() {
        assertEquals("上海 天气 今天",EgressPolicy.publicQuery("我叫张三，电话13800000000，今天上海天气如何"));
        assertFalse(EgressPolicy.validReviewedQuery("我的密码abc"));assertFalse(EgressPolicy.validReviewedQuery("联系13800000000"));
        assertEquals("",EgressPolicy.publicQuery("帮我找张三的私人信息"));
    }
    @Test void sensitiveMessagesStayRestrictedEvenWhenLoveIsOff() {
        assertEquals("LOVE",ContextPolicy.scope("我刚和女朋友分手",Capabilities.NONE));
        assertEquals("GENERAL",ContextPolicy.scope("解释Java集合",Capabilities.NONE));
    }
    @Test void contextBudgetNeverSplitsTurns() {
        UUID first=UUID.randomUUID(),second=UUID.randomUUID();
        var rows=List.<Map<String,Object>>of(Map.of("run_id",first,"role","USER","content","old"),Map.of("run_id",first,"role","ASSISTANT","content","old answer"),
            Map.of("run_id",second,"role","USER","content","new"),Map.of("run_id",second,"role","ASSISTANT","content","new answer"));
        assertEquals(2,ContextPolicy.trimCompleteTurns(rows,14).size());assertEquals(second,ContextPolicy.trimCompleteTurns(rows,14).getFirst().get("run_id"));
    }
    @Test void conservativeTokenBudgetPreservesUnicodeAndDropsWholeTurns() {
        assertEquals(6,ContextPolicy.tokens("你好"));assertEquals("你",ContextPolicy.clip("你好",5));
        UUID run=UUID.randomUUID();
        var history=List.<Map<String,Object>>of(Map.of("run_id",run,"role","USER","content","你好"),Map.of("run_id",run,"role","ASSISTANT","content","你好"));
        assertTrue(ContextPolicy.trimCompleteTurns(history,100,10).isEmpty());
    }
}
