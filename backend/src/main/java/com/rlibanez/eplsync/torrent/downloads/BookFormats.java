package com.rlibanez.eplsync.torrent.downloads;

import java.util.*;
import com.rlibanez.eplsync.exception.UserInputException;
public final class BookFormats {
    public static final Set<String> SUPPORTED=Set.of("epub","azw3","mobi","pdf");
    private BookFormats() {}
    public static String extension(String name) {
        String leaf=name.replace('\\','/');leaf=leaf.substring(leaf.lastIndexOf('/')+1);
        int dot=leaf.lastIndexOf('.');String ext=dot<0?"":leaf.substring(dot+1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,16}")?ext:"";
    }
    public static Set<String> allowed(List<String> values) {
        var result=new HashSet<>(values==null?List.of("epub"):values);
        if(result.isEmpty() || result.contains(null) || !SUPPORTED.containsAll(result)) throw new UserInputException("Selecciona formatos compatibles: EPUB, AZW3, MOBI o PDF");
        return Set.copyOf(result);
    }
    public static void validate(java.nio.file.Path path,String format) throws java.io.IOException {
        try(var in=java.nio.file.Files.newInputStream(path)) {
            byte[] header=in.readNBytes(1024);
            boolean valid=format.equals("pdf")?new String(header,java.nio.charset.StandardCharsets.ISO_8859_1).contains("%PDF-"):
                Set.of("mobi","azw3").contains(format) && header.length>=78 && new String(header,60,8,java.nio.charset.StandardCharsets.US_ASCII).equals("BOOKMOBI");
            if(!valid) throw new UserInputException("El contenido del archivo no corresponde al formato "+format.toUpperCase(Locale.ROOT));
        }
    }
}
