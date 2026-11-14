import android.app.UiAutomation;
import android.os.HandlerThread;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.accessibilityservice.AccessibilityServiceInfo;
import org.xmlpull.v1.XmlSerializer;
import android.util.Xml;
import java.io.StringWriter;
public class UiDump {
 static boolean setText(AccessibilityNodeInfo n,int[] index,String value) {
  if(n==null) return false;
  if(n.isEditable() && index[0]--==0) {
   android.os.Bundle args=new android.os.Bundle();
   args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);
   return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args);
  }
  for(int i=0;i<n.getChildCount();i++) if(setText(n.getChild(i),index,value)) return true;
  return false;
 }
 static void node(XmlSerializer x, AccessibilityNodeInfo n) throws Exception {
  if(n==null) return;
  Rect b=new Rect(); n.getBoundsInScreen(b);
  x.startTag(null,"node");
  x.attribute(null,"text",n.getText()==null?"":n.getText().toString());
  x.attribute(null,"class",n.getClassName()==null?"":n.getClassName().toString());
  x.attribute(null,"package",n.getPackageName()==null?"":n.getPackageName().toString());
  x.attribute(null,"resource-id",n.getViewIdResourceName()==null?"":n.getViewIdResourceName());
  x.attribute(null,"content-desc",n.getContentDescription()==null?"":n.getContentDescription().toString());
  x.attribute(null,"bounds","["+b.left+","+b.top+"]["+b.right+","+b.bottom+"]");
  x.attribute(null,"enabled",Boolean.toString(n.isEnabled()));
  x.attribute(null,"clickable",Boolean.toString(n.isClickable()));
  for(int i=0;i<n.getChildCount();i++) node(x,n.getChild(i));
  x.endTag(null,"node");
 }
 public static void main(String[] args) throws Exception {
  HandlerThread thread=new HandlerThread("moatrip-ui");thread.start();
  Class<?> connection=Class.forName("android.app.UiAutomationConnection");
  Class<?> iface=Class.forName("android.app.IUiAutomationConnection");
  UiAutomation ui=(UiAutomation)UiAutomation.class.getConstructor(android.os.Looper.class,iface).newInstance(thread.getLooper(),connection.getConstructor().newInstance());
  UiAutomation.class.getMethod("connect").invoke(ui);
  try {
   if(args.length==3 && args[0].equals("text")) {
    String value=new String(android.util.Base64.decode(args[2],android.util.Base64.DEFAULT),java.nio.charset.StandardCharsets.UTF_8);
    boolean changed=setText(ui.getRootInActiveWindow(),new int[]{Integer.parseInt(args[1])},value);
    System.out.println("{\"changed\":"+changed+"}");return;
   }
   StringWriter out=new StringWriter();XmlSerializer x=Xml.newSerializer();x.setOutput(out);x.startDocument("UTF-8",true);
   x.startTag(null,"hierarchy");
   if(args.length>0 && args[0].equals("all")) {
    AccessibilityServiceInfo info=ui.getServiceInfo();
    info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ui.setServiceInfo(info);
    for(AccessibilityWindowInfo window:ui.getWindows()) node(x,window.getRoot());
   } else node(x,ui.getRootInActiveWindow());
   x.endTag(null,"hierarchy");x.endDocument();
   System.out.println(out.toString());
  } finally {UiAutomation.class.getMethod("disconnect").invoke(ui);thread.quitSafely();}
 }
}
