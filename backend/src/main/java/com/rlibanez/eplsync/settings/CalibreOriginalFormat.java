package com.rlibanez.eplsync.settings;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** The read-only export command returns one MessagePack map entry, result: binary.
 * Parse only that bounded protocol shape; no general object deserialization or buffering. */
final class CalibreOriginalFormat {
    private CalibreOriginalFormat() {}
    static void copy(InputStream source,OutputStream target,long maxBytes) throws IOException {
        var input=new DataInputStream(source);
        int map=input.readUnsignedByte();
        if(map!=0x81 || !string(input,32).equals("result")) throw invalid();
        int tag=input.readUnsignedByte();
        long size=switch(tag) {
            case 0xc4 -> input.readUnsignedByte();
            case 0xc5 -> input.readUnsignedShort();
            case 0xc6 -> Integer.toUnsignedLong(input.readInt());
            default -> throw invalid();
        };
        if(size<=0 || size>maxBytes) throw invalid();
        byte[] buffer=new byte[65536];
        for(long remaining=size;remaining>0;) {
            if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            int count=input.read(buffer,0,(int)Math.min(buffer.length,remaining));
            if(count<0) throw new EOFException();
            target.write(buffer,0,count);remaining-=count;
        }
        if(input.read()!=-1) throw invalid();
    }
    private static String string(DataInputStream input,int max) throws IOException {
        int tag=input.readUnsignedByte();
        int size=(tag&0xe0)==0xa0?tag&0x1f:switch(tag) {
            case 0xd9 -> input.readUnsignedByte();
            case 0xda -> input.readUnsignedShort();
            default -> throw invalid();
        };
        if(size>max) throw invalid();
        byte[] bytes=new byte[size];input.readFully(bytes);return new String(bytes,StandardCharsets.UTF_8);
    }
    private static IOException invalid() {return new IOException("Respuesta de exportación original de Calibre inválida o demasiado grande");}
}
