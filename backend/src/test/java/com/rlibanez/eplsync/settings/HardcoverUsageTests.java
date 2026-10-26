package com.rlibanez.eplsync.settings;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HardcoverUsageTests {
    static final String POLICY="\"Free\";q=60;w=60;burst=10, \"daily\";q=5000;w=86400";
    @Test void readsTheDailyQuotaWithoutConfusingItWithBurstCounters() {
        var usage=new HardcoverUsage();
        usage.capture("first-key","\"Free\";r=9;t=0, \"daily\";r=4840;t=8294",POLICY);
        var value=usage.snapshot("first-key");assertThat(value).isNotNull();
        assertThat(value.limit()).isEqualTo(5000);assertThat(value.remaining()).isEqualTo(4840);assertThat(value.used()).isEqualTo(160);
        assertThat(java.time.Duration.between(value.observedAt(),value.resetAt()).getSeconds()).isEqualTo(8294);
        assertThat(usage.snapshot("other-key")).isNull();assertThat(value.toString()).doesNotContain("first-key");
    }
    @Test void missingMalformedAndExpiredHeadersNeverInventUsage() {
        var usage=new HardcoverUsage();
        for(String header:java.util.List.of("",";",",", "\"daily\";r=6000;t=10","\"daily\";r=-1;t=10","\"daily\";r=10;t=90000","\"daily\";r=bad;t=10")) {
            usage.capture("key",header,POLICY);assertThat(usage.snapshot("key")).isNull();
        }
        usage.capture("key","\"daily\";r=10;t=0",POLICY);assertThat(usage.snapshot("key")).isNull();
    }
    @Test void changingKeysKeepsCountersSeparateAndTheCacheIsBounded() {
        var usage=new HardcoverUsage();
        for(int n=0;n<9;n++) usage.capture("key-"+n,"\"daily\";r="+n+";t=60",POLICY);
        assertThat(usage.snapshot("key-0")).isNull();assertThat(usage.snapshot("key-8").remaining()).isEqualTo(8);
    }
}
