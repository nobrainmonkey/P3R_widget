package app.p3r.dayplanner.v2;
import android.content.Context;import org.json.*;import java.io.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import java.util.*;
/** Immutable-by-copy source assets after manifest verification, no executable external content. */
public final class RulePack {
 private final Map<String,String> files=new HashMap<>();public final String manifestHash,packVersion;
 public RulePack(Context context,String root,String expectedHash)throws Exception{
  byte[] manifest=read(context,root+"manifest.json");manifestHash=sha(manifest);if(!manifestHash.equals(expectedHash))throw new IllegalArgumentException("Pinned rule manifest mismatch");JSONObject meta=new JSONObject(new String(manifest,StandardCharsets.UTF_8));if(!"P3R-base".equals(meta.getString("gameEdition")))throw new IllegalArgumentException("Wrong game edition");packVersion=meta.getString("packVersion");JSONObject entries=meta.getJSONObject("files");
  for(Iterator<String> it=entries.keys();it.hasNext();){String name=it.next();if(!name.matches("[a-zA-Z0-9_.-]+\\.json"))throw new IllegalArgumentException("Invalid rule filename");byte[] data=read(context,root+name);if(!sha(data).equals(entries.getString(name)))throw new IllegalArgumentException("Rule checksum mismatch: "+name);String text=new String(data,StandardCharsets.UTF_8);Object value=new JSONTokener(text).nextValue();if(!(value instanceof JSONObject||value instanceof JSONArray))throw new IllegalArgumentException("Unsupported rule root");files.put(name,text);}
 }
 public JSONObject object(String name)throws JSONException{String value=files.get(name);if(value==null)throw new IllegalArgumentException("Unverified rule file");return new JSONObject(value);}
 public JSONArray array(String name)throws JSONException{String value=files.get(name);if(value==null)throw new IllegalArgumentException("Unverified rule file");return new JSONArray(value);}
 private static byte[] read(Context c,String path)throws IOException{try(InputStream in=c.getAssets().open(path);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];for(int n;(n=in.read(buffer))!=-1;)out.write(buffer,0,n);return out.toByteArray();}}
 private static String sha(byte[] bytes)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
}
