package com.rlibanez.eplsync.settings;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import com.rlibanez.eplsync.exception.UserInputException;

/** Reads only bounded XML entries; never extracts files or follows external XML entities. */
public final class CalibreEpubMetadata {
    public record Identity(Long eplId, BigDecimal revision) {}
    private CalibreEpubMetadata() {}
    public static Identity read(Path path) {
        String stage="estructura del EPUB";
        try (var zip=new ZipFile(path.toFile())) {
            if(zip.size()>10000) throw new IllegalArgumentException();
            var mime=zip.getEntry("mimetype");
            if(mime==null) throw new IllegalArgumentException();
            try(var in=zip.getInputStream(mime)) {
                if(!java.util.Arrays.equals(in.readNBytes(21),"application/epub+zip".getBytes(java.nio.charset.StandardCharsets.US_ASCII))) throw new IllegalArgumentException();
            }
            stage="contenedor del EPUB";
            var container=xml(zip,"META-INF/container.xml");
            var roots=container.getElementsByTagNameNS("urn:oasis:names:tc:opendocument:xmlns:container","rootfile");
            if(roots.getLength()!=1) throw new IllegalArgumentException();
            String opf=((org.w3c.dom.Element)roots.item(0)).getAttribute("full-path");
            if(opf.isBlank() || opf.startsWith("/") || opf.contains("\\") || opf.contains(":") || java.util.Arrays.stream(opf.split("/",-1)).anyMatch(p -> p.equals("..") || p.equals(".") || p.isEmpty())) throw new IllegalArgumentException();
            stage="metadatos OPF del EPUB";
            var document=xml(zip,opf);
            var fields=document.getElementsByTagNameNS("http://purl.org/dc/elements/1.1/","identifier");
            stage="identificadores EPL y revisión del EPUB";
            Long epl=null;BigDecimal revision=null;
            for(int i=0;i<fields.getLength();i++) {
                var element=(org.w3c.dom.Element)fields.item(i);
                String text=element.getTextContent().trim();
                String scheme=element.getAttributeNS("http://www.idpf.org/2007/opf","scheme");
                if(!scheme.isBlank() && !text.contains(":")) text=scheme+":"+text;
                if(text.regionMatches(true,0,"urn:",0,4)) text=text.substring(4);
                if(text.regionMatches(true,0,"epl:",0,4)) {
                    var parsed=CalibreClient.positiveId(text.substring(4));
                    if(parsed==null || (epl!=null && !epl.equals(parsed))) throw new IllegalArgumentException();epl=parsed;
                }
                if(text.regionMatches(true,0,"revision:",0,9)) {
                    var parsed=CalibreClient.revision(text.substring(9));
                    if(parsed==null || (revision!=null && revision.compareTo(parsed)!=0)) throw new IllegalArgumentException();revision=parsed;
                }
            }
            return new Identity(epl,revision);
        } catch(UserInputException ex) { throw ex; }
        catch(Exception ex) { throw new UserInputException("No se pudo validar " + stage); }
    }
    /** Compare publication resources and OPF structure, ignoring only metadata and XML formatting. */
    static String contentFingerprint(Path path) {
        read(path);
        try(var zip=new ZipFile(path.toFile())) {
            var container=xml(zip,"META-INF/container.xml");
            String opf=((org.w3c.dom.Element)container.getElementsByTagNameNS("urn:oasis:names:tc:opendocument:xmlns:container","rootfile").item(0)).getAttribute("full-path");
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            var names=new java.util.TreeSet<String>();
            var entries=zip.entries();
            while(entries.hasMoreElements()) {var entry=entries.nextElement();if(!entry.isDirectory() && !names.add(entry.getName())) throw new IllegalArgumentException();}
            fingerprintXml(xml(zip,opf).getDocumentElement(),digest,0);
            long total=0;boolean content=false;
            for(String name:names) {
                if(name.equals(opf) || name.equals("mimetype") || name.startsWith("META-INF/")) continue;
                if(name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm")) content=true;
                byte[] label=name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(label.length).array());digest.update(label);
                var resource=java.security.MessageDigest.getInstance("SHA-256");
                try(var input=zip.getInputStream(zip.getEntry(name))) {
                    byte[] buffer=new byte[65536];int count;
                    while((count=input.read(buffer))!=-1) {total+=count;if(total>268435456 || Thread.currentThread().isInterrupted()) throw new IllegalArgumentException();resource.update(buffer,0,count);}
                }
                digest.update(resource.digest());
            }
            if(!content) throw new IllegalArgumentException();
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch(Exception ex) {throw new UserInputException("No se pudo comparar el contenido del EPUB con su descarga original");}
    }
    private static String local(org.w3c.dom.Node node) {return node.getLocalName()==null?node.getNodeName():node.getLocalName();}
    private static void token(java.security.MessageDigest digest,String value) {
        byte[] bytes=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);
    }
    private static void fingerprintXml(org.w3c.dom.Element element,java.security.MessageDigest digest,int depth) {
        if(depth>128) throw new IllegalArgumentException();
        if(local(element).equals("metadata") && element.getParentNode() instanceof org.w3c.dom.Element parent && local(parent).equals("package")) return;
        token(digest,"element:"+java.util.Objects.toString(element.getNamespaceURI(),"")+":"+local(element));
        var attributes=new java.util.TreeMap<String,String>();
        for(int n=0;n<element.getAttributes().getLength();n++) {
            var attr=element.getAttributes().item(n);
            if(XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attr.getNamespaceURI()) || local(element).equals("package") && local(attr).equals("unique-identifier")) continue;
            attributes.put(java.util.Objects.toString(attr.getNamespaceURI(),"")+":"+local(attr),attr.getNodeValue());
        }
        attributes.forEach((name,value)->{token(digest,name);token(digest,value);});token(digest,"children");
        for(var node=element.getFirstChild();node!=null;node=node.getNextSibling()) {
            if(node instanceof org.w3c.dom.Element child) fingerprintXml(child,digest,depth+1);
            else if((node.getNodeType()==org.w3c.dom.Node.TEXT_NODE || node.getNodeType()==org.w3c.dom.Node.CDATA_SECTION_NODE) && !node.getNodeValue().isBlank()) token(digest,node.getNodeValue());
        }
        token(digest,"end");
    }
    private static org.w3c.dom.Document xml(ZipFile zip,String name) throws Exception {
        var entry=zip.getEntry(name);
        if(entry==null || entry.getSize()>1048576) throw new IllegalArgumentException();
        byte[] bytes;
        try(var in=zip.getInputStream(entry)) { bytes=in.readNBytes(1048577); }
        if(bytes.length>1048576) throw new IllegalArgumentException();
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(bytes));
    }
}
