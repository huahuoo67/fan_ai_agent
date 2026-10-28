package com.fan.fanaiagent.platform;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.fan.fanaiagent.platform.PlatformTypes.*;

class MemoryPolicyTest {
    final OffsetDateTime now=OffsetDateTime.parse("2026-10-09T00:00:00Z");
    MemoryFact fact(String subject,String value,String kind,boolean sensitive,OffsetDateTime expiry,int time) {
        return new MemoryFact(UUID.randomUUID(),subject,"diet.spicy",value,kind,"GENERAL",sensitive,expiry,"ACTIVE",1,1,UUID.randomUUID(),now.plusMinutes(time));
    }
    @Test void temporaryRestrictionWinsWithoutDestroyingPreference() {
        var preference=fact("SELF","喜欢吃辣","PREFERENCE",false,null,0);
        var restriction=fact("SELF","不吃辣","TEMPORARY",true,null,1);
        var result=MemoryResolver.resolve(List.of(preference,restriction),now.plusHours(1));
        assertEquals(List.of(restriction),result.facts());assertEquals("喜欢吃辣",preference.value());assertFalse(result.notices().isEmpty());
    }
    @Test void healthExpiryDoesNotInventRecovery() {
        var f=fact("SELF","不吃辣","TEMPORARY",true,now.minusDays(1),0);
        assertEquals(List.of(f),MemoryResolver.resolve(List.of(f),now).facts());
    }
    @Test void ordinaryExpiredExceptionFallsBackToPreference() {
        var p=fact("SELF","喜欢吃辣","PREFERENCE",false,null,0);
        var t=fact("SELF","不吃辣","TEMPORARY",false,now.minusDays(1),1);
        assertEquals(List.of(p),MemoryResolver.resolve(List.of(p,t),now).facts());
    }
    @Test void differentSubjectsNeverOverwriteEachOther() {
        var a=fact("SELF","喜欢吃辣","PREFERENCE",false,null,0);var b=fact("PARTNER","不吃辣","PREFERENCE",false,null,1);
        assertEquals(2,MemoryResolver.resolve(List.of(a,b),now).facts().size());
    }
    @Test void explicitCorrectionExcludesOldAssertion() {
        var old=fact("SELF","喜欢吃辣","PREFERENCE",false,null,0);var correction=fact("SELF","纠正：我没说过","RETRACT",false,null,1);
        assertTrue(MemoryResolver.resolve(List.of(old,correction),now).facts().isEmpty());
    }
    @Test void recoveryRetractsTemporaryLayerOnly() {
        var p=fact("SELF","喜欢吃辣","PREFERENCE",false,null,0);var t=fact("SELF","不吃辣","TEMPORARY",true,null,1);
        var recovery=fact("SELF","用户明确解除近期吃辣限制","RETRACT",false,null,2);
        assertEquals(List.of(p),MemoryResolver.resolve(List.of(p,t,recovery),now).facts());
    }
    @Test void vagueHotpotRequestNeverOverridesHealthRestriction() {
        var t=fact("SELF","不吃辣","TEMPORARY",true,null,1);var task=fact("SELF","喜欢吃辣","TASK",false,null,2);
        assertEquals(List.of(t),MemoryResolver.resolve(List.of(t,task),now).facts());
    }
    @ParameterizedTest @ValueSource(strings={"假设我喜欢吃辣","比如我不吃辣","帮我写一句我喜欢吃辣","我可能不吃辣","我的密码是abcdef123456","引用：我不吃香菜","你说我不吃香菜，但我从来没说过这些"})
    void quotesUncertaintyAndSecretsAreNotPositiveFacts(String text) {
        assertTrue(MemoryExtractor.extract(text,"GENERAL").stream().allMatch(d->"RETRACT".equals(d.kind())));
    }
    @Test void subjectAndTemporaryStateAreExtracted() {
        var third=MemoryExtractor.extract("我对象不吃辣","GENERAL").getFirst();assertEquals("PARTNER",third.subject());assertEquals("LOVE",third.scope());
        var temporary=MemoryExtractor.extract("我最近感冒了暂时不吃辣","GENERAL").getFirst();assertEquals("TEMPORARY",temporary.kind());assertTrue(temporary.sensitive());assertNull(temporary.expiresAt());
    }
    @Test void explicitDurationAndTaskScope() {
        assertNotNull(MemoryExtractor.extract("我未来3天暂时不吃辣","GENERAL").getFirst().expiresAt());
        assertEquals("TASK",MemoryExtractor.extract("我这顿不要辣","GENERAL").getFirst().kind());
    }
    @Test void genericPersonalQuestionHasNoFabricatedProfile() {
        String text=PlatformModel.special("说出关于我的信息",new EffectiveMemory(List.of(),List.of()));
        assertFalse(text.contains("香菜"));assertFalse(text.contains("泡面"));assertTrue(text.contains("没有可用"));
    }
    @Test void retentionAnswerUsesActualPolicy() {
        String text=PlatformModel.special("你的聊天记录能保持多久",new EffectiveMemory(List.of(),List.of()));
        assertNotNull(text);assertTrue(text.contains("30条"));assertTrue(text.contains("删除"));
    }
    @Test void motherIsNotTheCurrentUserAndRecoveryIsExplicit() {
        assertEquals("THIRD:妈妈",MemoryExtractor.extract("我妈妈不吃辣","GENERAL").getFirst().subject());
        assertEquals("RETRACT",MemoryExtractor.extract("我感冒好了，现在恢复吃辣","GENERAL").getFirst().kind());
        assertTrue(MemoryExtractor.extract("记住：sk-abcdefghijklmnopqrs","GENERAL").isEmpty());
    }
    @Test void equalConfidenceConflictUsesRestrictionAndAsksForConfirmation() {
        var yes=fact("SELF","喜欢吃辣","PREFERENCE",false,null,0);
        var no=fact("SELF","不吃辣","PREFERENCE",false,null,0);
        var result=MemoryResolver.resolve(List.of(yes,no),now);
        assertEquals(List.of(no),result.facts());assertTrue(result.notices().stream().anyMatch(s->s.contains("请确认")));
    }
}
