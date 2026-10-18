package com.rlibanez.eplsync.settings;

import java.io.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CalibreOriginalFormatTests {
    private byte[] envelope(int tag,int size,byte[] content) throws Exception {
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        out.writeByte(0x81);out.writeByte(0xa6);out.writeBytes("result");out.writeByte(tag);
        if(tag==0xc4) out.writeByte(size);else if(tag==0xc5) out.writeShort(size);else if(tag==0xc6) out.writeInt(size);
        out.write(content);return bytes.toByteArray();
    }
    @Test void copiesAllThreeBinarySizesWithoutTransformingBytes() throws Exception {
        for(int size:new int[]{20,300,70000}) {
            byte[] content=new byte[size];new java.util.Random(1).nextBytes(content);
            int tag=size<=255?0xc4:size<=65535?0xc5:0xc6;var target=new ByteArrayOutputStream();
            CalibreOriginalFormat.copy(new ByteArrayInputStream(envelope(tag,size,content)),target,100000);
            assertThat(target.toByteArray()).isEqualTo(content);
        }
    }
    @Test void rejectsOversizedTruncatedTrailingAndNonBinaryResponses() throws Exception {
        for(byte[] invalid:new byte[][]{envelope(0xc6,Integer.MAX_VALUE,new byte[0]),envelope(0xc4,2,new byte[]{1}),envelope(0xc4,1,new byte[]{1,2}),envelope(0xc0,0,new byte[0]),new byte[]{(byte)0x82}})
            assertThatThrownBy(()->CalibreOriginalFormat.copy(new ByteArrayInputStream(invalid),new ByteArrayOutputStream(),100)).isInstanceOf(IOException.class);
    }
}
